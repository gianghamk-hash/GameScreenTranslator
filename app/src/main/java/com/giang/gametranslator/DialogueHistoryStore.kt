package com.giang.gametranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DialogueEntry(
    val timestampMs: Long,
    val sourceEn: String,
    val translatedVi: String,
    val usedOffline: Boolean
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}

/**
 * Lưu lịch sử tối đa 50 câu thoại gần nhất (bộ nhớ RAM siêu nhanh + đồng bộ SharedPreferences).
 * Không gây bất kỳ độ trễ nào lên luồng dịch thời gian thực.
 */
object DialogueHistoryStore {
    private const val PREF_NAME = "DialogueHistoryPrefs"
    private const val KEY_ENTRIES = "ENTRIES_JSON"
    private const val MAX_ENTRIES = 50

    private val lock = Any()
    private var memoryList: MutableList<DialogueEntry>? = null

    private fun ensureLoaded(context: Context): MutableList<DialogueEntry> {
        memoryList?.let { return it }
        val list = mutableListOf<DialogueEntry>()
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        DialogueEntry(
                            timestampMs = obj.optLong("t", System.currentTimeMillis()),
                            sourceEn = obj.optString("en", ""),
                            translatedVi = obj.optString("vi", ""),
                            usedOffline = obj.optBoolean("off", false)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        memoryList = list
        return list
    }

    fun add(context: Context, sourceEn: String, translatedVi: String, usedOffline: Boolean) {
        val cleanEn = sourceEn.trim()
        val cleanVi = translatedVi.trim()
        if (cleanEn.isEmpty() || cleanVi.isEmpty()) return

        synchronized(lock) {
            val list = ensureLoaded(context)
            // Tránh ghi trùng câu liền kề
            val latest = list.firstOrNull()
            if (latest != null && latest.translatedVi.equals(cleanVi, ignoreCase = true)) {
                return
            }
            list.add(0, DialogueEntry(System.currentTimeMillis(), cleanEn, cleanVi, usedOffline))
            while (list.size > MAX_ENTRIES) {
                list.removeAt(list.lastIndex)
            }
            persistAsync(context.applicationContext, list.toList())
        }
    }

    fun getAll(context: Context): List<DialogueEntry> {
        synchronized(lock) {
            return ensureLoaded(context).toList()
        }
    }

    fun count(context: Context): Int {
        synchronized(lock) {
            return ensureLoaded(context).size
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            ensureLoaded(context).clear()
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENTRIES)
                .apply()
        }
    }

    private fun persistAsync(appContext: Context, snapshot: List<DialogueEntry>) {
        val arr = JSONArray()
        for (e in snapshot) {
            arr.put(
                JSONObject().apply {
                    put("t", e.timestampMs)
                    put("en", e.sourceEn)
                    put("vi", e.translatedVi)
                    put("off", e.usedOffline)
                }
            )
        }
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}
