package com.giang.gametranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Bộ đọc phụ đề tiếng Việt Offline (Android TextToSpeech).
 * - Độ trễ ~0ms, miễn phí 100%, chạy mượt khi chơi Offline.
 * - Hỗ trợ liệt kê & chọn các giọng tiếng Việt (Nam/Nữ) có sẵn trên máy.
 * - Dùng QUEUE_FLUSH để tự ngắt câu cũ ngay khi sang câu thoại mới.
 */
class SubtitleSpeaker(
    context: Context,
    private val onReady: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    var isReady: Boolean = false
        private set

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {
            isReady = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val viLocale = Locale("vi", "VN")
            val res = tts?.setLanguage(viLocale)
            isReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isReady) {
                // Fallback thử locale "vi"
                val fallback = tts?.setLanguage(Locale("vi"))
                isReady = fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED
            }
            onReady?.invoke()
        } else {
            isReady = false
        }
    }

    fun getVietnameseVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        if (!isReady) return emptyList()
        return try {
            engine.voices
                ?.filter { it.locale.language.equals("vi", ignoreCase = true) }
                ?.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun speak(text: String, voiceName: String, speechRate: Float) {
        val engine = tts ?: return
        if (!isReady) return
        val clean = text
            .replace("(Offline)", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty() || clean.startsWith("Đang dịch") || clean.startsWith("Lỗi ")) return

        try {
            if (voiceName.isNotEmpty()) {
                val match = engine.voices?.firstOrNull { it.name == voiceName }
                if (match != null) {
                    engine.voice = match
                }
            }
            engine.setSpeechRate(speechRate.coerceIn(0.7f, 2.0f))
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "sub_${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        isReady = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    companion object {
        fun friendlyVoiceLabel(voice: Voice, index: Int): String {
            val mode = if (voice.isNetworkConnectionRequired) "Online" else "Offline"
            val shortCode = voice.name.substringAfterLast("-").uppercase(Locale.ROOT)
            return "Giọng Việt #${index + 1} ($shortCode • $mode)"
        }
    }
}
