package com.example.gamescreentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

class PixelDiffDetector(
    private val cols: Int = 16,
    private val rows: Int = 8,
    private val changeThresholdRatio: Float = 0.08f // Đổi > 8% điểm mẫu thì coi là thoại mới
) {
    private var lastSignature: IntArray? = null

    fun hasRegionChanged(bitmap: Bitmap, cropRect: Rect?): Boolean {
        val rect = cropRect ?: Rect(0, 0, bitmap.width, bitmap.height)
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val width = rect.width().coerceAtMost(bitmap.width - left)
        val height = rect.height().coerceAtMost(bitmap.height - top)
        if (width <= 0 || height <= 0) return false

        val current = IntArray(cols * rows)
        var idx = 0
        for (r in 0 until rows) {
            val y = top + (height * (r + 0.5f) / rows).toInt().coerceIn(0, height - 1)
            for (c in 0 until cols) {
                val x = left + (width * (c + 0.5f) / cols).toInt().coerceIn(0, width - 1)
                current[idx++] = bitmap.getPixel(x, y)
            }
        }

        val prev = lastSignature
        lastSignature = current
        if (prev == null || prev.size != current.size) return true

        var changedCount = 0
        for (i in current.indices) {
            val c1 = prev[i]
            val c2 = current[i]
            val diff = abs(Color.red(c1) - Color.red(c2)) +
                       abs(Color.green(c1) - Color.green(c2)) +
                       abs(Color.blue(c1) - Color.blue(c2))
            if (diff > 45) changedCount++
        }

        return (changedCount.toFloat() / current.size.toFloat()) >= changeThresholdRatio
    }

    fun reset() {
        lastSignature = null
    }
}
