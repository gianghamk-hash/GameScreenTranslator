package com.giang.gametranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sao lưu & Khôi phục toàn bộ cấu hình (API Keys, Game Profiles, từ điển tên riêng, giao diện, TTS, phím tay cầm)
 * dưới dạng file .json chuẩn để đồng bộ nhanh giữa nhiều máy Android (điện thoại / handheld).
 */
object ConfigBackupManager {

    data class ImportResult(
        val settings: TranslatorSettings,
        val profileCount: Int
    )

    fun defaultBackupFilename(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "GameScreenTranslator_Backup_$stamp.json"
    }

    fun exportToJson(context: Context, s: TranslatorSettings): String {
        val settingsObj = JSONObject().apply {
            put("aiProvider", s.aiProvider.name)
            put("apiKey", s.apiKey)
            put("altApiKey", s.altApiKey)
            put("modelName", s.modelName)
            put("altModelName", s.altModelName)
            put("sourceLanguage", s.sourceLanguage.name)
            put("translationMode", s.translationMode.name)
            put("translationStyle", s.translationStyle.name)
            put("customGlossary", s.customGlossary)
            put("subtitlePositionMode", s.subtitlePositionMode.name)
            put("cinemaOutlineMode", s.cinemaOutlineMode)
            put("choiceFormatter", s.choiceFormatter)
            put("lockBubblePosition", s.lockBubblePosition)
            put("autoFitTextSize", s.autoFitTextSize)
            put("autoHideSeconds", s.autoHideSeconds)
            put("smartColorMatch", s.smartColorMatch)
            put("manualTriggerOnly", s.manualTriggerOnly)
            put("autoFadeBubble", s.autoFadeBubble)
            put("ttsEnabled", s.ttsEnabled)
            put("ttsVoiceName", s.ttsVoiceName)
            put("ttsSpeechRate", s.ttsSpeechRate.toDouble())
            put("hotkeyTranslate", s.hotkeyTranslate.name)
            put("hotkeyFullScreen", s.hotkeyFullScreen.name)
            put("activeProfileName", s.activeProfileName)
            put("antiDuplicate", s.antiDuplicate)
            put("useContextMemory", s.useContextMemory)
            put("scanIntervalMs", s.scanIntervalMs)
            put("subtitleFontSizeSp", s.subtitleFontSizeSp.toDouble())
            put("subtitleBgAlphaPct", s.subtitleBgAlphaPct)
            put("subtitleColor", s.subtitleColor.name)
            put("cropLeftPct", s.cropLeftPct.toDouble())
            put("cropTopPct", s.cropTopPct.toDouble())
            put("cropRightPct", s.cropRightPct.toDouble())
            put("cropBottomPct", s.cropBottomPct.toDouble())
        }

        val root = JSONObject().apply {
            put("app", "GameScreenTranslator")
            put("schemaVersion", 1)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsObj)
            put("profiles", GameProfileStore.exportJsonArray(context))
        }
        return root.toString(2)
    }

    fun importFromJson(context: Context, rawJson: String): ImportResult {
        val root = JSONObject(rawJson)
        val o = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("File JSON không phải bản sao lưu hợp lệ của GameScreenTranslator")

        val base = SettingsStore.load(context)
        val restored = base.copy(
            aiProvider = AiProvider.fromName(o.optString("aiProvider", base.aiProvider.name)),
            apiKey = o.optString("apiKey", base.apiKey).trim(),
            altApiKey = o.optString("altApiKey", base.altApiKey).trim(),
            modelName = o.optString("modelName", base.modelName).trim().ifEmpty { "gemini-3.5-flash-lite" },
            altModelName = o.optString("altModelName", base.altModelName).trim(),
            sourceLanguage = SourceLanguage.fromName(o.optString("sourceLanguage", base.sourceLanguage.name)),
            translationMode = TranslationMode.fromName(o.optString("translationMode", base.translationMode.name)),
            translationStyle = TranslationStyle.fromName(o.optString("translationStyle", base.translationStyle.name)),
            customGlossary = o.optString("customGlossary", base.customGlossary),
            subtitlePositionMode = SubtitlePositionMode.fromName(
                o.optString("subtitlePositionMode", base.subtitlePositionMode.name),
                false
            ),
            cinemaOutlineMode = o.optBoolean("cinemaOutlineMode", base.cinemaOutlineMode),
            choiceFormatter = o.optBoolean("choiceFormatter", base.choiceFormatter),
            lockBubblePosition = o.optBoolean("lockBubblePosition", base.lockBubblePosition),
            autoFitTextSize = o.optBoolean("autoFitTextSize", base.autoFitTextSize),
            autoHideSeconds = o.optInt("autoHideSeconds", base.autoHideSeconds).coerceIn(0, 15),
            smartColorMatch = o.optBoolean("smartColorMatch", base.smartColorMatch),
            manualTriggerOnly = o.optBoolean("manualTriggerOnly", base.manualTriggerOnly),
            autoFadeBubble = o.optBoolean("autoFadeBubble", base.autoFadeBubble),
            ttsEnabled = o.optBoolean("ttsEnabled", base.ttsEnabled),
            ttsVoiceName = o.optString("ttsVoiceName", base.ttsVoiceName),
            ttsSpeechRate = o.optDouble("ttsSpeechRate", base.ttsSpeechRate.toDouble()).toFloat().coerceIn(0.8f, 1.8f),
            hotkeyTranslate = GamepadHotkey.fromName(o.optString("hotkeyTranslate", base.hotkeyTranslate.name), GamepadHotkey.R3),
            hotkeyFullScreen = GamepadHotkey.fromName(o.optString("hotkeyFullScreen", base.hotkeyFullScreen.name), GamepadHotkey.L3),
            activeProfileName = o.optString("activeProfileName", base.activeProfileName).ifEmpty { "Mặc định" },
            antiDuplicate = o.optBoolean("antiDuplicate", base.antiDuplicate),
            useContextMemory = o.optBoolean("useContextMemory", base.useContextMemory),
            scanIntervalMs = o.optInt("scanIntervalMs", base.scanIntervalMs).coerceIn(300, 1500),
            subtitleFontSizeSp = o.optDouble("subtitleFontSizeSp", base.subtitleFontSizeSp.toDouble()).toFloat().coerceIn(12f, 28f),
            subtitleBgAlphaPct = o.optInt("subtitleBgAlphaPct", base.subtitleBgAlphaPct).coerceIn(40, 100),
            subtitleColor = SubtitleColor.fromName(o.optString("subtitleColor", base.subtitleColor.name)),
            cropLeftPct = o.optDouble("cropLeftPct", base.cropLeftPct.toDouble()).toFloat().coerceIn(0f, 0.95f),
            cropTopPct = o.optDouble("cropTopPct", base.cropTopPct.toDouble()).toFloat().coerceIn(0f, 0.95f),
            cropRightPct = o.optDouble("cropRightPct", base.cropRightPct.toDouble()).toFloat().coerceIn(0.05f, 1f),
            cropBottomPct = o.optDouble("cropBottomPct", base.cropBottomPct.toDouble()).toFloat().coerceIn(0.05f, 1f)
        )

        SettingsStore.save(context, restored)

        val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
        val importedProfiles = GameProfileStore.importJsonArray(context, profilesArr)
        return ImportResult(settings = restored, profileCount = importedProfiles)
    }
}
