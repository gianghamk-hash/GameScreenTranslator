package com.giang.gametranslator

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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Động cơ dịch hợp nhất tối ưu tốc độ tối đa:
 * - Dịch câu thoại đơn (Subtitle): HTTP/2 pooling, hủy request cũ, thinkingBudget=0 trên 2.5.
 * - Dịch lô toàn màn hình (Batch Full-Screen): gom toàn bộ khối chữ Menu/Kho đồ vào 1 request duy nhất
 *   (hoặc dịch song song đa luồng khi chạy Offline ML Kit).
 * - Văn phong tùy chọn + giữ nguyên tên nhân vật/địa danh + nhớ ngữ cảnh 4 câu thoại gần nhất.
 */
class TranslationEngine {

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

    private var offlineTranslator: Translator? = null

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

    fun close() {
        cancelActiveRequest()
        cancelBatchRequest()
        offlineTranslator?.close()
        offlineTranslator = null
    }

    // ==================== 1. DỊCH CÂU THOẠI ĐƠN (SUBTITLE) ====================
    fun translate(
        englishText: String,
        settings: TranslatorSettings,
        onSuccess: (translated: String, usedOffline: Boolean) -> Unit,
        onError: (message: String) -> Unit
    ) {
        when (settings.translationMode) {
            TranslationMode.OFFLINE -> {
                translateOffline(englishText, settings, onSuccess, onError)
            }
            TranslationMode.ONLINE -> {
                if (settings.apiKey.isEmpty()) {
                    onError("Chưa nhập Gemini API Key trong App!")
                    return
                }
                translateWithGemini(
                    englishText = englishText,
                    settings = settings,
                    allowThinkingConfig = settings.modelName.contains("2.5"),
                    allowOfflineFallback = false,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
            TranslationMode.HYBRID -> {
                if (settings.apiKey.isEmpty()) {
                    translateOffline(englishText, settings, onSuccess, onError)
                } else {
                    translateWithGemini(
                        englishText = englishText,
                        settings = settings,
                        allowThinkingConfig = settings.modelName.contains("2.5"),
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

        when (settings.translationMode) {
            TranslationMode.OFFLINE -> {
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            }
            TranslationMode.ONLINE -> {
                if (settings.apiKey.isEmpty()) {
                    onError("Chưa nhập Gemini API Key trong App!")
                    return
                }
                translateBatchWithGemini(
                    texts = texts,
                    settings = settings,
                    allowThinkingConfig = settings.modelName.contains("2.5"),
                    allowOfflineFallback = false,
                    onSuccess = onSuccess,
                    onError = onError
                )
            }
            TranslationMode.HYBRID -> {
                if (settings.apiKey.isEmpty()) {
                    translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                } else {
                    translateBatchWithGemini(
                        texts = texts,
                        settings = settings,
                        allowThinkingConfig = settings.modelName.contains("2.5"),
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
        allowThinkingConfig: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (List<String>, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelBatchRequest()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.modelName}:generateContent?key=${settings.apiKey}"

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
                            if (allowOfflineFallback) {
                                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc kết quả dịch toàn màn hình: ${e.message}")
                            }
                        }
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateBatchWithGemini(
                            texts = texts,
                            settings = settings,
                            allowThinkingConfig = false,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else if (allowOfflineFallback) {
                        translateBatchOfflineParallel(texts, settings, onSuccess) { offErr ->
                            onError("API ${it.code} • Offline: $offErr")
                        }
                    } else {
                        onError("Lỗi API ${it.code}: ${bodyStr.take(100)}")
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

    /**
     * Dịch toàn màn hình Offline: chạy song song tất cả các khối chữ cùng lúc (~80-150ms cho cả màn hình).
     */
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
                val (prepared, placeholders) = protectGlossary(original, terms)
                translator.translate(prepared)
                    .addOnSuccessListener { translated ->
                        results[idx] = restoreGlossary(translated.trim(), placeholders)
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

    private fun translateWithGemini(
        englishText: String,
        settings: TranslatorSettings,
        allowThinkingConfig: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        cancelActiveRequest()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.modelName}:generateContent?key=${settings.apiKey}"

        val glossaryTerms = settings.glossaryTerms()
        val glossaryRule = if (glossaryTerms.isNotEmpty()) {
            "\n- Danh sách tên riêng/thuật ngữ bắt buộc giữ nguyên: ${glossaryTerms.joinToString(", ")}."
        } else {
            ""
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
                            if (allowOfflineFallback) {
                                translateOffline(englishText, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc JSON: ${e.message}")
                            }
                        }
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateWithGemini(
                            englishText = englishText,
                            settings = settings,
                            allowThinkingConfig = false,
                            allowOfflineFallback = allowOfflineFallback,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    } else if (allowOfflineFallback) {
                        translateOffline(englishText, settings, onSuccess) { offlineErr ->
                            onError("API ${it.code} • Offline: $offlineErr")
                        }
                    } else {
                        onError("Lỗi API ${it.code}: ${bodyStr.take(100)}")
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

            val terms = settings.glossaryTerms().sortedByDescending { it.length }
            val (prepared, placeholders) = protectGlossary(englishText, terms)

            translator.translate(prepared)
                .addOnSuccessListener { rawTranslated ->
                    onSuccess(restoreGlossary(rawTranslated.trim(), placeholders), true)
                }
                .addOnFailureListener { e ->
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
