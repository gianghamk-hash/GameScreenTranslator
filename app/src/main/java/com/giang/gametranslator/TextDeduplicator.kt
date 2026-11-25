package com.giang.gametranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chống dịch lặp đa tầng:
 * 1. Frame pixel signature: bỏ qua OCR nếu khung hình vùng thoại không đổi.
 * 2. Chuẩn hóa chuỗi & Fuzzy similarity (Levenshtein >= 86%): bỏ qua nhiễu OCR 1-2 ký tự.
 * 3. Typewriter debounce: chờ câu thoại chạy chữ ổn định ngắn trước khi gọi API.
 * 4. LRU Cache 400 mục dùng chung cho cả phụ đề & dịch toàn màn hình (0ms khi lặp lại).
 */
class TextDeduplicator {

    enum class Decision {
        SKIP_DUPLICATE,
        WAIT_STABILIZE,
        USE_CACHE,
        TRANSLATE
    }

    data class Evaluation(
        val decision: Decision,
        val normalizedText: String,
        val cachedTranslation: String? = null
    )

    private val translationCache = LruCache<String, String>(400)
    private var lastTranslatedNormalized = ""
    private var lastFrameSignature: IntArray? = null

    private var pendingText = ""
    private var pendingTicks = 0

    fun resetSession() {
        lastTranslatedNormalized = ""
        lastFrameSignature = null
        pendingText = ""
        pendingTicks = 0
    }

    fun clearCache() {
        translationCache.evictAll()
        resetSession()
    }

    fun onEmptyFrame() {
        lastTranslatedNormalized = ""
        pendingText = ""
        pendingTicks = 0
    }

    fun getCached(rawText: String, cacheNamespace: String): String? {
        val norm = normalize(rawText)
        if (norm.isEmpty()) return null
        return translationCache.get("$cacheNamespace|$norm")
    }

    fun putCached(rawText: String, cacheNamespace: String, translated: String) {
        val norm = normalize(rawText)
        if (norm.isNotEmpty() && translated.isNotBlank()) {
            translationCache.put("$cacheNamespace|$norm", translated.trim())
        }
    }

    fun isFrameUnchanged(bitmap: Bitmap): Boolean {
        val cols = 8
        val rows = 4
        val w = bitmap.width
        val h = bitmap.height
        if (w < cols || h < rows) return false

        val currentSig = IntArray(cols * rows)
        var idx = 0
        for (r in 0 until rows) {
            val py = ((r + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val px = ((c + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                val pixel = bitmap.getPixel(px, py)
                val luma = (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10
                currentSig[idx++] = luma
            }
        }

        val prev = lastFrameSignature
        lastFrameSignature = currentSig
        if (prev == null || prev.size != currentSig.size) return false

        var totalDiff = 0
        for (i in currentSig.indices) {
            totalDiff += abs(currentSig[i] - prev[i])
        }
        val avgDiff = totalDiff.toFloat() / currentSig.size
        return avgDiff < 5.5f
    }

    fun resetFrameSignature() {
        lastFrameSignature = null
    }

    fun evaluate(
        rawText: String,
        cacheNamespace: String,
        antiDuplicateEnabled: Boolean
    ): Evaluation {
        val normalized = normalize(rawText)
        if (normalized.length < 2) {
            return Evaluation(Decision.SKIP_DUPLICATE, normalized)
        }

        if (normalized == lastTranslatedNormalized) {
            pendingText = ""
            pendingTicks = 0
            return Evaluation(Decision.SKIP_DUPLICATE, normalized)
        }

        if (antiDuplicateEnabled && lastTranslatedNormalized.isNotEmpty()) {
            val sim = similarity(normalized, lastTranslatedNormalized)
            val lenDelta = abs(normalized.length - lastTranslatedNormalized.length)
            if (sim >= 0.86f && lenDelta <= max(3, (lastTranslatedNormalized.length * 0.12f).toInt())) {
                return Evaluation(Decision.SKIP_DUPLICATE, normalized)
            }
        }

        val cacheKey = "$cacheNamespace|$normalized"
        val cached = translationCache.get(cacheKey)
        if (cached != null) {
            lastTranslatedNormalized = normalized
            pendingText = ""
            pendingTicks = 0
            return Evaluation(Decision.USE_CACHE, normalized, cached)
        }

        if (antiDuplicateEnabled) {
            if (pendingText.isNotEmpty() &&
                normalized.length > pendingText.length + 2 &&
                normalized.startsWith(pendingText.take(min(12, pendingText.length))) &&
                pendingTicks < 2
            ) {
                pendingText = normalized
                pendingTicks++
                return Evaluation(Decision.WAIT_STABILIZE, normalized)
            }
            if (pendingText.isEmpty() && normalized.length in 4..45 && !endsWithSentencePunctuation(rawText)) {
                pendingText = normalized
                pendingTicks = 1
                return Evaluation(Decision.WAIT_STABILIZE, normalized)
            }
        }

        pendingText = ""
        pendingTicks = 0
        return Evaluation(Decision.TRANSLATE, normalized)
    }

    fun recordTranslated(normalizedText: String, cacheNamespace: String, translated: String) {
        lastTranslatedNormalized = normalizedText
        translationCache.put("$cacheNamespace|$normalizedText", translated)
    }

    fun normalize(text: String): String {
        return text
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("[▼▸►▶_]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private fun endsWithSentencePunctuation(raw: String): Boolean {
        val t = raw.trim()
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("\"") || t.endsWith("'")
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        if (abs(a.length - b.length) > maxLen * 0.35f) return 0f

        val dist = levenshtein(a, b)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshtein(s: String, t: String): Int {
        val m = s.length
        val n = t.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            val sc = s[i - 1]
            for (j in 1..n) {
                val cost = if (sc == t[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
