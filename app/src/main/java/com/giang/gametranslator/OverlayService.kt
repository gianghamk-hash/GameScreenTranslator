package com.giang.gametranslator

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
        private const val PREFS_NAME = "AppPrefs"
        private const val KEY_API = "API_KEY"
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
        GeminiTranslator(apiKey = savedKey, model = prefs.getString("MODEL_NAME", "gemini-3.5-flash-lite").orEmpty().ifBlank { "gemini-3.5-flash-lite" }, context = this, profile = prefs.getString("GAME_PROFILE", "Mặc định").orEmpty(), hybrid = prefs.getBoolean("HYBRID", false)).apply {
            currentStyle = GameStyle.entries.firstOrNull { it.name == prefs.getString("STYLE", "") } ?: GameStyle.RPG_FANTASY
        }
    }

    // Trạng thái Auto-Translate & khung chọn vùng dịch
    private var autoTranslateJob: Job? = null
    private var isAutoTranslating = false
    private var isCapturing = false
    private val policy = ScanPolicy()
    private var translationJob: Job? = null
    private var generation = 0L
    private var activeText = ""
    private var activeAuto = false
    private var manualPending = false
    private var lastChange = 0L
    private var selector: View? = null
    private var subtitleHidden = false
    private var autoFailures = 0
    private var retryAfter = 0L
    private var customZone: Rect? = null
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val zoneKey get() = "ZONE_" + TranslationMemory.hash(prefs.getString("GAME_PROFILE", "Mặc định").orEmpty()) + "_" + (screenWidth > screenHeight)
    private val smartEnabled get() = prefs.getBoolean("SMART_DIALOGUE", true)
    private var lastText = ""
    private var lastTranslation = ""
    private var lastScan = 0L
    private var cropMode = 0
    private var lastLatency = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }
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
            // Android 14+ requires the user's capture consent before starting a
            // mediaProjection foreground service. onCreate runs before this intent arrives.
            startForegroundServiceNotification()
            setupOverlayView()
            restoreZone()
            translator.prepareOffline { ready -> Toast.makeText(this, if (ready) "Dịch offline đã sẵn sàng" else "Chưa tải được model offline (cần Wi-Fi)", Toast.LENGTH_SHORT).show() }
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, android.os.Handler(mainLooper))
            setupVirtualDisplay()
        } else if (mediaProjection == null) stopSelf()
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
        btnAuto?.alpha = 0.55f

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
            x = 16
            y = 100
        }

        tvSubtitle?.maxWidth = (screenWidth * 0.72f).toInt()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tvSubtitle?.textSize = prefs.getInt("SUBTITLE_SIZE", 18).toFloat()
        tvSubtitle?.background?.alpha = prefs.getInt("SUBTITLE_ALPHA", 204)
        view.findViewById<View>(R.id.btnClose).setOnClickListener { stopSelf() }
        view.findViewById<View>(R.id.btnRegion).setOnLongClickListener { openZoneSelector(); true }
        view.findViewById<View>(R.id.btnRegion).setOnClickListener {
            if (!isCapturing) {
                cropMode = (cropMode + 1) % (if (customZone == null) 3 else 4)
                customCropRect = when (cropMode) {
                    1 -> Rect(0, screenHeight * 2 / 3, screenWidth, screenHeight)
                    2 -> Rect(0, 0, screenWidth, screenHeight / 3)
                    3 -> customZone
                    else -> null
                }
                invalidateTranslation(); diffDetector.reset(); policy.reset(); lastText = ""
                Toast.makeText(this, listOf("Toàn màn hình", "1/3 dưới", "1/3 trên", "Vùng tự chọn")[cropMode], Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<View>(R.id.btnHistory).setOnClickListener {
            if (!isCapturing && translationJob?.isActive != true) tvSubtitle?.apply {
                text = translator.dialogueHistory.takeLast(4).joinToString("\n\n") { it.second }.ifBlank { "Chưa có lịch sử" }
                subtitleHidden = false
                visibility = View.VISIBLE
            }
        }
        view.findViewById<View>(R.id.btnHide).setOnClickListener {
            subtitleHidden = !subtitleHidden
            tvSubtitle?.visibility = if (subtitleHidden) View.GONE else View.VISIBLE
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
        if (isCapturing) return
        val styles = GameStyle.entries
        val nextIndex = (styles.indexOf(translator.currentStyle) + 1) % styles.size
        invalidateTranslation()
        translator.currentStyle = styles[nextIndex]
        translator.clearContext()
        lastText = ""
        policy.reset()
        diffDetector.reset()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("STYLE", translator.currentStyle.name).apply()
        Toast.makeText(
            this,
            "Văn phong: ${translator.currentStyle.label}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleAutoTranslate() {
        isAutoTranslating = !isAutoTranslating
        btnAuto?.alpha = if (isAutoTranslating) 1f else 0.55f
        if (isAutoTranslating) {
            diffDetector.reset()
            policy.reset()
            lastChange = android.os.SystemClock.elapsedRealtime()
            Toast.makeText(this, "Đã BẬT tự động dịch", Toast.LENGTH_SHORT).show()
            autoTranslateJob = serviceScope.launch {
                while (isActive && isAutoTranslating) {
                    captureAndTranslate(isAutoMode = true)
                    delay(policy.interval(android.os.SystemClock.elapsedRealtime() - lastChange))
                }
            }
        } else {
            autoTranslateJob?.cancel()
            autoTranslateJob = null
            if (activeAuto) invalidateTranslation()
            Toast.makeText(this, "Đã TẮT tự động dịch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureAndTranslate(isAutoMode: Boolean) {
        if (selector != null) return
        if (isAutoMode && translationJob?.isActive == true && !activeAuto) return
        if (isCapturing) { if (!isAutoMode) manualPending = true; return }
        val reader = imageReader ?: return

        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        isCapturing = true

        val overlayBounds = overlayView?.let { view ->
            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            Rect(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
        }
        serviceScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
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
                    if (bmp.width == screenWidth) bmp else Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight).also { bmp.recycle() }
                }
            } catch (e: Exception) {
                isCapturing = false
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch
            } finally {
                image.close()
            }

            val captured = fullBitmap ?: run {
                isCapturing = false
                return@launch
            }

            try {
                // 2. Nếu là Auto-Translate và vùng thoại chưa đổi chữ -> bỏ qua luôn (< 2ms)
                val changed = withContext(Dispatchers.Default) {
                    // Ignore our own floating controls/subtitle so OCR cannot translate itself.
                    overlayBounds?.let { bounds -> android.graphics.Canvas(captured).drawRect(bounds, android.graphics.Paint().apply { color = android.graphics.Color.BLACK }) }
                    diffDetector.hasRegionChanged(captured, customCropRect)
                }
                if (changed) lastChange = started
                if (isAutoMode && !changed && !policy.hasPending() && started - lastScan < 1500L) {
                    return@launch
                }

                lastScan = started
                // 3. Quét OCR & gom câu hoàn chỉnh
                val ocrText = withContext(Dispatchers.Default) {
                    ocrProcessor.extractText(captured, customCropRect, isAutoMode && customCropRect == null && smartEnabled)
                }

                if (isAutoMode && !isAutoTranslating) return@launch
                if (selector != null) return@launch
                val stableText = policy.observe(ocrText, android.os.SystemClock.elapsedRealtime(), !isAutoMode) ?: return@launch
                if (isAutoMode && android.os.SystemClock.elapsedRealtime() < retryAfter) { policy.retry(); return@launch }
                if (stableText == lastText && lastTranslation.isNotBlank()) {
                    if (activeText.isNotBlank() && activeText != stableText) invalidateTranslation()
                    showSubtitle(lastTranslation)
                    return@launch
                }
                enqueueTranslation(stableText, isAutoMode, started)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { if (!isAutoMode) tvSubtitle?.apply { text = "Không đọc được màn hình. Thử lại."; visibility = View.VISIBLE } } finally {
                if (!captured.isRecycled) captured.recycle()
                isCapturing = false
                if (manualPending) { manualPending = false; captureAndTranslate(false) }
            }
        }
    }

    private fun showSubtitle(text: String) {
        tvSubtitle?.text = text
        if (!subtitleHidden) tvSubtitle?.visibility = View.VISIBLE
    }

    private fun invalidateTranslation() {
        generation++
        translationJob?.cancel()
        translationJob = null
        activeText = ""
    }

    private fun enqueueTranslation(text: String, auto: Boolean, started: Long) {
        if (activeText == text && translationJob?.isActive == true) return
        invalidateTranslation()
        val token = generation
        activeText = text
        activeAuto = auto
        translationJob = serviceScope.launch {
            val result = translator.translate(text) { partial ->
                serviceScope.launch {
                    if (token == generation && !subtitleHidden) showSubtitle(partial)
                }
            }
            if (token != generation) return@launch
            activeText = ""
            result.onSuccess { value ->
                if (value.isNotBlank()) {
                    autoFailures = 0; retryAfter = 0
                    lastText = text; lastTranslation = value
                    lastLatency = android.os.SystemClock.elapsedRealtime() - started
                    showSubtitle(value)
                    tvSubtitle?.contentDescription = "Bản dịch, $lastLatency ms"
                }
            }.onFailure { err ->
                if (!auto) showSubtitle("Lỗi dịch: ${err.message}")
                else {
                    autoFailures = (autoFailures + 1).coerceAtMost(4)
                    retryAfter = android.os.SystemClock.elapsedRealtime() + (1000L shl autoFailures)
                    policy.retry()
                    if (lastTranslation.isNotBlank()) showSubtitle(lastTranslation)
                    else showSubtitle("Đang thử lại dịch…")
                }
            }
            generation++ // Reject callbacks queued after the final result.
        }
    }

    private fun restoreZone() {
        val coords = prefs.getString(zoneKey, "")?.split(",")?.mapNotNull { it.toFloatOrNull() }.orEmpty()
        if (coords.size == 4 && coords.all { it in 0f..1f } && coords[2] > coords[0] && coords[3] > coords[1]) {
            customZone = Rect((coords[0]*screenWidth).toInt(), (coords[1]*screenHeight).toInt(),
                (coords[2]*screenWidth).toInt(), (coords[3]*screenHeight).toInt())
            cropMode = 3; customCropRect = customZone
        }
    }

    private fun openZoneSelector() {
        if (selector != null || isCapturing) return
        invalidateTranslation(); policy.reset()
        val view = object : View(this) {
            val brush = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
            override fun onDraw(canvas: android.graphics.Canvas) {
                canvas.drawColor(0x55000000)
                brush.color = 0xFF62E8D7.toInt(); brush.style = android.graphics.Paint.Style.STROKE
                brush.strokeWidth = 4f
                canvas.drawRect(minOf(x1,x2), minOf(y1,y2), maxOf(x1,x2), maxOf(y1,y2), brush)
                brush.style = android.graphics.Paint.Style.FILL
                brush.textSize = 16f * resources.displayMetrics.scaledDensity
                canvas.drawText("Kéo chọn vùng dịch • Chạm để hủy", 20f, 55f, brush)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x1=e.x; y1=e.y; x2=x1; y2=y1 }
                    MotionEvent.ACTION_MOVE -> { x2=e.x; y2=e.y }
                    MotionEvent.ACTION_UP -> {
                        x2=e.x; y2=e.y
                        val pos = IntArray(2); getLocationOnScreen(pos)
                        val left=(minOf(x1,x2).toInt()+pos[0]).coerceIn(0,screenWidth)
                        val top=(minOf(y1,y2).toInt()+pos[1]).coerceIn(0,screenHeight)
                        val right=(maxOf(x1,x2).toInt()+pos[0]).coerceIn(0,screenWidth)
                        val bottom=(maxOf(y1,y2).toInt()+pos[1]).coerceIn(0,screenHeight)
                        if (right-left >= 32 && bottom-top >= 32) {
                            customZone=Rect(left,top,right,bottom); customCropRect=customZone; cropMode=3
                            prefs.edit().putString(zoneKey, listOf(left.toFloat()/screenWidth,top.toFloat()/screenHeight,
                                right.toFloat()/screenWidth,bottom.toFloat()/screenHeight).joinToString(",")).apply()
                            lastText=""; diffDetector.reset(); policy.reset()
                        }
                        closeZoneSelector()
                    }
                    MotionEvent.ACTION_CANCEL -> closeZoneSelector()
                }
                invalidate()
                return true
            }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START }
        selector=view
        overlayView?.visibility=View.GONE
        try { windowManager.addView(view,params) } catch (_: Exception) { selector=null; overlayView?.visibility=View.VISIBLE }
    }

    private fun closeZoneSelector() {
        selector?.let { runCatching { windowManager.removeView(it) } }
        selector=null
        overlayView?.visibility=View.VISIBLE
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
                    params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, (screenWidth - targetView.width).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, (screenHeight - targetView.height).coerceAtLeast(0))
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
        invalidateTranslation()
        closeZoneSelector()
        serviceScope.cancel()
        translator.close()
        ocrProcessor.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        overlayView?.let { windowManager.removeView(it) }
    }
}
