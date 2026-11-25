package com.giang.gametranslator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Lắng nghe phím tắt tay cầm (R3 / L3 / Select) toàn hệ thống ngay cả khi đang chơi game
 * mà không chiếm quyền điều khiển hay chặn nút bấm của game.
 */
class GamepadHotkeyService : AccessibilityService() {

    private var lastTriggerMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không xử lý sự kiện UI để giữ hiệu năng 0% overhead
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
                startService(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_MANUAL_SCAN
                })
            }
            settings.hotkeyFullScreen != GamepadHotkey.NONE && keyCode == settings.hotkeyFullScreen.keyCode -> {
                lastTriggerMs = now
                startService(Intent(this, OverlayService::class.java).apply {
                    action = SettingsStore.ACTION_FULLSCREEN_TRANSLATE
                })
            }
        }
        // Trả về false để không chặn phím của game nếu game có dùng
        return false
    }
}
