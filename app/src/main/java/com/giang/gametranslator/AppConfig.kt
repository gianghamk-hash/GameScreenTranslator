package com.giang.gametranslator

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import com.google.mlkit.nl.translate.TranslateLanguage

enum class AiProvider(
    val label: String,
    val endpointUrl: String,
    val defaultModel: String
) {
    GEMINI("Gemini", "", "gemini-3.5-flash-lite"),
    GROQ("Groq (Siêu nhanh)", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "google/gemini-2.0-flash-exp:free"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat");

    companion object {
        fun fromName(name: String?): AiProvider =
            entries.firstOrNull { it.name == name } ?: GEMINI
    }
}

enum class SourceLanguage(
    val shortLabel: String,
    val vietnameseName: String,
    val mlKitCode: String
) {
    ENGLISH("Anh", "tiếng Anh", TranslateLanguage.ENGLISH),
    JAPANESE("Nhật", "tiếng Nhật", TranslateLanguage.JAPANESE),
    CHINESE("Trung", "tiếng Trung", TranslateLanguage.CHINESE),
    KOREAN("Hàn", "tiếng Hàn", TranslateLanguage.KOREAN);

    companion object {
        fun fromName(name: String?): SourceLanguage =
            entries.firstOrNull { it.name == name } ?: ENGLISH
    }
}

enum class TranslationMode(val label: String) {
    HYBRID("Tự động (AI + Offline)"),
    ONLINE("Chỉ AI"),
    OFFLINE("Chỉ Offline");

    companion object {
        fun fromName(name: String?): TranslationMode =
            entries.firstOrNull { it.name == name } ?: HYBRID
    }
}

enum class TranslationStyle(val label: String, val instruction: String) {
    RPG_FANTASY(
        "RPG / Giả tưởng",
        "Văn phong game nhập vai giả tưởng/kiếm hiệp: giàu khí chất, tự nhiên, xưng hô linh hoạt theo vai vế (ta/ngươi, ngài/tôi, huynh/đệ khi phù hợp)."
    ),
    ACTION_MODERN(
        "Hành động / Hiện đại",
        "Văn phong game hành động/hiện đại/sci-fi: ngắn gọn, mạnh mẽ, đời thường, dứt khoát (tôi/anh/cậu/mày-tao tùy sắc thái)."
    ),
    VISUAL_NOVEL(
        "Visual Novel",
        "Văn phong tiểu thuyết tương tác/cốt truyện: giàu cảm xúc, mượt mà, tự nhiên như phụ đề phim."
    ),
    LITERAL_UI(
        "Sát nghĩa / Menu",
        "Văn phong gọn gàng, chính xác, sát nghĩa gốc cho giao diện, mô tả kỹ năng, nhiệm vụ và vật phẩm."
    );

    companion object {
        fun fromName(name: String?): TranslationStyle =
            entries.firstOrNull { it.name == name } ?: RPG_FANTASY
    }
}

enum class SubtitlePositionMode(val label: String) {
    ABOVE_CROP("Sát trên Eng-Sub (0% nháy)"),
    COVER_CROP("Đè kín Eng-Sub"),
    BOTTOM_FLOATING("Nổi phía dưới (0% nháy)");

    companion object {
        fun fromName(name: String?, legacyCoverEngSub: Boolean = false): SubtitlePositionMode {
            entries.firstOrNull { it.name == name }?.let { return it }
            return if (legacyCoverEngSub) COVER_CROP else ABOVE_CROP
        }
    }
}

enum class SubtitleColor(val colorInt: Int) {
    WHITE(Color.WHITE),
    YELLOW(Color.parseColor("#FFFDE047")),
    CYAN(Color.parseColor("#FF00D4FF")),
    GREEN(Color.parseColor("#FF4ADE80"));

    companion object {
        fun fromName(name: String?): SubtitleColor =
            entries.firstOrNull { it.name == name } ?: WHITE
    }
}

enum class GamepadHotkey(val keyCode: Int, val label: String) {
    NONE(0, "Tắt"),
    R3(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3"),
    L3(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3"),
    SELECT(KeyEvent.KEYCODE_BUTTON_SELECT, "Select");

    companion object {
        fun fromName(name: String?, default: GamepadHotkey): GamepadHotkey =
            entries.firstOrNull { it.name == name } ?: default
    }
}

data class TranslatorSettings(
    val aiProvider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val altApiKey: String = "",
    val modelName: String = "gemini-3.5-flash-lite",
    val altModelName: String = "",
    val sourceLanguage: SourceLanguage = SourceLanguage.ENGLISH,
    val translationMode: TranslationMode = TranslationMode.HYBRID,
    val translationStyle: TranslationStyle = TranslationStyle.RPG_FANTASY,
    val customGlossary: String = "",
    val subtitlePositionMode: SubtitlePositionMode = SubtitlePositionMode.ABOVE_CROP,
    val cinemaOutlineMode: Boolean = false,
    val choiceFormatter: Boolean = true,
    val lockBubblePosition: Boolean = false,
    val autoFitTextSize: Boolean = true,
    val autoHideSeconds: Int = 0,
    val smartColorMatch: Boolean = true,
    val manualTriggerOnly: Boolean = false,
    val autoFadeBubble: Boolean = true,
    val ttsEnabled: Boolean = false,
    val ttsVoiceName: String = "",
    val ttsSpeechRate: Float = 1.15f,
    val hotkeyTranslate: GamepadHotkey = GamepadHotkey.R3,
    val hotkeyFullScreen: GamepadHotkey = GamepadHotkey.L3,
    val activeProfileName: String = "Mặc định",
    val antiDuplicate: Boolean = true,
    val useContextMemory: Boolean = true,
    val scanIntervalMs: Int = 500,
    val subtitleFontSizeSp: Float = 16f,
    val subtitleBgAlphaPct: Int = 95,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val cropLeftPct: Float = 0.08f,
    val cropTopPct: Float = 0.72f,
    val cropRightPct: Float = 0.92f,
    val cropBottomPct: Float = 0.95f
) {
    val coverEngSub: Boolean
        get() = subtitlePositionMode == SubtitlePositionMode.COVER_CROP

    fun apiKeys(): List<String> = splitKeys(apiKey)

    fun altApiKeys(): List<String> = splitKeys(altApiKey)

    fun hasAnyAiKey(): Boolean = apiKeys().isNotEmpty() || altApiKeys().isNotEmpty()

    fun effectiveAltModel(provider: AiProvider): String =
        altModelName.trim().ifEmpty { provider.defaultModel }

    private fun splitKeys(raw: String): List<String> =
        raw.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun bgAlphaInt(): Int =
        ((subtitleBgAlphaPct.coerceIn(40, 100) / 100f) * 255f).toInt().coerceIn(100, 255)

    fun bgColorInt(): Int = Color.argb(bgAlphaInt(), 11, 15, 23)

    fun glossaryTerms(): List<String> =
        customGlossary
            .split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("=") }
            .distinct()

    fun customReplacements(): List<Pair<String, String>> =
        customGlossary
            .split(",", "\n", ";")
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.length - 1) return@mapNotNull null
                val src = entry.substring(0, idx).trim()
                val dst = entry.substring(idx + 1).trim()
                if (src.isEmpty() || dst.isEmpty()) null else (src to dst)
            }
}

