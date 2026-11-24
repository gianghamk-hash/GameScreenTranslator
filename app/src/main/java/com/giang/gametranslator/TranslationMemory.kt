package com.giang.gametranslator
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class TranslationMemory(context: Context, profile: String, style: String) {
    private val prefs = context.getSharedPreferences("TranslationMemoryV3", Context.MODE_PRIVATE)
    private val namespace = hash(profile + "\n" + style)
    private val cache = LinkedHashMap<String, String>(400, .75f, true)
    private val history = ArrayDeque<Pair<String, String>>()
    init {
        runCatching {
            val c = JSONObject(prefs.getString("cache_" + namespace, "{}").orEmpty())
            c.keys().forEach { cache[it] = c.getString(it) }
            val h = JSONArray(prefs.getString("history_" + namespace, "[]").orEmpty())
            for (i in maxOf(0, h.length() - 100) until h.length()) {
                val pair = h.getJSONArray(i)
                history.addLast(pair.getString(0) to pair.getString(1))
            }
        }
    }
    fun key(model: String, text: String, contextual: Boolean): String =
        hash("v3\n" + model + "\n" + (if (contextual) history.takeLast(2).joinToString("\n") else "") + "\n" + text)
    fun lookup(key: String): String? = cache[key]
    fun remember(key: String, original: String, translated: String, dialogue: Boolean) {
        if (translated.isBlank()) return
        if (original.length <= 2000 && translated.length <= 3000) {
            cache[key] = translated
            while (cache.size > 400) cache.remove(cache.keys.first())
        }
        if (dialogue && history.lastOrNull() != (original to translated)) {
            history.addLast(original.take(800) to translated.take(1000))
            while (history.size > 100) history.removeFirst()
        }
        val h = JSONArray()
        history.forEach { (a,b) -> h.put(JSONArray().put(a).put(b)) }
        prefs.edit().putString("cache_" + namespace, JSONObject(cache as Map<*, *>).toString())
            .putString("history_" + namespace, h.toString()).apply()
    }
    fun context(text: String): List<Pair<String, String>> {
        val words = Regex("[\\p{L}]{4,}").findAll(text.lowercase()).map { it.value }.toSet()
        val recent = history.takeLast(6)
        val relevant = history.dropLast(recent.size).map { pair ->
            pair to Regex("[\\p{L}]{4,}").findAll(pair.first.lowercase()).count { it.value in words }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(4).map { it.first }
        return (relevant + recent).distinct()
    }
    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun clearAll(context: Context) {
            context.getSharedPreferences("TranslationMemoryV3", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
