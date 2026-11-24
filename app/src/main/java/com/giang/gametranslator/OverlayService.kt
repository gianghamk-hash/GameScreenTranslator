package com.example.gamescreentranslator // Đổi lại cho khớp package của bạn

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "game_translator_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS_NAME = "GameTranslatorPrefs"
        private const val KEY_API = "gemini_api_key"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // 3 module tối ưu mới
    private val ocrProcessor = OcrProcessor()
    private val diffDetector = PixelDiffDetector()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val translator: GeminiTranslator by lazy {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API, "").orEmpty()
        GeminiTranslator(apiKey = savedKey)
    }

    // Trạng thái Auto-Translate & khung chọn vùng dịch
    private var autoTranslateJob: Job? = null
    private var isAutoTranslating = false
    private var isCapturing = false
    private var customCropRect: Rect? = null // Gán tọa độ khung crop của bạn vào đây

    // Các View trên Overlay (đổi ID khớp với XML của bạn)
    private var tvSubtitle: TextView? = null
    private var btnTranslate: ImageButton? = null
    private var btnAuto: ImageButton? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initScreenMetrics()
        startForegroundServiceNotification()
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        return START_NOT_STICKY
    }

    private fun initScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun setupOverlayView() {
        // Thay R.layout.layout_overlay bằng tên file XML overlay hiện tại của bạn
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        overlayView = view

        tvSubtitle = view.findViewById(R.id.tvSubtitle)
        btnTranslate = view.findViewById(R.id.btnTranslate)
        btnAuto = view.findViewById(R.id.btnAuto)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        // Bấm 1 chạm để dịch ngay lập tức
        btnTranslate?.setOnClickListener {
            captureAndTranslate(isAutoMode = false)
        }

        // Chạm giữ nút dịch để đổi nhanh Văn Phong (RPG -> Kiếm hiệp -> Hiện đại -> UI)
        btnTranslate?.setOnLongClickListener {
            cycleGameStyle()
            true
        }

        // Bật / tắt chế độ Auto-Translate thông minh (dùng PixelDiff)
        btnAuto?.setOnClickListener {
            toggleAutoTranslate()
        }

        // Kéo thả bong bóng nổi
        enableDragOnView(view, params)

        windowManager.addView(view, params)
    }

    private fun cycleGameStyle() {
        val styles = GameStyle.entries
        val nextIndex = (styles.indexOf(translator.currentStyle) + 1) % styles.size
        translator.currentStyle = styles[nextIndex]
        translator.clearContext() // Xóa ngữ cảnh cũ khi đổi thể loại game
        Toast.makeText(
            this,
            "Văn phong: ${translator.currentStyle.label}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleAutoTranslate() {
        isAutoTranslating = !isAutoTranslating
        if (isAutoTranslating) {
            diffDetector.reset()
            Toast.makeText(this, "Đã BẬT tự động dịch", Toast.LENGTH_SHORT).show()
            autoTranslateJob = serviceScope.launch {
                while (isActive && isAutoTranslating) {
                    captureAndTranslate(isAutoMode = true)
                    delay(700L) // Quét kiểm tra thay đổi mỗi 0.7 giây
                }
            }
        } else {
            autoTranslateJob?.cancel()
            autoTranslateJob = null
            Toast.makeText(this, "Đã TẮT tự động dịch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureAndTranslate(isAutoMode: Boolean) {
        if (isCapturing) return
        val reader = imageReader ?: return

        val image = reader.acquireLatestImage() ?: return
        isCapturing = true

        serviceScope.launch {
            var fullBitmap: Bitmap? = null
            try {
                // 1. Chuyển Image sang Bitmap (xử lý chuẩn rowStride tránh xé hình)
                fullBitmap = withContext(Dispatchers.Default) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bmp = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    bmp
                }
            } finally {
                image.close()
            }

            val captured = fullBitmap ?: run {
                isCapturing = false
                return@launch
            }

            try {
                // 2. Nếu là Auto-Translate và vùng thoại chưa đổi chữ -> bỏ qua luôn (< 2ms)
                if (isAutoMode && !diffDetector.hasRegionChanged(captured, customCropRect)) {
                    return@launch
                }

                // 3. Quét OCR & gom câu hoàn chỉnh
                val ocrText = withContext(Dispatchers.Default) {
                    ocrProcessor.extractText(captured, customCropRect)
                }

                if (ocrText.isBlank()) return@launch

                // 4. Dịch bằng Gemini 3.5 Flash-Lite (kèm LRU Cache + Ngữ cảnh)
                val result = translator.translate(ocrText)
                result.onSuccess { vietnameseText ->
                    if (vietnameseText.isNotBlank()) {
                        tvSubtitle?.text = vietnameseText
                        tvSubtitle?.visibility = View.VISIBLE
                    }
                }.onFailure { err ->
                    if (!isAutoMode) {
                        tvSubtitle?.text = "Lỗi dịch: ${err.message}"
                        tvSubtitle?.visibility = View.VISIBLE
                    }
                }
            } finally {
                if (!captured.isRecycled) captured.recycle()
                isCapturing = false
            }
        }
    }

    private fun enableDragOnView(targetView: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        targetView.setOnTouchListener { _, event ->
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
                    windowManager.updateViewLayout(targetView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Screen Translator",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Game Screen Translator đang chạy")
            .setContentText("Chạm nút nổi để dịch màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoTranslateJob?.cancel()
        serviceScope.cancel()
        ocrProcessor.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayView?.let { windowManager.removeView(it) }
    }
}
