package com.giang.gametranslator
import org.junit.Assert.*
import org.junit.Test
class VersionOrderTest {
    @Test fun comparesNumericVersions() {
        assertTrue(VersionOrder.isNewer("v2.10.0", "2.9"))
        assertFalse(VersionOrder.isNewer("V1.0", "2.0"))
        assertFalse(VersionOrder.isNewer("v2.0.0", "2.0"))
        assertFalse(VersionOrder.isNewer("v3.0-beta", "2.0"))
    }
}
