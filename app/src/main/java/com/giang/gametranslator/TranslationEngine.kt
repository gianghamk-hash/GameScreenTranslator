package com.giang.gametranslator

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Động cơ dịch hợp nhất tối ưu tốc độ & độ ổn định tối đa:
 * - Xoay vòng nhiều Gemini API Key + cơ chế Cooldown 35s khi dính 429 (tự nhảy key hoặc lùi sang Offline mượt mà).
 * - Dịch câu thoại đơn (HTTP/2 pooling, hủy request cũ, thinkingBudget=0 trên 2.5).
 * - Dịch lô toàn màn hình 1-request (AI) hoặc song song đa luồng (Offline ML Kit).
 * - Ghi log kỹ thuật vào DiagnosticLogStore khi gặp lỗi mạng/API/xoay key.
 */
class TranslationEngine(private val appContext: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var activeBatchCall: Call? = null

    private val recentHistory = ArrayDeque<Pair<String, String>>(5)
    private val historyLock = Any()

    // Quản lý xoay vòng nhiều API Key & thời gian nghỉ khi dính 429
    private val nextKeyIndex = AtomicInteger(0)
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val cooldownDurationMs = 35_000L

    private var offlineTranslator: Translator? = null

    @Synchronized
    private fun getOrCreateOfflineTranslator(): Translator {
        return offlineTranslator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        ).also { offlineTranslator = it }
    }

    fun clearHistory() {
        synchronized(historyLock) {
            recentHistory.clear()
        }
    }

    fun cancelActiveRequest() {
        activeCall?.cancel()
        activeCall = null
    }

    fun cancelBatchRequest() {
        activeBatchCall?.cancel()
        activeBatchCall = null
    }

    @Synchronized
    fun close() {
        cancelActiveRequest()
        cancelBatchRequest()
        offlineTranslator?.close()
        offlineTranslator = null
    }

    private fun pickAvailableKey(keys: List<String>, excluded: Set<String> = emptySet()): Pair<String, Int>? {
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val start = Math.floorMod(nextKeyIndex.get(), keys.size)
        for (offset in keys.indices) {
            val idx = (start + offset) % keys.size
            val candidate = keys[idx]
            if (candidate in excluded) continue
            val until = keyCooldownUntil[candidate] ?: 0L
            if (now >= until) {
                nextKeyIndex.set((idx + 1) % keys.size)
                return candidate to (idx + 1)
            }
        }
        return null
    }

    private fun markKeyRateLimited(key: String, keyNumber: Int) {
        keyCooldownUntil[key] = System.currentTimeMillis() + cooldownDurationMs
        DiagnosticLogStore.warn(appContext, "API Key #$keyNumber chạm 429 → tạm nghỉ 35s")
    }

    // ==================== 1. DỊCH CÂU THOẠI ĐƠN (SUBTITLE) ====================
    fun translate(
        englishText: String,
        settings: TranslatorSettings,
        onSuccess: (translated: String, usedOffline: Boolean) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val keys = settings.apiKeys()
        when (settings.translationMode) {
            TranslationMode.OFFLINE -> {
                OfflineGameDictionary.lookupExactPhrase(englishText, settings)?.let { exact ->
                    onSuccess(exact, true)
                    return
                }
                translateOffline(englishText, settings, onSuccess, onError)
            }
            TranslationMode.ONLINE -> {
                if (keys.isEmpty()) {
                    onError("Chưa nhập Gemini API Key trong App!")
                    return
                }
                translateWithGemini(
                    englishText = englishText,
                    settings = settings,
                    keys = keys,
                    triedKeys = emptySet(),
                    allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    allowOfflineFallback = false,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
            TranslationMode.HYBRID -> {
                if (keys.isEmpty()) {
                    translateOffline(englishText, settings, onSuccess, onError)
                } else {
                    translateWithGemini(
                        englishText = englishText,
                        settings = settings,
                        keys = keys,
                        triedKeys = emptySet(),
                        allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                        allowOfflineFallback = true,
                        onSuccess = onSuccess,
                        onError = onError
                    )
                }
            }
        }
    }

    // ==================== 2. DỊCH LÔ TOÀN MÀN HÌNH (SINGLE-SHOT BATCH) ====================
    fun translateBatch(
        texts: List<String>,
        settings: TranslatorSettings,
        onSuccess: (translations: List<String>, usedOffline: Boolean) -> Unit,
        onError: (message: String) -> Unit
    ) {
        if (texts.isEmpty()) {
            onSuccess(emptyList(), false)
            return
        }

        val keys = settings.apiKeys()
        when (settings.translationMode) {
            TranslationMode.OFFLINE -> {
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            }
            TranslationMode.ONLINE -> {
                if (keys.isEmpty()) {
                    onError("Chưa nhập Gemini API Key trong App!")
                    return
                }
                translateBatchWithGemini(
                    texts = texts,
                    settings = settings,
                    keys = keys,
                    triedKeys = emptySet(),
                    allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    allowOfflineFallback = false,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
            TranslationMode.HYBRID -> {
                if (keys.isEmpty()) {
                    translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                } else {
                    translateBatchWithGemini(
                        texts = texts,
                        settings = settings,
                        keys = keys,
                        triedKeys = emptySet(),
                        allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                        allowOfflineFallback = true,
                        onSuccess = onSuccess,
                        onError = onError
                    )
                }
            }
        }
    }

    private fun translateBatchWithGemini(
        texts: List<String>,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowThinkingConfig: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (List<String>, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowOfflineFallback) {
                DiagnosticLogStore.warn(appContext, "Tất cả Key đang nghỉ 429 → tự chuyển dịch toàn màn hình Offline")
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            } else {
                onError("Tất cả API Key đang tạm nghỉ do giới hạn 429, thử lại sau vài giây!")
            }
            return
        }
        val (apiKey, keyNum) = picked

        cancelBatchRequest()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.modelName}:generateContent?key=$apiKey"

        val glossaryTerms = settings.glossaryTerms()
        val glossaryRule = if (glossaryTerms.isNotEmpty()) {
            " Giữ nguyên: ${glossaryTerms.joinToString(", ")}."
        } else {
            ""
        }

        val systemText = """
            Bạn là trình dịch giao diện/menu/kho đồ game siêu tốc sang tiếng Việt.
            - Dịch cực ngắn gọn, đúng ngữ cảnh game, giữ nguyên tên riêng, địa danh và số liệu.$glossaryRule
            - Input gồm các dòng đánh số [0], [1], ...
            - BẮT BUỘC trả về đúng số lượng dòng theo đúng định dạng [0] bản dịch, [1] bản dịch... Không thêm lời giải thích.
        """.trimIndent()

        val userPrompt = buildString {
            texts.forEachIndexed { idx, s ->
                append('[').append(idx).append("] ").append(s).append('\n')
            }
        }

        val generationConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", 1024)
            if (allowThinkingConfig) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val jsonBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                })
            })
            put("generationConfig", generationConfig)
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = httpClient.newCall(request)
        activeBatchCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                DiagnosticLogStore.warn(appContext, "Lỗi mạng dịch toàn màn hình: ${e.message}")
                if (allowOfflineFallback) {
                    translateBatchOfflineParallel(texts, settings, onSuccess) { offErr ->
                        onError("Lỗi mạng (${e.message}) • Offline: $offErr")
                    }
                } else {
                    onError("Lỗi mạng: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    response.close()
                    return
                }
                response.use {
                    val bodyStr = it.body?.string() ?: ""
                    if (it.isSuccessful) {
                        try {
                            val json = JSONObject(bodyStr)
                            val rawOut = json.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                                .trim()

                            val parsed = parseIndexedBatchOutput(rawOut, texts)
                            onSuccess(parsed, false)
                        } catch (e: Exception) {
                            DiagnosticLogStore.error(appContext, "Lỗi parse JSON toàn màn hình: ${e.message}")
                            if (allowOfflineFallback) {
                                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc kết quả dịch toàn màn hình: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited(apiKey, keyNum)
                        translateBatchWithGemini(
                            texts = texts,
                            settings = settings,
                            keys = keys,
                            triedKeys = triedKeys + apiKey,
                            allowThinkingConfig = allowThinkingConfig,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateBatchWithGemini(
                            texts = texts,
                            settings = settings,
                            keys = keys,
                            triedKeys = triedKeys,
                            allowThinkingConfig = false,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else {
                        DiagnosticLogStore.error(appContext, "Batch API HTTP ${it.code} (Key #$keyNum)")
                        if (allowOfflineFallback) {
                            translateBatchOfflineParallel(texts, settings, onSuccess) { offErr ->
                                onError("API ${it.code} • Offline: $offErr")
                            }
                        } else {
                            onError("Lỗi API ${it.code}: ${bodyStr.take(100)}")
                        }
                    }
                }
            }
        })
    }

    private fun parseIndexedBatchOutput(rawOut: String, originals: List<String>): List<String> {
        val result = originals.toMutableList()
        val regex = Regex("^\\[(\\d+)]\\s*(.+)$")
        for (line in rawOut.lines()) {
            val trimmed = line.trim()
            val match = regex.find(trimmed)
            if (match != null) {
                val idx = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim().removeSurrounding("\"")
                if (idx != null && idx in result.indices && text.isNotEmpty()) {
                    result[idx] = text
                }
            }
        }
        return result
    }

    private fun translateBatchOfflineParallel(
        texts: List<String>,
        settings: TranslatorSettings,
        onSuccess: (List<String>, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val translator = getOrCreateOfflineTranslator()
        isOfflineModelDownloaded { downloaded ->
            if (!downloaded) {
                onError("Chưa tải gói dịch Offline (Anh → Việt) trong màn hình chính!")
                return@isOfflineModelDownloaded
            }

            val results = Array(texts.size) { texts[it] }
            val remaining = AtomicInteger(texts.size)
            val terms = settings.glossaryTerms().sortedByDescending { it.length }

            texts.forEachIndexed { idx, original ->
                val exact = OfflineGameDictionary.lookupExactPhrase(original, settings)
                if (exact != null) {
                    results[idx] = exact
                    if (remaining.decrementAndGet() == 0) {
                        onSuccess(results.toList(), true)
                    }
                } else {
                    val prepared = OfflineGameDictionary.prepareForOffline(original, settings)
                    translator.translate(prepared.textForMlKit)
                        .addOnSuccessListener { translated ->
                            results[idx] = OfflineGameDictionary.postProcessOffline(
                                translated,
                                prepared,
                                settings.translationStyle
                            )
                            if (remaining.decrementAndGet() == 0) {
                                onSuccess(results.toList(), true)
                            }
                        }
                        .addOnFailureListener {
                            if (remaining.decrementAndGet() == 0) {
                                onSuccess(results.toList(), true)
                            }
                        }
                }
            }
        }
    }

    private fun translateWithGemini(
        englishText: String,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowThinkingConfig: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowOfflineFallback) {
                translateOffline(englishText, settings, onSuccess) {
                    onError("Đang tạm nghỉ 429 (chưa tải gói Offline dự phòng)")
                }
            } else {
                onError("Chạm giới hạn 429, đang tạm nghỉ 35s...")
            }
            return
        }
        val (apiKey, keyNum) = picked

        cancelActiveRequest()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.modelName}:generateContent?key=$apiKey"

        val glossaryTerms = settings.glossaryTerms()
        val customPairs = settings.customReplacements()
        val glossaryRule = buildString {
            if (glossaryTerms.isNotEmpty()) {
                append("\n- Danh sách tên riêng/thuật ngữ bắt buộc giữ nguyên: ${glossaryTerms.joinToString(", ")}.")
            }
            if (customPairs.isNotEmpty()) {
                append("\n- Thuật ngữ dịch cố định: ${customPairs.joinToString(", ") { "${it.first} -> ${it.second}" }}.")
            }
        }

        val systemText = """
            Bạn là chuyên gia Việt hóa phụ đề game thời gian thực.
            Quy tắc bắt buộc:
            - ${settings.translationStyle.instruction}
            - GIỮ NGUYÊN 100% tên nhân vật, tên người, tên địa danh, vùng đất, tổ chức (tuyệt đối không dịch nghĩa đen tên người hay địa danh).$glossaryRule
            - Nếu có tên người nói ở đầu câu (vd "Cloud: ..."), giữ nguyên định dạng "Tên: ...".
            - Dịch tự nhiên, súc tích, đúng ngữ cảnh game. Chỉ trả về DUY NHẤT câu tiếng Việt, không ngoặc kép thừa, không giải thích.
        """.trimIndent()

        val userPrompt = buildString {
            if (settings.useContextMemory) {
                val snapshot = synchronized(historyLock) { recentHistory.toList() }
                if (snapshot.isNotEmpty()) {
                    append("Ngữ cảnh thoại gần nhất (để giữ xưng hô nhất quán):\n")
                    for ((en, vi) in snapshot) {
                        append("EN: ").append(en).append(" -> VI: ").append(vi).append('\n')
                    }
                    append('\n')
                }
            }
            append("Dịch câu thoại sau sang tiếng Việt:\n")
            append(englishText)
        }

        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 256)
            if (allowThinkingConfig) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val jsonBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                })
            })
            put("generationConfig", generationConfig)
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = httpClient.newCall(request)
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                DiagnosticLogStore.warn(appContext, "Lỗi mạng Gemini: ${e.message}")
                if (allowOfflineFallback) {
                    translateOffline(englishText, settings, onSuccess) { offlineErr ->
                        onError("Lỗi mạng (${e.message}) • Offline: $offlineErr")
                    }
                } else {
                    onError("Lỗi mạng: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    response.close()
                    return
                }
                response.use {
                    val bodyStr = it.body?.string() ?: ""
                    if (it.isSuccessful) {
                        try {
                            val json = JSONObject(bodyStr)
                            val translated = json.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                                .trim()
                                .removeSurrounding("\"")

                            if (settings.useContextMemory && translated.isNotEmpty()) {
                                synchronized(historyLock) {
                                    if (recentHistory.size >= 4) recentHistory.removeFirst()
                                    recentHistory.addLast(englishText to translated)
                                }
                            }
                            onSuccess(translated, false)
                        } catch (e: Exception) {
                            DiagnosticLogStore.error(appContext, "Lỗi parse JSON Gemini: ${e.message}")
                            if (allowOfflineFallback) {
                                translateOffline(englishText, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc JSON: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited(apiKey, keyNum)
                        translateWithGemini(
                            englishText = englishText,
                            settings = settings,
                            keys = keys,
                            triedKeys = triedKeys + apiKey,
                            allowThinkingConfig = allowThinkingConfig,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateWithGemini(
                            englishText = englishText,
                            settings = settings,
                            keys = keys,
                            triedKeys = triedKeys,
                            allowThinkingConfig = false,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else {
                        DiagnosticLogStore.error(appContext, "Gemini HTTP ${it.code} (Key #$keyNum): ${bodyStr.take(80)}")
                        if (allowOfflineFallback) {
                            translateOffline(englishText, settings, onSuccess) { offlineErr ->
                                onError("API ${it.code} • Offline: $offlineErr")
                            }
                        } else {
                            onError("Lỗi API ${it.code}: ${bodyStr.take(100)}")
                        }
                    }
                }
            }
        })
    }

    private fun translateOffline(
        englishText: String,
        settings: TranslatorSettings,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val translator = getOrCreateOfflineTranslator()
        isOfflineModelDownloaded { downloaded ->
            if (!downloaded) {
                onError("Chưa tải gói dịch Offline (Anh → Việt) trong màn hình chính!")
                return@isOfflineModelDownloaded
            }

            OfflineGameDictionary.lookupExactPhrase(englishText, settings)?.let { exact ->
                onSuccess(exact, true)
                return@isOfflineModelDownloaded
            }
            val prepared = OfflineGameDictionary.prepareForOffline(englishText, settings)

            translator.translate(prepared.textForMlKit)
                .addOnSuccessListener { rawTranslated ->
                    onSuccess(
                        OfflineGameDictionary.postProcessOffline(
                            rawTranslated,
                            prepared,
                            settings.translationStyle
                        ),
                        true
                    )
                }
                .addOnFailureListener { e ->
                    DiagnosticLogStore.error(appContext, "Lỗi ML Kit Offline: ${e.message}")
                    onError("Lỗi dịch Offline: ${e.message}")
                }
        }
    }

    private fun protectGlossary(text: String, terms: List<String>): Pair<String, Map<String, String>> {
        if (terms.isEmpty()) return text to emptyMap()
        val placeholders = mutableMapOf<String, String>()
        var prepared = text
        terms.forEachIndexed { idx, term ->
            val token = "ZXQ${idx}K"
            val pattern = Pattern.compile("\\b${Pattern.quote(term)}\\b", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(prepared)
            if (matcher.find()) {
                placeholders[token] = term
                prepared = matcher.replaceAll(token)
            }
        }
        return prepared to placeholders
    }

    private fun restoreGlossary(translated: String, placeholders: Map<String, String>): String {
        var out = translated
        for ((token, originalTerm) in placeholders) {
            out = out.replace(token, originalTerm, ignoreCase = true)
        }
        return out
    }

    companion object {
        fun isOfflineModelDownloaded(callback: (Boolean) -> Unit) {
            val modelManager = RemoteModelManager.getInstance()
            val viModel = TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()
            modelManager.isModelDownloaded(viModel)
                .addOnSuccessListener { callback(it) }
                .addOnFailureListener { callback(false) }
        }

        fun downloadOfflineModel(
            onSuccess: () -> Unit,
            onError: (String) -> Unit
        ) {
            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
            )
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.close()
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    translator.close()
                    onError(e.message ?: "Không thể tải gói ngôn ngữ Offline")
                }
        }

        fun deleteOfflineModel(onDone: (Boolean) -> Unit) {
            val modelManager = RemoteModelManager.getInstance()
            val viModel = TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()
            modelManager.deleteDownloadedModel(viModel)
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        }
    }
}
