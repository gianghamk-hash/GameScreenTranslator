package com.giang.gametranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

/**
 * TextView hỗ trợ vẽ viền chữ dày chuẩn phụ đề phim (Cinema Outline Stroke)
 * giúp chữ trắng/vàng nổi bật sắc nét ngay cả khi bỏ hoàn toàn khung nền.
 */
class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    var outlineEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var outlineColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var outlineWidthPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var isDrawingOutline = false

    override fun invalidate() {
        if (isDrawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!outlineEnabled || outlineWidthPx <= 0f) {
            super.onDraw(canvas)
            return
        }

        val originalColor = currentTextColor
        val tp = paint

        isDrawingOutline = true
        try {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = outlineWidthPx
            tp.strokeJoin = Paint.Join.ROUND
            tp.strokeCap = Paint.Cap.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)

            tp.style = Paint.Style.FILL
            setTextColor(originalColor)
            super.onDraw(canvas)
        } finally {
            isDrawingOutline = false
        }
    }
}
