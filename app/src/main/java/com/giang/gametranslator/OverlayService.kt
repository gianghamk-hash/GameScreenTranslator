package com.giang.gametranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayTextView: TextView
    private lateinit var floatingButton: TextView
    private var selectionView: CropSelectionView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Tọa độ vùng quét mặc định theo tỷ lệ % màn hình (35% phía dưới)
    private var cropLeftPct = 0.05f
    private var cropTopPct = 0.65f
    private var cropRightPct = 0.95f
    private var cropBottomPct = 0.98f

    private var lastRecognizedText = ""
    private var emptyFrameCount = 0
    private var isProcessing = false
    private var isRunning = false
    private var isSelectingRegion = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isSelectingRegion) {
                captureAndProcessFrame()
            }
            handler.postDelayed(this, 1000) // Quét mỗi 1 giây
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayWindow()
        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra("DATA", Intent::class.java)

        if (resultCode == Activity.RESULT_OK && data != null) {
            setupMediaProjection(resultCode, data)
            isRunning = true
            handler.postDelayed(captureRunnable, 1000)
        } else {
            overlayTextView.text = "Chưa nhận được quyền chụp màn hình!"
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "translator_channel"
        val channel = NotificationChannel(
            channelId,
            "Game Screen Translator",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("AI Dịch Game đang chạy")
            .setContentText("Chạm nút 🎯 để chọn vùng thoại")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // 1. Khung hiển thị phụ đề (xuyên cảm ứng, tự ẩn khi không có thoại)
    private fun setupOverlayWindow() {
        overlayTextView = TextView(this).apply {
            text = "Bấm 🎯 để kéo chọn vùng thoại"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(36, 20, 36, 20)
            gravity = Gravity.CENTER
            maxWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        windowManager.addView(overlayTextView, params)
    }

    // 2. Nút bong bóng 🎯 (kéo di chuyển được, chạm nhẹ để vẽ Crop Box)
    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC1E88E5"))
            setStroke(3, Color.WHITE)
        }

        floatingButton = TextView(this).apply {
            text = "🎯"
            textSize = 20f
            gravity = Gravity.CENTER
            background = bg
        }

        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingButton, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < 15 && dy < 15) {
                        openCropSelectionOverlay()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingButton, params)
    }

    // 3. Mở lớp phủ vẽ khung đỏ Crop Box
    private fun openCropSelectionOverlay() {
        if (isSelectingRegion) return
        isSelectingRegion = true
        overlayTextView.visibility = View.GONE

        selectionView = CropSelectionView(this) { leftPct, topPct, rightPct, bottomPct ->
            cropLeftPct = leftPct
            cropTopPct = topPct
            cropRightPct = rightPct
            cropBottomPct = bottomPct
            lastRecognizedText = ""

            closeCropSelectionOverlay()
            Toast.makeText(this, "Đã lưu vùng dịch mới!", Toast.LENGTH_SHORT).show()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(selectionView, params)
    }

    private fun closeCropSelectionOverlay() {
        selectionView?.let {
            windowManager.removeView(it)
            selectionView = null
        }
        isSelectingRegion = false
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameTranslatorDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureAndProcessFrame() {
        if (isProcessing) return
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val x = (image.width * cropLeftPct).toInt().coerceIn(0, image.width - 10)
            val y = (image.height * cropTopPct).toInt().coerceIn(0, image.height - 10)
            val w = ((image.width * (cropRightPct - cropLeftPct)).toInt()).coerceIn(10, image.width - x)
            val h = ((image.height * (cropBottomPct - cropTopPct)).toInt()).coerceIn(10, image.height - y)

            val croppedBitmap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()

            isProcessing = true
            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text.replace("\n", " ").trim()
                    if (rawText.length > 3) {
                        emptyFrameCount = 0
                        if (rawText != lastRecognizedText) {
                            lastRecognizedText = rawText
                            handler.post {
                                if (!isSelectingRegion) {
                                    overlayTextView.text = "Đang dịch: ${rawText.take(60)}..."
                                    overlayTextView.visibility = View.VISIBLE
                                }
                            }
                            translateWithGemini(rawText)
                        } else {
                            isProcessing = false
                        }
                    } else {
                        emptyFrameCount++
                        if (emptyFrameCount >= 2) {
                            lastRecognizedText = ""
                            handler.post { overlayTextView.visibility = View.GONE }
                        }
                        isProcessing = false
                    }
                    croppedBitmap.recycle()
                }
                .addOnFailureListener { e ->
                    handler.post {
                        overlayTextView.text = "Lỗi OCR: ${e.message}"
                        overlayTextView.visibility = View.VISIBLE
                    }
                    isProcessing = false
                    croppedBitmap.recycle()
                }
        } catch (e: Exception) {
            isProcessing = false
        } finally {
            image.close()
        }
    }

    private fun translateWithGemini(englishText: String) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "")?.trim() ?: ""
        val modelName = prefs.getString("MODEL_NAME", "gemini-3.5-flash-lite")?.trim() ?: "gemini-3.5-flash-lite"

        if (apiKey.isEmpty()) {
            handler.post {
                overlayTextView.text = "Chưa nhập Gemini API Key trong App!"
                overlayTextView.visibility = View.VISIBLE
            }
            isProcessing = false
            return
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val prompt = """
            Bạn là trình dịch phụ đề game thời gian thực. 
            Dịch câu tiếng Anh sau sang tiếng Việt tự nhiên, đúng văn phong game nhập vai/hành động. 
            Giữ nguyên tên riêng. Chỉ trả về duy nhất câu tiếng Việt, tuyệt đối không giải thích:
            "$englishText"
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    overlayTextView.text = "Lỗi mạng: ${e.message}"
                    overlayTextView.visibility = View.VISIBLE
                }
                isProcessing = false
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: ""
                    if (it.isSuccessful) {
                        try {
                            val json = JSONObject(bodyStr)
                            val translated = json.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                                .trim()

                            handler.post {
                                if (!isSelectingRegion) {
                                    overlayTextView.text = translated
                                    overlayTextView.visibility = View.VISIBLE
                                }
                            }
                        } catch (e: Exception) {
                            handler.post {
                                overlayTextView.text = "Lỗi đọc JSON: ${e.message}"
                                overlayTextView.visibility = View.VISIBLE
                            }
                        }
                    } else {
                        handler.post {
                            overlayTextView.text = "Lỗi API ${it.code}: ${bodyStr.take(120)}"
                            overlayTextView.visibility = View.VISIBLE
                        }
                    }
                    isProcessing = false
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(captureRunnable)
        closeCropSelectionOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::overlayTextView.isInitialized) windowManager.removeView(overlayTextView)
        if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // View tùy chỉnh để kéo vẽ khung đỏ chọn vùng dịch
    private inner class CropSelectionView(
        context: Context,
        private val onRegionSelected: (Float, Float, Float, Float) -> Unit
    ) : View(context) {

        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var isDragging = false

        private val bgPaint = Paint().apply { color = Color.parseColor("#66000000") }
        private val borderPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            if (!isDragging) {
                canvas.drawText("Kéo ngón tay để khoanh vùng hộp thoại game", width / 2f, height / 2f, textPaint)
            } else {
                val rect = RectF(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY)
                )
                canvas.drawRect(rect, borderPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    currentX = event.x
                    currentY = event.y
                    isDragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    currentX = event.x
                    currentY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    val left = min(startX, currentX)
                    val top = min(startY, currentY)
                    val right = max(startX, currentX)
                    val bottom = max(startY, currentY)

                    if (right - left > 50 && bottom - top > 50 && width > 0 && height > 0) {
                        onRegionSelected(
                            (left / width).coerceIn(0f, 1f),
                            (top / height).coerceIn(0f, 1f),
                            (right / width).coerceIn(0f, 1f),
                            (bottom / height).coerceIn(0f, 1f)
                        )
                    } else {
                        closeCropSelectionOverlay()
                    }
                }
            }
            return true
        }
    }
}