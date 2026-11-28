package com.giang.gametranslator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Lắng nghe phím tắt tay cầm (R3 / L3 / Select) và tự động đổi Game Profile
 * khi người dùng chuyển sang game/giả lập đã liên kết.
 */
class GamepadHotkeyService : AccessibilityService() {

    private var lastTriggerMs = 0L
    private var lastObservedPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.trim() ?: return
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return
        if (pkg == lastObservedPackage) return
        lastObservedPackage = pkg

        if (!OverlayService.isServiceRunning) return
        val settings = SettingsStore.load(this)
        val matched = GameProfileStore.findByPackage(this, pkg)

        if (matched != null) {
            if (settings.autoSwitchProfile && !matched.name.equals(settings.activeProfileName, ignoreCase = true)) {
                GameProfileStore.applyProfile(this, matched)
                safeSendOverlayIntent(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_RELOAD_SETTINGS
                })
                Toast.makeText(this, "🎮 Tự đổi Profile: ${matched.name}", Toast.LENGTH_SHORT).show()
            }
            if (settings.autoPauseOutsideGame) {
                safeSendOverlayIntent(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_SET_AUTO_PAUSE
                    putExtra("PAUSED", false)
                })
            }
        } else if (settings.autoPauseOutsideGame && GameProfileStore.hasAnyLinkedPackage(this)) {
            safeSendOverlayIntent(Intent(this, OverlayService::class.java).apply {
                action = SettingsStore.ACTION_SET_AUTO_PAUSE
                putExtra("PAUSED", true)
            })
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return false
        }
        if (!OverlayService.isServiceRunning) {
            return false
        }

        val settings = SettingsStore.load(this)
        val keyCode = event.keyCode
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < 250L) return false

        when {
            settings.hotkeyTranslate != GamepadHotkey.NONE && keyCode == settings.hotkeyTranslate.keyCode -> {
                lastTriggerMs = now
                safeSendOverlayIntent(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_MANUAL_SCAN
                })
            }
            settings.hotkeyFullScreen != GamepadHotkey.NONE && keyCode == settings.hotkeyFullScreen.keyCode -> {
                lastTriggerMs = now
                safeSendOverlayIntent(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_FULLSCREEN_TRANSLATE
                })
            }
        }
        // Trả về false để không chặn phím của game nếu game có dùng
        return false
    }

    private fun safeSendOverlayIntent(intent: Intent) {
        if (!OverlayService.isServiceRunning) return
        try {
            startService(intent)
        } catch (_: Exception) {
        }
    }
}