object SettingsStore {
    private const val PREF_NAME = "AppPrefs"

    const val ACTION_RELOAD_SETTINGS = "com.giang.gametranslator.ACTION_RELOAD_SETTINGS"
    const val ACTION_MANUAL_SCAN = "com.giang.gametranslator.ACTION_MANUAL_SCAN"
    const val ACTION_FULLSCREEN_TRANSLATE = "com.giang.gametranslator.ACTION_FULLSCREEN_TRANSLATE"

    fun load(context: Context): TranslatorSettings {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedPosName = p.getString("SUBTITLE_POSITION_MODE", null)
        val legacyCover = p.getBoolean("COVER_ENG_SUB", false)
        return TranslatorSettings(
            aiProvider = AiProvider.fromName(p.getString("AI_PROVIDER", null)),
            apiKey = p.getString("API_KEY", "")?.trim() ?: "",
            altApiKey = p.getString("ALT_API_KEY", "")?.trim() ?: "",
            modelName = p.getString("MODEL_NAME", "gemini-3.5-flash-lite")
                ?.trim()
                ?.let { if (it.isEmpty() || it == "gemini-2.5-flash-lite") "gemini-3.5-flash-lite" else it }
                ?: "gemini-3.5-flash-lite",
            altModelName = p.getString("ALT_MODEL_NAME", "")?.trim() ?: "",
            sourceLanguage = SourceLanguage.fromName(p.getString("SOURCE_LANGUAGE", null)),
            translationMode = TranslationMode.fromName(p.getString("TRANSLATION_MODE", null)),
            translationStyle = TranslationStyle.fromName(p.getString("TRANSLATION_STYLE", null)),
            customGlossary = p.getString("CUSTOM_GLOSSARY", "") ?: "",
            subtitlePositionMode = SubtitlePositionMode.fromName(savedPosName, legacyCover),
            cinemaOutlineMode = p.getBoolean("CINEMA_OUTLINE_MODE", false),
            choiceFormatter = p.getBoolean("CHOICE_FORMATTER", true),
            lockBubblePosition = p.getBoolean("LOCK_BUBBLE_POSITION", false),
            autoFitTextSize = p.getBoolean("AUTO_FIT_TEXT_SIZE", true),
            autoHideSeconds = p.getInt("AUTO_HIDE_SECONDS", 0).coerceIn(0, 15),
            smartColorMatch = p.getBoolean("SMART_COLOR_MATCH", true),
            manualTriggerOnly = p.getBoolean("MANUAL_TRIGGER_ONLY", false),
            autoFadeBubble = p.getBoolean("AUTO_FADE_BUBBLE", true),
            ttsEnabled = p.getBoolean("TTS_ENABLED", false),
            ttsVoiceName = p.getString("TTS_VOICE_NAME", "") ?: "",
            ttsSpeechRate = p.getFloat("TTS_SPEECH_RATE", 1.15f).coerceIn(0.8f, 1.8f),
            hotkeyTranslate = GamepadHotkey.fromName(p.getString("HOTKEY_TRANSLATE", null), GamepadHotkey.R3),
            hotkeyFullScreen = GamepadHotkey.fromName(p.getString("HOTKEY_FULLSCREEN", null), GamepadHotkey.L3),
            activeProfileName = p.getString("ACTIVE_PROFILE_NAME", "Mặc định") ?: "Mặc định",
            antiDuplicate = p.getBoolean("ANTI_DUPLICATE", true),
            useContextMemory = p.getBoolean("USE_CONTEXT_MEMORY", true),
            scanIntervalMs = p.getInt("SCAN_INTERVAL_MS", 500).coerceIn(300, 1500),
            subtitleFontSizeSp = p.getFloat("SUBTITLE_FONT_SIZE", 16f).coerceIn(12f, 28f),
            subtitleBgAlphaPct = p.getInt("SUBTITLE_BG_ALPHA_PCT", 95).coerceIn(40, 100),
            subtitleColor = SubtitleColor.fromName(p.getString("SUBTITLE_COLOR", null)),
            cropLeftPct = p.getFloat("CROP_LEFT", 0.08f).coerceIn(0f, 0.95f),
            cropTopPct = p.getFloat("CROP_TOP", 0.72f).coerceIn(0f, 0.95f),
            cropRightPct = p.getFloat("CROP_RIGHT", 0.92f).coerceIn(0.05f, 1f),
            cropBottomPct = p.getFloat("CROP_BOTTOM", 0.95f).coerceIn(0.05f, 1f)
        )
    }

