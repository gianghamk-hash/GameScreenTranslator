package com.giang.gametranslator

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun serviceContextInflatesTranslationControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val overlay = android.view.LayoutInflater.from(context).inflate(R.layout.layout_overlay, null)
        assertNotNull(overlay.findViewById<android.view.View>(R.id.btnTranslate))
        assertNotNull(overlay.findViewById<android.view.View>(R.id.btnRegion))
        assertNotNull(overlay.findViewById<android.view.View>(R.id.tvSubtitle))
    }

    @Test
    fun mainActivityStartsAndInflatesControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNotNull(activity.findViewById<android.view.View>(R.id.edtApiKey))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnStart))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnUpdate))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.giang.gametranslator", appContext.packageName)
    }
}
