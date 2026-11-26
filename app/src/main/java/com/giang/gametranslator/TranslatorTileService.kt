package com.giang.gametranslator

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Ô bật/tắt nhanh trên thanh trạng thái Android (Quick Settings Tile).
 * - Khi chưa chạy: chạm 1 cái sẽ tự mở hộp thoại cấp quyền chụp màn hình và bật dịch ngay.
 * - Khi đang chạy: chạm 1 cái để tắt gọn OverlayService ngay lập tức.
 */
class TranslatorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.isServiceRunning) {
            stopService(Intent(this, OverlayService::class.java))
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = "Chạm để bật"
                updateTile()
            }
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTO_START, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    2028,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        if (OverlayService.isServiceRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Dịch Game"
            tile.subtitle = "Đang chạy"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Dịch Game"
            tile.subtitle = "Chạm để bật"
        }
        tile.updateTile()
    }

    companion object {
        const val EXTRA_AUTO_START = "EXTRA_AUTO_START"

        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, TranslatorTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
