package com.giang.gametranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrProcessor {
    // Có thể đổi sang Japanese/Chinese/Korean options nếu cần chơi game Nhật/Trung
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Cắt vùng chọn, nén xuống tối đa 1080px để tăng tốc OCR, rồi nhận diện & gom câu.
     */
    suspend fun extractText(fullScreenBitmap: Bitmap, cropRect: Rect?): String {
        val cropped = cropSafely(fullScreenBitmap, cropRect)
        val scaled = scaleDownIfNeeded(cropped, maxDimension = 1080)

        return try {
            recognizeMergedText(scaled)
        } finally {
            // Tránh rò rỉ bộ nhớ (Memory Leak) khi chạy nền lâu dài
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== fullScreenBitmap) cropped.recycle()
        }
    }

    private suspend fun recognizeMergedText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Sắp xếp block từ trên xuống dưới, trái sang phải
                    val sortedBlocks = visionText.textBlocks.sortedWith(
                        compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })
                    )

                    val paragraphs = sortedBlocks.mapNotNull { block ->
                        // Nối các dòng trong cùng 1 khối thoại bằng dấu cách thay vì xuống dòng
                        val merged = block.lines
                            .sortedBy { it.boundingBox?.top ?: 0 }
                            .joinToString(" ") { it.text.trim() }
                            .trim()
                        merged.ifBlank { null }
                    }

                    if (cont.isActive) cont.resume(paragraphs.joinToString("\n"))
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun cropSafely(source: Bitmap, rect: Rect?): Bitmap {
        if (rect == null) return source
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val width = rect.width().coerceAtMost(source.width - left)
        val height = rect.height().coerceAtMost(source.height - top)
        if (width <= 0 || height <= 0) return source
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap

        val ratio = maxDimension.toFloat() / maxOf(w, h).toFloat()
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    fun close() {
        recognizer.close()
    }
}
