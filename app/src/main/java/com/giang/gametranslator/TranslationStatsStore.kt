package com.giang.gametranslator

import android.content.Context
import java.util.Locale

/**
 * Thống kê tốc độ dịch (ms) & hiệu quả tiết kiệm lượt gọi AI nhờ Cache 0ms / Từ điển Offline / Bộ lọc HUD.
 */
object TranslationStatsStore {
    private const val PREF_NAME = "TranslationStatsPrefs"

    data class StatsSnapshot(
        val aiCount: Int,
        val aiTotalMs: Long,
        val offlineCount: Int,
        val cacheHits: Int,
        val blacklistSkipped: Int,
        val lastProvider: String,
        val lastLatencyMs: Long
    ) {
        val totalHandled: Int get() = aiCount + offlineCount + cacheHits
        val avgAiMs: Long get() = if (aiCount > 0) aiTotalMs / aiCount else 0L
        val savedPct: Int
            get() {
                val total = totalHandled + blacklistSkipped
                if (total <= 0) return 0
                return (((offlineCount + cacheHits + blacklistSkipped) * 100f) / total).toInt().coerceIn(0, 100)
            }

        fun formatReport(): String {
            val avgStr = if (aiCount > 0) "${avgAiMs} ms" else "Chưa có"
            val lastStr = if (lastProvider.isNotEmpty()) "$lastProvider (${lastLatencyMs} ms)" else "Chưa có"
            return buildString {
                append("• Tổng câu thoại đã dịch: $totalHandled câu\n")
                append("• Gọi AI Online: $aiCount câu (TB: $avgStr)\n")
                append("• Bắt trúng Cache 0ms: $cacheHits câu\n")
                append("• Dịch Offline / Từ điển game: $offlineCount câu\n")
                append("• Chữ rác HUD đã chặn (Blacklist): $blacklistSkipped lần\n")
                append("• Tỷ lệ tiết kiệm lượt gọi AI: $savedPct%\n")
                append("• Lần dịch gần nhất: $lastStr")
            }
        }
    }

    fun recordAi(context: Context, providerLabel: String, latencyMs: Long) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val safeMs = latencyMs.coerceIn(1L, 60_000L)
        p.edit()
            .putInt("AI_COUNT", p.getInt("AI_COUNT", 0) + 1)
            .putLong("AI_TOTAL_MS", p.getLong("AI_TOTAL_MS", 0L) + safeMs)
            .putString("LAST_PROVIDER", providerLabel)
            .putLong("LAST_LATENCY_MS", safeMs)
            .apply()
    }

    fun recordOffline(context: Context, latencyMs: Long) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val safeMs = latencyMs.coerceIn(0L, 60_000L)
        p.edit()
            .putInt("OFFLINE_COUNT", p.getInt("OFFLINE_COUNT", 0) + 1)
            .putString("LAST_PROVIDER", "Offline / Từ điển")
            .putLong("LAST_LATENCY_MS", safeMs)
            .apply()
    }

    fun recordCacheHit(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit()
            .putInt("CACHE_HITS", p.getInt("CACHE_HITS", 0) + 1)
            .putString("LAST_PROVIDER", "Cache 0ms")
            .putLong("LAST_LATENCY_MS", 0L)
            .apply()
    }

    fun recordBlacklistSkip(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        p.edit()
            .putInt("BLACKLIST_SKIPPED", p.getInt("BLACKLIST_SKIPPED", 0) + 1)
            .apply()
    }

    fun snapshot(context: Context): StatsSnapshot {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return StatsSnapshot(
            aiCount = p.getInt("AI_COUNT", 0),
            aiTotalMs = p.getLong("AI_TOTAL_MS", 0L),
            offlineCount = p.getInt("OFFLINE_COUNT", 0),
            cacheHits = p.getInt("CACHE_HITS", 0),
            blacklistSkipped = p.getInt("BLACKLIST_SKIPPED", 0),
            lastProvider = p.getString("LAST_PROVIDER", "") ?: "",
            lastLatencyMs = p.getLong("LAST_LATENCY_MS", 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
