package com.giang.gametranslator

import android.app.DownloadManager
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Động cơ dịch hợp nhất đa nhà cung cấp AI + đa ngôn ngữ (Anh / Nhật / Trung / Hàn → Việt):
 * - Tầng 1: AI chính (Gemini / Groq / OpenRouter / DeepSeek) xoay vòng nhiều Key.
 * - Tầng 2 (Dự phòng chéo chống 429): Gemini dính 429/lỗi → tự nhảy sang Groq/Alt AI (hoặc ngược lại).
 * - Tầng 3: Offline ML Kit + từ điển game 0ms (OfflineGameDictionary).
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

    private val nextKeyIndex = AtomicInteger(0)
    private val keyCooldownUntil = ConcurrentHashMap<String, Long>()
    private val cooldownDurationMs = 35_000L

    private val offlineTranslators = HashMap<SourceLanguage, Translator>()

    @Synchronized
    private fun getOrCreateOfflineTranslator(lang: SourceLanguage): Translator {
        return offlineTranslators[lang] ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang.mlKitCode)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        ).also { offlineTranslators[lang] = it }
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
        for (t in offlineTranslators.values) {
            try {
                t.close()
            } catch (_: Exception) {
            }
        }
        offlineTranslators.clear()
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

    private fun markKeyRateLimited(providerName: String, key: String, keyNumber: Int) {
        keyCooldownUntil[key] = System.currentTimeMillis() + cooldownDurationMs
        DiagnosticLogStore.warn(appContext, "$providerName Key #$keyNumber chạm 429 → tạm nghỉ 35s")
    }

    // ==================== 1. DỊCH CÂU THOẠI ĐƠN ====================
    fun translate(
        sourceText: String,
        settings: TranslatorSettings,
        onSuccess: (translated: String, usedOffline: Boolean) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val allowOffline = settings.translationMode == TranslationMode.HYBRID
        if (settings.translationMode == TranslationMode.OFFLINE) {
            OfflineGameDictionary.lookupExactPhrase(sourceText, settings)?.let { exact ->
                onSuccess(exact, true)
                return
            }
            translateOffline(sourceText, settings, onSuccess, onError)
            return
        }

        val geminiKeys = settings.apiKeys()
        val altKeys = settings.altApiKeys()

        if (settings.aiProvider == AiProvider.GEMINI) {
            if (geminiKeys.isNotEmpty()) {
                translateWithGemini(
                    sourceText = sourceText,
                    settings = settings,
                    keys = geminiKeys,
                    triedKeys = emptySet(),
                    allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    allowCrossFallback = altKeys.isNotEmpty(),
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (altKeys.isNotEmpty()) {
                translateWithOpenAiCompatible(
                    provider = AiProvider.GROQ,
                    sourceText = sourceText,
                    settings = settings,
                    keys = altKeys,
                    triedKeys = emptySet(),
                    allowCrossFallback = false,
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (allowOffline) {
                translateOffline(sourceText, settings, onSuccess, onError)
            } else {
                onError("Chưa nhập API Key AI trong App!")
            }
        } else {
            // Người dùng chọn Groq / OpenRouter / DeepSeek làm chính
            val primaryKeys = if (altKeys.isNotEmpty()) altKeys else geminiKeys
            if (primaryKeys.isNotEmpty()) {
                translateWithOpenAiCompatible(
                    provider = settings.aiProvider,
                    sourceText = sourceText,
                    settings = settings,
                    keys = primaryKeys,
                    triedKeys = emptySet(),
                    allowCrossFallback = (altKeys.isNotEmpty() && geminiKeys.isNotEmpty()),
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (allowOffline) {
                translateOffline(sourceText, settings, onSuccess, onError)
            } else {
                onError("Chưa nhập API Key cho ${settings.aiProvider.label}!")
            }
        }
    }

    // ==================== 2. DỊCH LÔ TOÀN MÀN HÌNH ====================
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

        val allowOffline = settings.translationMode == TranslationMode.HYBRID
        if (settings.translationMode == TranslationMode.OFFLINE) {
            translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            return
        }

        val geminiKeys = settings.apiKeys()
        val altKeys = settings.altApiKeys()

        if (settings.aiProvider == AiProvider.GEMINI) {
            if (geminiKeys.isNotEmpty()) {
                translateBatchWithGemini(
                    texts = texts,
                    settings = settings,
                    keys = geminiKeys,
                    triedKeys = emptySet(),
                    allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    allowCrossFallback = altKeys.isNotEmpty(),
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (altKeys.isNotEmpty()) {
                translateBatchWithOpenAiCompatible(
                    provider = AiProvider.GROQ,
                    texts = texts,
                    settings = settings,
                    keys = altKeys,
                    triedKeys = emptySet(),
                    allowCrossFallback = false,
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (allowOffline) {
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            } else {
                onError("Chưa nhập API Key AI trong App!")
            }
        } else {
            val primaryKeys = if (altKeys.isNotEmpty()) altKeys else geminiKeys
            if (primaryKeys.isNotEmpty()) {
                translateBatchWithOpenAiCompatible(
                    provider = settings.aiProvider,
                    texts = texts,
                    settings = settings,
                    keys = primaryKeys,
                    triedKeys = emptySet(),
                    allowCrossFallback = (altKeys.isNotEmpty() && geminiKeys.isNotEmpty()),
                    allowOfflineFallback = allowOffline,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (allowOffline) {
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            } else {
                onError("Chưa nhập API Key cho ${settings.aiProvider.label}!")
            }
        }
    }

    // ==================== PROMPT BUILDERS ====================
    private fun buildSinglePrompts(sourceText: String, settings: TranslatorSettings): Pair<String, String> {
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
            Bạn là chuyên gia Việt hóa phụ đề game thời gian thực từ ${settings.sourceLanguage.vietnameseName} sang tiếng Việt.
            Quy tắc bắt buộc:
            - ${settings.translationStyle.instruction}
            - GIỮ NGUYÊN 100% tên nhân vật, tên người, tên địa danh, vùng đất, tổ chức (tuyệt đối không dịch nghĩa đen tên người hay địa danh).$glossaryRule
            - Nếu có tên người nói ở đầu câu (vd "Cloud: ..."), giữ nguyên định dạng "Tên: ...".
            - Nếu input là danh sách nhiều lựa chọn đánh số (1. ...\n2. ...\n3. ...), BẮT BUỘC giữ nguyên cấu trúc xuống dòng và đánh số 1., 2., 3. tương ứng từng lựa chọn.
            - Dịch tự nhiên, súc tích, đúng ngữ cảnh game. Chỉ trả về DUY NHẤT bản dịch tiếng Việt, không ngoặc kép thừa, không giải thích.
        """.trimIndent()

        val userPrompt = buildString {
            if (settings.useContextMemory) {
                val snapshot = synchronized(historyLock) { recentHistory.toList() }
                if (snapshot.isNotEmpty()) {
                    append("Ngữ cảnh thoại gần nhất (để giữ xưng hô nhất quán):\n")
                    for ((src, vi) in snapshot) {
                        append("Gốc: ").append(src).append(" -> VI: ").append(vi).append('\n')
                    }
                    append('\n')
                }
            }
            append("Dịch câu thoại ${settings.sourceLanguage.vietnameseName} sau sang tiếng Việt:\n")
            append(sourceText)
        }
        return systemText to userPrompt
    }

    private fun buildBatchPrompts(texts: List<String>, settings: TranslatorSettings): Pair<String, String> {
        val glossaryTerms = settings.glossaryTerms()
        val customPairs = settings.customReplacements()
        val glossaryRule = buildString {
            if (glossaryTerms.isNotEmpty()) {
                append(" Giữ nguyên: ${glossaryTerms.joinToString(", ")}.")
            }
            if (customPairs.isNotEmpty()) {
                append(" Dịch cố định: ${customPairs.joinToString(", ") { "${it.first}->${it.second}" }}.")
            }
        }

        val systemText = """
            Bạn là trình dịch giao diện/menu/kho đồ game từ ${settings.sourceLanguage.vietnameseName} sang tiếng Việt siêu tốc.
            - Dịch cực ngắn gọn, đúng ngữ cảnh game, giữ nguyên tên riêng, địa danh và số liệu.$glossaryRule
            - Input gồm các dòng đánh số [0], [1], ...
            - BẮT BUỘC trả về đúng số lượng dòng theo đúng định dạng [0] bản dịch, [1] bản dịch... Không thêm lời giải thích.
        """.trimIndent()

        val userPrompt = buildString {
            texts.forEachIndexed { idx, s ->
                append('[').append(idx).append("] ").append(s).append('\n')
            }
        }
        return systemText to userPrompt
    }

    // ==================== OPENAI-COMPATIBLE (GROQ / OPENROUTER / DEEPSEEK) ====================
    private fun translateWithOpenAiCompatible(
        provider: AiProvider,
        sourceText: String,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowCrossFallback: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowCrossFallback && settings.apiKeys().isNotEmpty()) {
                DiagnosticLogStore.warn(appContext, "${provider.label} hết lượt 429 → tự chuyển dự phòng sang Gemini")
                translateWithGemini(
                    sourceText = sourceText,
                    settings = settings,
                    keys = settings.apiKeys(),
                    triedKeys = emptySet(),
                    allowThinkingConfig = (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    allowCrossFallback = false,
                    allowOfflineFallback = allowOfflineFallback,
                    onSuccess = onSuccess,
                    onError = onError
                )
            } else if (allowOfflineFallback) {
                translateOffline(sourceText, settings, onSuccess) {
                    onError("${provider.label} chạm 429 (chưa tải gói Offline dự phòng)")
                }
            } else {
                onError("${provider.label} chạm giới hạn 429, đang tạm nghỉ 35s...")
            }
            return
        }

        val (apiKey, keyNum) = picked
        cancelActiveRequest()

        val (systemText, userPrompt) = buildSinglePrompts(sourceText, settings)
        val model = settings.effectiveAltModel(provider)

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 256)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemText))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val reqBuilder = Request.Builder()
            .url(provider.endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (provider == AiProvider.OPENROUTER) {
            reqBuilder.header("HTTP-Referer", "https://github.com/gianghamk-hash/GameScreenTranslator")
            reqBuilder.header("X-Title", "GameScreenTranslator")
        }

        val call = httpClient.newCall(reqBuilder.build())
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                DiagnosticLogStore.warn(appContext, "Lỗi mạng ${provider.label}: ${e.message}")
                if (allowCrossFallback && settings.apiKeys().isNotEmpty()) {
                    translateWithGemini(
                        sourceText, settings, settings.apiKeys(), emptySet(),
                        (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                        false, allowOfflineFallback, onSuccess, onError
                    )
                } else if (allowOfflineFallback) {
                    translateOffline(sourceText, settings, onSuccess) { offErr ->
                        onError("Lỗi mạng (${e.message}) • Offline: $offErr")
                    }
                } else {
                    onError("Lỗi mạng ${provider.label}: ${e.message}")
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
                            val translated = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                                .trim()
                                .removeSurrounding("\"")

                            if (settings.useContextMemory && translated.isNotEmpty()) {
                                synchronized(historyLock) {
                                    if (recentHistory.size >= 4) recentHistory.removeFirst()
                                    recentHistory.addLast(sourceText to translated)
                                }
                            }
                            onSuccess(translated, false)
                        } catch (e: Exception) {
                            DiagnosticLogStore.error(appContext, "Lỗi JSON ${provider.label}: ${e.message}")
                            if (allowOfflineFallback) {
                                translateOffline(sourceText, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc phản hồi ${provider.label}: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited(provider.label, apiKey, keyNum)
                        translateWithOpenAiCompatible(
                            provider, sourceText, settings, keys, triedKeys + apiKey,
                            allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else {
                        DiagnosticLogStore.error(appContext, "${provider.label} HTTP ${it.code}: ${bodyStr.take(80)}")
                        if (allowCrossFallback && settings.apiKeys().isNotEmpty()) {
                            translateWithGemini(
                                sourceText, settings, settings.apiKeys(), emptySet(),
                                (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                                false, allowOfflineFallback, onSuccess, onError
                            )
                        } else if (allowOfflineFallback) {
                            translateOffline(sourceText, settings, onSuccess) { offErr ->
                                onError("${provider.label} ${it.code} • Offline: $offErr")
                            }
                        } else {
                            onError("Lỗi ${provider.label} ${it.code}: ${bodyStr.take(90)}")
                        }
                    }
                }
            }
        })
    }

    private fun translateBatchWithOpenAiCompatible(
        provider: AiProvider,
        texts: List<String>,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowCrossFallback: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (List<String>, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowCrossFallback && settings.apiKeys().isNotEmpty()) {
                translateBatchWithGemini(
                    texts, settings, settings.apiKeys(), emptySet(),
                    (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                    false, allowOfflineFallback, onSuccess, onError
                )
            } else if (allowOfflineFallback) {
                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
            } else {
                onError("${provider.label} chạm giới hạn 429, thử lại sau vài giây!")
            }
            return
        }

        val (apiKey, keyNum) = picked
        cancelBatchRequest()

        val (systemText, userPrompt) = buildBatchPrompts(texts, settings)
        val model = settings.effectiveAltModel(provider)

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemText))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val reqBuilder = Request.Builder()
            .url(provider.endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (provider == AiProvider.OPENROUTER) {
            reqBuilder.header("HTTP-Referer", "https://github.com/gianghamk-hash/GameScreenTranslator")
            reqBuilder.header("X-Title", "GameScreenTranslator")
        }

        val call = httpClient.newCall(reqBuilder.build())
        activeBatchCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                if (allowOfflineFallback) {
                    translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                } else {
                    onError("Lỗi mạng ${provider.label}: ${e.message}")
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
                            val rawOut = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                                .trim()
                            onSuccess(parseIndexedBatchOutput(rawOut, texts), false)
                        } catch (e: Exception) {
                            if (allowOfflineFallback) {
                                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc kết quả ${provider.label}: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited(provider.label, apiKey, keyNum)
                        translateBatchWithOpenAiCompatible(
                            provider, texts, settings, keys, triedKeys + apiKey,
                            allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else if (allowCrossFallback && settings.apiKeys().isNotEmpty()) {
                        translateBatchWithGemini(
                            texts, settings, settings.apiKeys(), emptySet(),
                            (settings.modelName.contains("3.") || settings.modelName.contains("2.5")),
                            false, allowOfflineFallback, onSuccess, onError
                        )
                    } else if (allowOfflineFallback) {
                        translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                    } else {
                        onError("Lỗi ${provider.label} ${it.code}: ${bodyStr.take(90)}")
                    }
                }
            }
        })
    }

    // ==================== GEMINI API ====================
    private fun translateBatchWithGemini(
        texts: List<String>,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowThinkingConfig: Boolean,
        allowCrossFallback: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (List<String>, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                DiagnosticLogStore.warn(appContext, "Gemini chạm 429 → tự chuyển dự phòng sang Groq")
                translateBatchWithOpenAiCompatible(
                    AiProvider.GROQ, texts, settings, settings.altApiKeys(), emptySet(),
                    false, allowOfflineFallback, onSuccess, onError
                )
            } else if (allowOfflineFallback) {
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
        val (systemText, userPrompt) = buildBatchPrompts(texts, settings)

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
                if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                    translateBatchWithOpenAiCompatible(
                        AiProvider.GROQ, texts, settings, settings.altApiKeys(), emptySet(),
                        false, allowOfflineFallback, onSuccess, onError
                    )
                } else if (allowOfflineFallback) {
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

                            onSuccess(parseIndexedBatchOutput(rawOut, texts), false)
                        } catch (e: Exception) {
                            DiagnosticLogStore.error(appContext, "Lỗi parse JSON toàn màn hình: ${e.message}")
                            if (allowOfflineFallback) {
                                translateBatchOfflineParallel(texts, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc kết quả dịch toàn màn hình: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited("Gemini", apiKey, keyNum)
                        translateBatchWithGemini(
                            texts, settings, keys, triedKeys + apiKey,
                            allowThinkingConfig, allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateBatchWithGemini(
                            texts, settings, keys, triedKeys,
                            false, allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else {
                        DiagnosticLogStore.error(appContext, "Batch API HTTP ${it.code} (Key #$keyNum)")
                        if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                            translateBatchWithOpenAiCompatible(
                                AiProvider.GROQ, texts, settings, settings.altApiKeys(), emptySet(),
                                false, allowOfflineFallback, onSuccess, onError
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
        val lang = settings.sourceLanguage
        val translator = getOrCreateOfflineTranslator(lang)
        isOfflineModelDownloaded(lang) { downloaded ->
            if (!downloaded) {
                onError("Chưa tải gói dịch Offline (${lang.shortLabel} → Việt) trong màn hình chính!")
                return@isOfflineModelDownloaded
            }

            val results = Array(texts.size) { texts[it] }
            val remaining = AtomicInteger(texts.size)

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
        sourceText: String,
        settings: TranslatorSettings,
        keys: List<String>,
        triedKeys: Set<String>,
        allowThinkingConfig: Boolean,
        allowCrossFallback: Boolean,
        allowOfflineFallback: Boolean,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val picked = pickAvailableKey(keys, triedKeys)
        if (picked == null) {
            if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                DiagnosticLogStore.warn(appContext, "Gemini chạm 429 → tự chuyển dự phòng sang Groq AI")
                translateWithOpenAiCompatible(
                    AiProvider.GROQ, sourceText, settings, settings.altApiKeys(), emptySet(),
                    false, allowOfflineFallback, onSuccess, onError
                )
            } else if (allowOfflineFallback) {
                translateOffline(sourceText, settings, onSuccess) {
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
        val (systemText, userPrompt) = buildSinglePrompts(sourceText, settings)

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
                if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                    translateWithOpenAiCompatible(
                        AiProvider.GROQ, sourceText, settings, settings.altApiKeys(), emptySet(),
                        false, allowOfflineFallback, onSuccess, onError
                    )
                } else if (allowOfflineFallback) {
                    translateOffline(sourceText, settings, onSuccess) { offlineErr ->
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
                                    recentHistory.addLast(sourceText to translated)
                                }
                            }
                            onSuccess(translated, false)
                        } catch (e: Exception) {
                            DiagnosticLogStore.error(appContext, "Lỗi parse JSON Gemini: ${e.message}")
                            if (allowOfflineFallback) {
                                translateOffline(sourceText, settings, onSuccess, onError)
                            } else {
                                onError("Lỗi đọc JSON: ${e.message}")
                            }
                        }
                    } else if (it.code == 429) {
                        markKeyRateLimited("Gemini", apiKey, keyNum)
                        translateWithGemini(
                            sourceText, settings, keys, triedKeys + apiKey,
                            allowThinkingConfig, allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else if (it.code == 400 && allowThinkingConfig) {
                        translateWithGemini(
                            sourceText, settings, keys, triedKeys,
                            false, allowCrossFallback, allowOfflineFallback, onSuccess, onError
                        )
                    } else {
                        DiagnosticLogStore.error(appContext, "Gemini HTTP ${it.code} (Key #$keyNum): ${bodyStr.take(80)}")
                        if (allowCrossFallback && settings.altApiKeys().isNotEmpty()) {
                            translateWithOpenAiCompatible(
                                AiProvider.GROQ, sourceText, settings, settings.altApiKeys(), emptySet(),
                                false, allowOfflineFallback, onSuccess, onError
                            )
                        } else if (allowOfflineFallback) {
                            translateOffline(sourceText, settings, onSuccess) { offlineErr ->
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
        sourceText: String,
        settings: TranslatorSettings,
        onSuccess: (String, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val lang = settings.sourceLanguage
        val translator = getOrCreateOfflineTranslator(lang)
        isOfflineModelDownloaded(lang) { downloaded ->
            if (!downloaded) {
                onError("Chưa tải gói dịch Offline (${lang.shortLabel} → Việt) trong màn hình chính!")
                return@isOfflineModelDownloaded
            }

            val choiceRegex = Regex("^(\\d+\\.)\\s+(.+)$")
            val rawLines = sourceText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.size in 2..5 && rawLines.all { choiceRegex.matches(it) }) {
                val results = Array(rawLines.size) { rawLines[it] }
                val remaining = AtomicInteger(rawLines.size)
                rawLines.forEachIndexed { idx, line ->
                    val match = choiceRegex.find(line)!!
                    val prefix = match.groupValues[1]
                    val body = match.groupValues[2].trim()
                    val exact = OfflineGameDictionary.lookupExactPhrase(body, settings)
                    if (exact != null) {
                        results[idx] = "$prefix $exact"
                        if (remaining.decrementAndGet() == 0) {
                            onSuccess(results.joinToString("\n"), true)
                        }
                    } else {
                        val preparedLine = OfflineGameDictionary.prepareForOffline(body, settings)
                        translator.translate(preparedLine.textForMlKit)
                            .addOnSuccessListener { rawTranslated ->
                                val vi = OfflineGameDictionary.postProcessOffline(
                                    rawTranslated,
                                    preparedLine,
                                    settings.translationStyle
                                )
                                results[idx] = "$prefix $vi"
                                if (remaining.decrementAndGet() == 0) {
                                    onSuccess(results.joinToString("\n"), true)
                                }
                            }
                            .addOnFailureListener {
                                if (remaining.decrementAndGet() == 0) {
                                    onSuccess(results.joinToString("\n"), true)
                                }
                            }
                    }
                }
                return@isOfflineModelDownloaded
            }

            OfflineGameDictionary.lookupExactPhrase(sourceText, settings)?.let { exact ->
                onSuccess(exact, true)
                return@isOfflineModelDownloaded
            }
            val prepared = OfflineGameDictionary.prepareForOffline(sourceText, settings)

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
                    DiagnosticLogStore.error(appContext, "Lỗi ML Kit Offline (${lang.shortLabel}): ${e.message}")
                    onError("Lỗi dịch Offline: ${e.message}")
                }
        }
    }

    companion object {
        private fun requiredRemoteModelCodes(lang: SourceLanguage): List<String> {
            return if (lang == SourceLanguage.ENGLISH) {
                listOf(TranslateLanguage.VIETNAMESE)
            } else {
                listOf(lang.mlKitCode, TranslateLanguage.VIETNAMESE)
            }
        }

        fun isOfflineModelDownloaded(lang: SourceLanguage, callback: (Boolean) -> Unit) {
            val modelManager = RemoteModelManager.getInstance()
            val codes = requiredRemoteModelCodes(lang)
            val remaining = AtomicInteger(codes.size)
            val allReady = AtomicBoolean(true)

            for (code in codes) {
                val model = TranslateRemoteModel.Builder(code).build()
                modelManager.isModelDownloaded(model)
                    .addOnSuccessListener { downloaded ->
                        if (!downloaded) allReady.set(false)
                        if (remaining.decrementAndGet() == 0) {
                            callback(allReady.get())
                        }
                    }
                    .addOnFailureListener {
                        allReady.set(false)
                        if (remaining.decrementAndGet() == 0) {
                            callback(false)
                        }
                    }
            }
        }

        fun downloadOfflineModelWithProgress(
            context: Context,
            lang: SourceLanguage,
            onProgress: (percent: Int, indeterminate: Boolean, statusText: String) -> Unit,
            onSuccess: () -> Unit,
            onError: (String) -> Unit
        ): () -> Unit {
            val appCtx = context.applicationContext
            val modelManager = RemoteModelManager.getInstance()
            val codes = requiredRemoteModelCodes(lang)
            val conditions = DownloadConditions.Builder().build()

            val mainHandler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            val startTimeMs = System.currentTimeMillis()
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

            val remaining = AtomicInteger(codes.size)
            val failedMessage = StringBuilder()
            var pollTick = 0

            val pollRunnable = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    pollTick++
                    val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).toInt()

                    var bytesSoFar = 0L
                    var totalBytes = 0L
                    var hasActiveEntry = false
                    var pausedReason = false

                    try {
                        val query = DownloadManager.Query().setFilterByStatus(
                            DownloadManager.STATUS_RUNNING or
                                DownloadManager.STATUS_PENDING or
                                DownloadManager.STATUS_PAUSED
                        )
                        dm?.query(query)?.use { cursor ->
                            val idxSoFar = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val idxTotal = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val idxStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            while (cursor.moveToNext()) {
                                hasActiveEntry = true
                                val st = if (idxStatus >= 0) cursor.getInt(idxStatus) else 0
                                if (st == DownloadManager.STATUS_PAUSED) pausedReason = true
                                val dl = if (idxSoFar >= 0) cursor.getLong(idxSoFar) else 0L
                                val tot = if (idxTotal >= 0) cursor.getLong(idxTotal) else 0L
                                if (dl > 0) bytesSoFar += dl
                                if (tot > 0) totalBytes += tot
                            }
                        }
                    } catch (_: Exception) {
                    }

                    if (totalBytes > 0L) {
                        val pct = ((bytesSoFar * 100L) / totalBytes).toInt().coerceIn(1, 99)
                        val dlMb = String.format(Locale.US, "%.1f", bytesSoFar / (1024f * 1024f))
                        val totMb = String.format(Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                        val state = if (pausedReason) "Đang chờ mạng" else "Đang tải"
                        onProgress(pct, false, "$state $pct% ($dlMb / $totMb MB)")
                    } else if (hasActiveEntry) {
                        onProgress(0, true, "Đang kết nối & chờ DownloadManager... (${elapsedSec}s)")
                    } else {
                        onProgress(0, true, "Đang chuẩn bị / giải nén gói ${lang.shortLabel} → Việt... (${elapsedSec}s)")
                    }

                    if (pollTick % 4 == 0) {
                        isOfflineModelDownloaded(lang) { ready ->
                            if (ready && finished.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onProgress(100, false, "Hoàn tất 100%!")
                                    onSuccess()
                                }
                            }
                        }
                    }

                    if (!finished.get()) {
                        mainHandler.postDelayed(this, 500L)
                    }
                }
            }

            mainHandler.post(pollRunnable)

            for (code in codes) {
                val model = TranslateRemoteModel.Builder(code).build()
                modelManager.download(model, conditions)
                    .addOnSuccessListener {
                        if (remaining.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(pollRunnable)
                            mainHandler.post {
                                onProgress(100, false, "Hoàn tất 100%!")
                                onSuccess()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        synchronized(failedMessage) {
                            if (failedMessage.isEmpty()) {
                                failedMessage.append(e.message ?: "Không thể tải gói ngôn ngữ")
                            }
                        }
                        if (finished.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(pollRunnable)
                            mainHandler.post {
                                onError(failedMessage.toString())
                            }
                        }
                    }
            }

            return {
                finished.set(true)
                mainHandler.removeCallbacks(pollRunnable)
            }
        }

        fun deleteOfflineModel(lang: SourceLanguage, onDone: (Boolean) -> Unit) {
            val modelManager = RemoteModelManager.getInstance()
            val codeToDelete = if (lang == SourceLanguage.ENGLISH) TranslateLanguage.VIETNAMESE else lang.mlKitCode
            val model = TranslateRemoteModel.Builder(codeToDelete).build()
            modelManager.deleteDownloadedModel(model)
                .addOnSuccessListener { onDone(true) }
                .addOnFailureListener { onDone(false) }
        }
    }
}
