package com.giang.gametranslator

object VersionOrder {
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parse(value: String): List<Int>? {
            val normalized = value.trim().removePrefix("v").removePrefix("V")
            if (!normalized.matches(Regex("[0-9]+(\\.[0-9]+){0,2}"))) return null
            val numbers = normalized.split(".").map { it.toIntOrNull() ?: return null }
            return numbers + List(3 - numbers.size) { 0 }
        }
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