    fun save(context: Context, s: TranslatorSettings) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString("AI_PROVIDER", s.aiProvider.name)
            .putString("API_KEY", s.apiKey.trim())
            .putString("ALT_API_KEY", s.altApiKey.trim())
            .putString("MODEL_NAME", s.modelName.trim().ifEmpty { "gemini-3.5-flash-lite" })
            .putString("ALT_MODEL_NAME", s.altModelName.trim())
            .putString("SOURCE_LANGUAGE", s.sourceLanguage.name)
            .putString("TRANSLATION_MODE", s.translationMode.name)
            .putString("TRANSLATION_STYLE", s.translationStyle.name)
            .putString("CUSTOM_GLOSSARY", s.customGlossary.trim())
            .putString("SUBTITLE_POSITION_MODE", s.subtitlePositionMode.name)
            .putBoolean("COVER_ENG_SUB", s.coverEngSub)
            .putBoolean("CINEMA_OUTLINE_MODE", s.cinemaOutlineMode)
            .putBoolean("CHOICE_FORMATTER", s.choiceFormatter)
            .putBoolean("LOCK_BUBBLE_POSITION", s.lockBubblePosition)
            .putBoolean("AUTO_FIT_TEXT_SIZE", s.autoFitTextSize)
            .putInt("AUTO_HIDE_SECONDS", s.autoHideSeconds.coerceIn(0, 15))
            .putBoolean("SMART_COLOR_MATCH", s.smartColorMatch)
            .putBoolean("MANUAL_TRIGGER_ONLY", s.manualTriggerOnly)
            .putBoolean("AUTO_FADE_BUBBLE", s.autoFadeBubble)
            .putBoolean("TTS_ENABLED", s.ttsEnabled)
            .putString("TTS_VOICE_NAME", s.ttsVoiceName)
            .putFloat("TTS_SPEECH_RATE", s.ttsSpeechRate.coerceIn(0.8f, 1.8f))
            .putString("HOTKEY_TRANSLATE", s.hotkeyTranslate.name)
            .putString("HOTKEY_FULLSCREEN", s.hotkeyFullScreen.name)
            .putString("ACTIVE_PROFILE_NAME", s.activeProfileName)
            .putBoolean("ANTI_DUPLICATE", s.antiDuplicate)
            .putBoolean("USE_CONTEXT_MEMORY", s.useContextMemory)
            .putInt("SCAN_INTERVAL_MS", s.scanIntervalMs.coerceIn(300, 1500))
            .putFloat("SUBTITLE_FONT_SIZE", s.subtitleFontSizeSp.coerceIn(12f, 28f))
            .putInt("SUBTITLE_BG_ALPHA_PCT", s.subtitleBgAlphaPct.coerceIn(40, 100))
            .putString("SUBTITLE_COLOR", s.subtitleColor.name)
            .putFloat("CROP_LEFT", s.cropLeftPct.coerceIn(0f, 0.95f))
            .putFloat("CROP_TOP", s.cropTopPct.coerceIn(0f, 0.95f))
            .putFloat("CROP_RIGHT", s.cropRightPct.coerceIn(0.05f, 1f))
            .putFloat("CROP_BOTTOM", s.cropBottomPct.coerceIn(0.05f, 1f))
            .apply()
    }

    fun saveCropRegion(context: Context, left: Float, top: Float, right: Float, bottom: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("CROP_LEFT", left.coerceIn(0f, 0.95f))
            .putFloat("CROP_TOP", top.coerceIn(0f, 0.95f))
            .putFloat("CROP_RIGHT", right.coerceIn(0.05f, 1f))
            .putFloat("CROP_BOTTOM", bottom.coerceIn(0.05f, 1f))
            .apply()
    }
}
