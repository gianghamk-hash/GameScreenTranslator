package com.giang.gametranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticEntry(
    val timestampMs: Long,
    val level: String,
    val message: String
) {
    fun formatLine(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return "[${sdf.format(Date(timestampMs))}] [$level] $message"
    }
}

/**
 * Lưu 30 sự kiện / lỗi kỹ thuật gần nhất (nhẹ, thread-safe, 0ms overhead)
 * để dễ dàng chẩn đoán khi app gặp sự cố mạng, API 429, OCR hay hệ thống thu hồi quyền.
 */
object DiagnosticLogStore {
    private const val PREF_NAME = "DiagnosticLogPrefs"
    private const val KEY_LOGS = "LOGS_JSON"
    private const val MAX_LOGS = 30

    private val lock = Any()
    private var memoryLogs: MutableList<DiagnosticEntry>? = null

    private fun ensureLoaded(context: Context): MutableList<DiagnosticEntry> {
        memoryLogs?.let { return it }
        val list = mutableListOf<DiagnosticEntry>()
        val raw = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOGS, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        DiagnosticEntry(
                            timestampMs = o.optLong("t", System.currentTimeMillis()),
                            level = o.optString("l", "INFO"),
                            message = o.optString("m", "")
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        memoryLogs = list
        return list
    }

    fun info(context: Context, message: String) = log(context, "INFO", message)
    fun warn(context: Context, message: String) = log(context, "WARN", message)
    fun error(context: Context, message: String) = log(context, "ERR", message)

    private fun log(context: Context, level: String, message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val list = ensureLoaded(context)
            list.add(0, DiagnosticEntry(System.currentTimeMillis(), level, clean))
            while (list.size > MAX_LOGS) {
                list.removeAt(list.lastIndex)
            }
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject().apply {
                        put("t", e.timestampMs)
                        put("l", e.level)
                        put("m", e.message)
                    }
                )
            }
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LOGS, arr.toString())
                .apply()
        }
    }

    fun getAll(context: Context): List<DiagnosticEntry> {
        synchronized(lock) {
            return ensureLoaded(context).toList()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            ensureLoaded(context).clear()
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LOGS)
                .apply()
        }
    }
}
