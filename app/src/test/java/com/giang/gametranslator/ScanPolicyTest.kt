package com.giang.gametranslator
import org.junit.Assert.*
import org.junit.Test
class ScanPolicyTest {
    @Test fun waitsForStableTypingAndSkipsDuplicate() {
        val p = ScanPolicy()
        assertNull(p.observe("Hel", 0))
        assertNull(p.observe("Hello", 180))
        assertNull(p.observe("Hello there", 360))
        assertNull(p.observe("Hello there", 540))
        assertEquals("Hello there", p.observe("Hello there", 720))
        assertNull(p.observe("Hello there", 900))
        assertEquals("Hello there", p.observe("Hello there", 901, true))
    }
    @Test fun adaptiveIntervalsAndRetry() {
        val p = ScanPolicy()
        assertEquals(700L, p.interval(7000))
        p.observe("New line", 0)
        assertEquals(180L, p.interval(7000))
        assertEquals("New line", p.observe("New line", 300))
        p.retry()
        assertEquals("New line", p.observe("New line", 500))
    }
    @Test fun dialoguePrefersBottomAndIgnoresHud() {
        assertEquals("I have been waiting for you.", DialogueDetection.select(listOf(
            TextRegion("HP 100/100",0f,0f,.2f,.04f), TextRegion("Inventory",.1f,.1f,.2f,.03f),
            TextRegion("I have been waiting for you.",.1f,.72f,.7f,.08f))))
        assertTrue(DialogueDetection.isShortUi("Inventory"))
        assertFalse(DialogueDetection.isShortUi("I will not attack you."))
    }
}
