package com.giang.gametranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GameProfile(
    val name: String,
    val sourceLanguage: SourceLanguage,
    val translationStyle: TranslationStyle,
    val customGlossary: String,
    val subtitlePositionMode: SubtitlePositionMode,
    val smartColorMatch: Boolean,
    val manualTriggerOnly: Boolean,
    val cropLeftPct: Float,
    val cropTopPct: Float,
    val cropRightPct: Float,
    val cropBottomPct: Float,
    val linkedPackageName: String = ""
)

/**
 * Lưu & chuyển đổi nhanh Profile theo từng tựa game (ngôn ngữ nguồn, vùng thoại, văn phong, tên riêng, vị trí phụ đề).
 */
object GameProfileStore {
    private const val PREF_NAME = "GameProfilesPrefs"
    private const val KEY_PROFILES = "PROFILES_JSON"

    fun listProfiles(context: Context): List<GameProfile> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null) ?: return emptyList()
        val result = mutableListOf<GameProfile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val posName = o.optString("posMode", "").ifEmpty { null }
                val legacyCover = o.optBoolean("cover", false)
                result.add(
                    GameProfile(
                        name = o.optString("name", "Game ${i + 1}"),
                        sourceLanguage = SourceLanguage.fromName(o.optString("lang", null)),
                        translationStyle = TranslationStyle.fromName(o.optString("style", null)),
                        customGlossary = o.optString("glossary", ""),
                        subtitlePositionMode = SubtitlePositionMode.fromName(posName, legacyCover),
                        smartColorMatch = o.optBoolean("smartColor", true),
                        manualTriggerOnly = o.optBoolean("manual", false),
                        cropLeftPct = o.optDouble("left", 0.08).toFloat().coerceIn(0f, 0.95f),
                        cropTopPct = o.optDouble("top", 0.72).toFloat().coerceIn(0f, 0.95f),
                        cropRightPct = o.optDouble("right", 0.92).toFloat().coerceIn(0.05f, 1f),
                        cropBottomPct = o.optDouble("bottom", 0.95).toFloat().coerceIn(0.05f, 1f),
                        linkedPackageName = o.optString("pkg", "").trim()
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun findByPackage(context: Context, packageName: String): GameProfile? {
        if (packageName.isBlank()) return null
        return listProfiles(context).firstOrNull {
            it.linkedPackageName.isNotEmpty() && it.linkedPackageName.equals(packageName, ignoreCase = true)
        }
    }

    fun saveFromSettings(context: Context, profileName: String, s: TranslatorSettings, linkedPackageName: String = "") {
        val cleanName = profileName.trim()
        if (cleanName.isEmpty()) return

        val current = listProfiles(context).toMutableList()
        val existing = current.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        val finalPkg = linkedPackageName.trim().ifEmpty { existing?.linkedPackageName ?: "" }
        val newProfile = GameProfile(
            name = cleanName,
            sourceLanguage = s.sourceLanguage,
            translationStyle = s.translationStyle,
            customGlossary = s.customGlossary,
            subtitlePositionMode = s.subtitlePositionMode,
            smartColorMatch = s.smartColorMatch,
            manualTriggerOnly = s.manualTriggerOnly,
            cropLeftPct = s.cropLeftPct,
            cropTopPct = s.cropTopPct,
            cropRightPct = s.cropRightPct,
            cropBottomPct = s.cropBottomPct,
            linkedPackageName = finalPkg
        )
        val existingIdx = current.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        if (existingIdx >= 0) {
            current[existingIdx] = newProfile
        } else {
            current.add(0, newProfile)
        }
        persist(context, current)
        SettingsStore.save(context, s.copy(activeProfileName = cleanName))
    }

    fun applyProfile(context: Context, profile: GameProfile): TranslatorSettings {
        val updated = SettingsStore.load(context).copy(
            activeProfileName = profile.name,
            sourceLanguage = profile.sourceLanguage,
            translationStyle = profile.translationStyle,
            customGlossary = profile.customGlossary,
            subtitlePositionMode = profile.subtitlePositionMode,
            smartColorMatch = profile.smartColorMatch,
            manualTriggerOnly = profile.manualTriggerOnly,
            cropLeftPct = profile.cropLeftPct,
            cropTopPct = profile.cropTopPct,
            cropRightPct = profile.cropRightPct,
            cropBottomPct = profile.cropBottomPct
        )
        SettingsStore.save(context, updated)
        return updated
    }

    fun deleteProfile(context: Context, profileName: String) {
        val remaining = listProfiles(context).filterNot { it.name.equals(profileName, ignoreCase = true) }
        persist(context, remaining)
        val current = SettingsStore.load(context)
        if (current.activeProfileName.equals(profileName, ignoreCase = true)) {
            SettingsStore.save(context, current.copy(activeProfileName = "Mặc định"))
        }
    }

    fun exportJsonArray(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun importJsonArray(context: Context, arr: JSONArray): Int {
        val parsed = mutableListOf<GameProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) continue
            val posName = o.optString("posMode", "").ifEmpty { null }
            val legacyCover = o.optBoolean("cover", false)
            parsed.add(
                GameProfile(
                    name = name,
                    sourceLanguage = SourceLanguage.fromName(o.optString("lang", null)),
                    translationStyle = TranslationStyle.fromName(o.optString("style", null)),
                    customGlossary = o.optString("glossary", ""),
                    subtitlePositionMode = SubtitlePositionMode.fromName(posName, legacyCover),
                    smartColorMatch = o.optBoolean("smartColor", true),
                    manualTriggerOnly = o.optBoolean("manual", false),
                    cropLeftPct = o.optDouble("left", 0.08).toFloat().coerceIn(0f, 0.95f),
                    cropTopPct = o.optDouble("top", 0.72).toFloat().coerceIn(0f, 0.95f),
                    cropRightPct = o.optDouble("right", 0.92).toFloat().coerceIn(0.05f, 1f),
                    cropBottomPct = o.optDouble("bottom", 0.95).toFloat().coerceIn(0.05f, 1f),
                    linkedPackageName = o.optString("pkg", "").trim()
                )
            )
        }
        if (parsed.isNotEmpty()) {
            persist(context, parsed)
        }
        return parsed.size
    }

    private fun persist(context: Context, list: List<GameProfile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("lang", p.sourceLanguage.name)
                    put("style", p.translationStyle.name)
                    put("glossary", p.customGlossary)
                    put("posMode", p.subtitlePositionMode.name)
                    put("cover", p.subtitlePositionMode == SubtitlePositionMode.COVER_CROP)
                    put("smartColor", p.smartColorMatch)
                    put("manual", p.manualTriggerOnly)
                    put("left", p.cropLeftPct.toDouble())
                    put("top", p.cropTopPct.toDouble())
                    put("right", p.cropRightPct.toDouble())
                    put("bottom", p.cropBottomPct.toDouble())
                    put("pkg", p.linkedPackageName)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, arr.toString())
            .apply()
    }
}
