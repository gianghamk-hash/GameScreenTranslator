package com.giang.gametranslator

class ScanPolicy {
    private var candidate = ""
    private var since = 0L
    private var count = 0
    var stable = ""
        private set
    fun observe(text: String, now: Long, manual: Boolean = false): String? {
        val normalized = text.trim().replace(Regex("[ \\t]+"), " ")
        if (normalized != candidate) { candidate = normalized; since = now; count = 1 } else count++
        if (normalized.isBlank()) { stable = ""; return null }
        if (!manual && (count < 2 || now - since < 280)) return null
        if (!manual && stable == normalized) return null
        stable = normalized
        return normalized
    }
    fun hasPending() = candidate.isNotBlank() && candidate != stable
    fun retry() { stable = "" }
    fun reset() { candidate = ""; stable = ""; since = 0; count = 0 }
    fun interval(idleMs: Long): Long = when {
        hasPending() || idleMs < 2000 -> 180
        idleMs < 6000 -> 350
        else -> 700
    }
}

data class TextRegion(val text: String, val x: Float, val y: Float, val width: Float, val height: Float)

object DialogueDetection {
    private val hud = Regex("(?i)^(HP|MP|EXP|FPS|ATK|DEF|LV|LEVEL|PING|GOLD|COINS?)\\b[\\s:0-9%/.,+-]*$")
    fun select(blocks: List<TextRegion>): String {
        val valid = blocks.filter { it.text.isNotBlank() && !hud.matches(it.text.trim()) && it.text.any(Char::isLetter) }
        fun score(b: TextRegion): Float {
            val words = b.text.trim().split(Regex("\\s+")).size
            return (if (b.y > .48f) 3f else 0f) + b.width * 5f +
                words.coerceAtMost(24) * .16f + (if (b.text.any { it in ".!?…“”" }) 1.4f else 0f) -
                (if (words < 3) 2f else 0f)
        }
        val best = valid.maxByOrNull(::score) ?: return ""
        return valid.filter {
            it === best || (kotlin.math.abs(it.y - best.y) < .12f &&
                it.x < best.x + best.width && it.x + it.width > best.x && score(it) >= 1.5f)
        }.sortedWith(compareBy<TextRegion> { it.y }.thenBy { it.x }).joinToString("\n") { it.text }
    }
    fun isShortUi(text: String) = Regex("(?i)^(continue|settings|inventory|equipment|save|load|exit|confirm|cancel|back|next|start|resume|retry|pause|quest|skills|map|shop|options|new game|game over|health|mana|damage|attack|defense|level up|select|close|open)[ !.:]*$").matches(text.trim())
}
