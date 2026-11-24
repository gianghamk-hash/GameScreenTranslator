package com.example.gamescreentranslator // Đổi lại cho khớp package của bạn

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

enum class GameStyle(val label: String, val instruction: String) {
    RPG_FANTASY("RPG / Giả tưởng", "Văn phong nhập vai giả tưởng (RPG/Fantasy), tự nhiên, hùng tráng hoặc bí ẩn tùy ngữ cảnh."),
    WUXIA("Kiếm hiệp / Cổ trang", "Văn phong kiếm hiệp, cổ trang (xưng hô ta - ngươi, huynh - đệ, tại hạ - các hạ phù hợp)."),
    MODERN("Hiện đại / Đời thường", "Văn phong giao tiếp hiện đại, tự nhiên, trẻ trung, gần gũi."),
    LITERAL_UI("Menu / Giao diện", "Dịch ngắn gọn, súc tích, chuẩn thuật ngữ giao diện và chỉ số game.")
}

class GeminiTranslator(
    private val apiKey: String
) {
    companion object {
        private const val MODEL_ID = "gemini-3.5-flash-lite"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:generateContent"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Dùng chung 1 OkHttpClient để giữ kết nối HTTP/2 Keep-Alive (giảm 200-400ms mỗi lần gọi)
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }

    var currentStyle: GameStyle = GameStyle.RPG_FANTASY

    // Từ điển cá nhân (VD: "Hyrule" to "Hyrule", "Shrine" to "Đền Thờ")
    val customGlossary = mutableMapOf<String, String>()

    // Cache 100 câu thoại gần nhất
    private val translationCache = LruCache<String, String>(100)

    // Lịch sử 5 câu thoại gần nhất để giữ ngữ cảnh & xưng hô
    private val contextWindow = ArrayDeque<Pair<String, String>>(6)

    // Lưu lịch sử để xem lại (Dialogue Log - tối đa 30 câu)
    val dialogueHistory = ArrayDeque<Pair<String, String>>(31)

    fun clearContext() {
        contextWindow.clear()
    }

    suspend fun translate(rawOcrText: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanedText = rawOcrText.trim().replace(Regex("\\s+"), " ")
        if (cleanedText.isBlank()) return@withContext Result.success("")

        // 1. Kiểm tra Cache chính xác hoặc giống >= 92% (tránh gọi API khi OCR nhiễu 1-2 ký tự)
        findInCacheFuzzy(cleanedText)?.let { cached ->
            return@withContext Result.success(cached)
        }

        try {
            val payload = buildRequestJson(cleanedText)
            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API Error ${response.code}: $bodyStr"))
                }

                val translated = parseResponseText(bodyStr)
                if (translated.isNotBlank()) {
                    // Lưu Cache, Context và Dialogue Log
                    translationCache.put(cleanedText, translated)
                    updateContext(cleanedText, translated)
                }
                Result.success(translated)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestJson(textToTranslate: String): JSONObject {
        val glossaryRule = if (customGlossary.isNotEmpty()) {
            val entries = customGlossary.entries.joinToString("; ") { "${it.key} -> ${it.value}" }
            "\n6. Bắt buộc tuân thủ từ điển riêng: $entries."
        } else ""

        val systemText = """
            Bạn là biên dịch viên chuyên nghiệp bản địa hóa game sang tiếng Việt.
            Quy tắc bắt buộc:
            1. ${currentStyle.instruction}
            2. Dịch thoát ý, tự nhiên theo đúng ngữ cảnh, TUYỆT ĐỐI KHÔNG dịch word-by-word.
            3. GIỮ NGUYÊN KHÔNG DỊCH: tên riêng nhân vật, tên địa danh, tên tổ chức, thuật ngữ game (HP, MP, EXP, ATK, DEF, Level...).
            4. Nếu văn bản OCR bị lỗi chính tả nhẹ do nhiễu nền game, hãy tự suy luận theo ngữ cảnh để dịch đúng.
            5. Giữ nhất quán đại từ nhân xưng dựa theo ngữ cảnh các câu thoại trước đó.$glossaryRule
            Chỉ trả về duy nhất bản dịch tiếng Việt, không giải thích, không thêm dấu ngoặc kép thừa.
        """.trimIndent()

        val userPrompt = buildString {
            if (contextWindow.isNotEmpty()) {
                append("Ngữ cảnh thoại trước đó:\n")
                contextWindow.forEach { (orig, trans) ->
                    append("- $orig => $trans\n")
                }
                append("\n")
            }
            append("Dịch đoạn thoại sau:\n$textToTranslate")
        }

        return JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 512)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", "minimal")
                })
            })
        }
    }

    private fun parseResponseText(jsonStr: String): String {
        val root = JSONObject(jsonStr)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun updateContext(original: String, translated: String) {
        if (contextWindow.size >= 5) contextWindow.removeFirst()
        contextWindow.addLast(original to translated)

        if (dialogueHistory.size >= 30) dialogueHistory.removeFirst()
        dialogueHistory.addLast(original to translated)
    }

    private fun findInCacheFuzzy(query: String): String? {
        translationCache.get(query)?.let { return it }

        val snapshot = translationCache.snapshot()
        for ((key, value) in snapshot) {
            if (similarity(query, key) >= 0.92) {
                return value
            }
        }
        return null
    }

    private fun similarity(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshtein(s1, s2)
        return (maxLen - dist).toDouble() / maxLen.toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
