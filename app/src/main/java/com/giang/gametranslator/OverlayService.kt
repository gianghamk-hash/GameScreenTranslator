package com.giang.gametranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayTextView: TextView
    private lateinit var overlayBgDrawable: GradientDrawable
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var floatingButton: TextView

    private var selectionView: CropSelectionView? = null
    private var quickMenuView: View? = null
    private var fullScreenOverlayView: FullScreenTranslationView? = null
    private var historyOverlayView: View? = null
    private var profilePickerView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageReaderLock = Any()

    // Luồng UI chính & luồng nền riêng cho xử lý Bitmap / Pixel / Smart Color (không bao giờ làm lag UI)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val deduplicator = TextDeduplicator()
    private lateinit var translationEngine: TranslationEngine

    @Volatile
    private var settings = TranslatorSettings()
    @Volatile
    private var cropLeftPct = 0.08f
    @Volatile
    private var cropTopPct = 0.72f
    @Volatile
    private var cropRightPct = 0.92f
    @Volatile
    private var cropBottomPct = 0.95f

    // Smart Color Match chống rung
    @Volatile
    private var sampledBgColor: Int? = null
    @Volatile
    private var sampledIsLightBg: Boolean = false
    private var lastSampledR = -1
    private var lastSampledG = -1
    private var lastSampledB = -1

    // Watchdog tự hồi phục VirtualDisplay
    private var consecutiveNullFrames = 0
    private var lastRecoveryMs = 0L
    private var recoveryWindowStartMs = 0L
    private var recoveryCountInWindow = 0
    @Volatile
    private var needsWakeCheck = false

    private var emptyFrameCount = 0
    private val isOcrRunning = AtomicBoolean(false)
    @Volatile
    private var isRunning = false
    @Volatile
    private var isPaused = false
    @Volatile
    private var isSelectingRegion = false
    @Volatile
    private var isPeeking = false
    @Volatile
    private var isFullScreenBusy = false
    @Volatile
    private var hasActiveSubtitle = false
    private var lastDisplayedText = ""
    private var translationSequence = 0L

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT && isRunning) {
                needsWakeCheck = true
                DiagnosticLogStore.info(this@OverlayService, "Màn hình vừa mở khóa → kích hoạt kiểm tra Watchdog")
                if (!settings.manualTriggerOnly) {
                    scheduleNextCapture()
                }
            }
        }
    }

    private val fadeBubbleRunnable = Runnable {
        if (::floatingButton.isInitialized && settings.autoFadeBubble && quickMenuView == null) {
            floatingButton.animate()
                .alpha(0.28f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(300)
                .start()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (settings.manualTriggerOnly) return

            val modalOpen = isSelectingRegion || quickMenuView != null ||
                fullScreenOverlayView != null || historyOverlayView != null ||
                profilePickerView != null || isFullScreenBusy
            if (!isPaused && !modalOpen && !isOcrRunning.get() && !isPeeking) {
                triggerCaptureCycle(forceManual = false)
            } else {
                scheduleNextCapture()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        translationEngine = TranslationEngine(applicationContext)
        captureThread = HandlerThread("GameTranslatorCapture", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
        }
        captureHandler = Handler(captureThread.looper)

        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        reloadSettingsFromStore()
        setupOverlayWindow()
        setupFloatingBubble()

        try {
            registerReceiver(screenWakeReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (_: Exception) {
        }
        DiagnosticLogStore.info(this, "OverlayService khởi tạo sẵn sàng (Background Capture Thread)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SettingsStore.ACTION_RELOAD_SETTINGS -> {
                reloadSettingsFromStore()
                resetSampledColor()
                applyOverlayStyleAndPosition()
                updateBubbleAppearance()
                deduplicator.resetFrameSignature()
                if (isRunning && !settings.manualTriggerOnly) {
                    scheduleNextCapture()
                }
                return START_NOT_STICKY
            }
            SettingsStore.ACTION_MANUAL_SCAN -> {
                if (isRunning) triggerManualScan()
                return START_NOT_STICKY
            }
            SettingsStore.ACTION_FULLSCREEN_TRANSLATE -> {
                if (isRunning) {
                    if (fullScreenOverlayView != null) {
                        translationEngine.cancelBatchRequest()
                        isFullScreenBusy = false
                        closeFullScreenOverlay()
                    } else {
                        triggerFullScreenTranslate()
                    }
                }
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra("DATA", Intent::class.java)

        if (resultCode == Activity.RESULT_OK && data != null) {
            reloadSettingsFromStore()
            resetSampledColor()
            applyOverlayStyleAndPosition()
            updateBubbleAppearance()
            setupMediaProjection(resultCode, data)
            isRunning = true
            isServiceRunning = true
            isPaused = false
            consecutiveNullFrames = 0
            DiagnosticLogStore.info(this, "Bắt đầu dịch game • Chế độ: ${settings.translationMode.label}, Vị trí: ${settings.subtitlePositionMode.label}")
            mainHandler.removeCallbacks(captureRunnable)
            if (!settings.manualTriggerOnly) {
                mainHandler.postDelayed(captureRunnable, 500)
            } else {
                showOverlayMessage("Chế độ 0% nháy: Chạm ⚡ hoặc bấm ${settings.hotkeyTranslate.label} để dịch")
            }
            scheduleBubbleFade()
        } else if (!isRunning) {
            DiagnosticLogStore.error(this, "Không nhận được quyền MediaProjection từ Activity")
            showOverlayMessage("Chưa nhận được quyền chụp màn hình!")
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isRunning) return
        DiagnosticLogStore.info(this, "Phát hiện xoay màn hình → tự động dựng lại VirtualDisplay")
        closeFullScreenOverlay()
        mainHandler.postDelayed({
            recreateVirtualDisplay()
            applyOverlayStyleAndPosition()
            deduplicator.resetFrameSignature()
        }, 250)
    }

    private fun reloadSettingsFromStore() {
        settings = SettingsStore.load(this)
        cropLeftPct = settings.cropLeftPct
        cropTopPct = settings.cropTopPct
        cropRightPct = settings.cropRightPct
        cropBottomPct = settings.cropBottomPct
    }

    private fun resetSampledColor() {
        sampledBgColor = null
        sampledIsLightBg = false
        lastSampledR = -1
        lastSampledG = -1
        lastSampledB = -1
    }

    private fun getScreenBounds(): Pair<Int, Int> {
        val bounds = windowManager.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
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
            .setContentTitle("Game Screen Translator v2.4.1 đang chạy")
            .setContentText("Chạm 🎯 khoanh/dịch • Chạm đúp 🎯 toàn màn hình • Giữ 🎯 mở menu")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // 1. Khung hiển thị phụ đề Việt-Sub
    private fun setupOverlayWindow() {
        val density = resources.displayMetrics.density
        overlayBgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(settings.bgColorInt())
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
        }

        overlayTextView = TextView(this).apply {
            text = "Chạm 🎯 khoanh vùng • Chạm đúp 🎯 dịch toàn màn hình"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = overlayBgDrawable
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        applyOverlayStyleAndPosition(addIfNeeded = true)
    }

    private fun applyOverlayColorsInPlace() {
        if (!::overlayTextView.isInitialized || !::overlayBgDrawable.isInitialized) return
        val density = resources.displayMetrics.density

        val useSmart = settings.smartColorMatch && sampledBgColor != null
        val effectiveBgColor = if (useSmart) sampledBgColor!! else settings.bgColorInt()
        val useDarkText = useSmart && sampledIsLightBg
        val effectiveTextColor = if (useDarkText) Color.parseColor("#FF111827") else settings.subtitleColor.colorInt
        val borderColor = if (useDarkText) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF")

        overlayBgDrawable.setColor(effectiveBgColor)
        overlayBgDrawable.setStroke((1f * density).toInt().coerceAtLeast(1), borderColor)
        overlayTextView.setTextColor(effectiveTextColor)
        if (useDarkText) {
            overlayTextView.setShadowLayer(2f, 0f, 1f, Color.parseColor("#66FFFFFF"))
        } else {
            overlayTextView.setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
        }
    }

    private fun applyOverlayStyleAndPosition(addIfNeeded: Boolean = false) {
        if (!::overlayTextView.isInitialized) return
        val (screenW, screenH) = getScreenBounds()
        val density = resources.displayMetrics.density

        applyOverlayColorsInPlace()
        overlayTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleFontSizeSp)
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        overlayTextView.setPadding(padH, padV, padH, padV)

        val leftPx = (screenW * cropLeftPct).toInt()
        val topPx = (screenH * cropTopPct).toInt()
        val bottomPx = (screenH * cropBottomPct).toInt()
        val widthPx = (screenW * (cropRightPct - cropLeftPct)).toInt().coerceIn(180, screenW)
        val heightPx = (bottomPx - topPx).coerceIn(56, screenH)
        val gapPx = (6 * density).toInt()

        when (settings.subtitlePositionMode) {
            SubtitlePositionMode.ABOVE_CROP -> {
                overlayTextView.minHeight = 0
                overlayTextView.maxWidth = widthPx
                overlayParams.width = widthPx
                overlayParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (topPx >= (90 * density).toInt()) {
                    overlayParams.gravity = Gravity.BOTTOM or Gravity.START
                    overlayParams.x = leftPx
                    overlayParams.y = (screenH - topPx) + gapPx
                } else {
                    overlayParams.gravity = Gravity.TOP or Gravity.START
                    overlayParams.x = leftPx
                    overlayParams.y = bottomPx + gapPx
                }
            }
            SubtitlePositionMode.COVER_CROP -> {
                overlayTextView.minHeight = heightPx
                overlayTextView.maxWidth = widthPx
                overlayParams.gravity = Gravity.TOP or Gravity.START
                overlayParams.x = leftPx
                overlayParams.y = topPx
                overlayParams.width = widthPx
                overlayParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            SubtitlePositionMode.BOTTOM_FLOATING -> {
                overlayTextView.minHeight = 0
                overlayTextView.maxWidth = (screenW * 0.90f).toInt()
                overlayParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                overlayParams.x = 0
                overlayParams.y = (36 * density).toInt()
                overlayParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                overlayParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
        }

        if (addIfNeeded) {
            try {
                windowManager.addView(overlayTextView, overlayParams)
            } catch (e: Exception) {
                DiagnosticLogStore.error(this, "Không thể gắn Overlay phụ đề: ${e.message}")
            }
        } else if (overlayTextView.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(overlayTextView, overlayParams)
            } catch (_: Exception) {
            }
        }
    }

    // Chạy trên background thread (captureHandler) để lấy mẫu màu viền hộp thoại
    private fun sampleSmartBackgroundColor(bitmap: Bitmap) {
        if (!settings.smartColorMatch) return
        val w = bitmap.width
        val h = bitmap.height
        if (w < 12 || h < 12) return

        val reds = IntArray(24)
        val greens = IntArray(24)
        val blues = IntArray(24)
        var idx = 0

        fun addSample(px: Int, py: Int) {
            if (idx >= 24) return
            val c = bitmap.getPixel(px.coerceIn(0, w - 1), py.coerceIn(0, h - 1))
            reds[idx] = Color.red(c)
            greens[idx] = Color.green(c)
            blues[idx] = Color.blue(c)
            idx++
        }

        for (i in 0 until 8) {
            val x = ((i + 0.5f) * w / 8f).toInt()
            addSample(x, 2)
            addSample(x, h - 3)
        }
        for (i in 0 until 4) {
            val y = ((i + 0.5f) * h / 4f).toInt()
            addSample(2, y)
            addSample(w - 3, y)
        }

        reds.sort()
        greens.sort()
        blues.sort()
        val medR = reds[12]
        val medG = greens[12]
        val medB = blues[12]

        if (lastSampledR >= 0) {
            val delta = abs(medR - lastSampledR) + abs(medG - lastSampledG) + abs(medB - lastSampledB)
            if (delta < 36) return
        }

        lastSampledR = medR
        lastSampledG = medG
        lastSampledB = medB

        val luma = 0.299f * medR + 0.587f * medG + 0.114f * medB
        val alpha = if (settings.coverEngSub) max(settings.bgAlphaInt(), 240) else settings.bgAlphaInt()
        sampledBgColor = Color.argb(alpha, medR, medG, medB)
        sampledIsLightBg = luma > 165f

        mainHandler.post { applyOverlayColorsInPlace() }
    }

    // 2. Nút bong bóng 🎯 / ⚡ với tính năng tự làm mờ
    private fun wakeBubble() {
        mainHandler.removeCallbacks(fadeBubbleRunnable)
        if (::floatingButton.isInitialized) {
            floatingButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140)
                .start()
        }
    }

    private fun scheduleBubbleFade() {
        mainHandler.removeCallbacks(fadeBubbleRunnable)
        if (settings.autoFadeBubble) {
            mainHandler.postDelayed(fadeBubbleRunnable, 4000L)
        } else {
            wakeBubble()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        val density = resources.displayMetrics.density
        floatingButton = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        updateBubbleAppearance()

        val sizePx = (48 * density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (140 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        var lastTapTime = 0L

        val singleTapRunnable = Runnable {
            if (settings.manualTriggerOnly) {
                triggerManualScan()
            } else {
                openCropSelectionOverlay()
            }
        }

        val longPressRunnable = Runnable {
            longPressTriggered = true
            mainHandler.removeCallbacks(singleTapRunnable)
            openQuickMenuOverlay()
        }

        floatingButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeBubble()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.removeCallbacks(singleTapRunnable)
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(floatingButton, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (!longPressTriggered && dx < 15 && dy < 15 && event.action == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime <= 280L) {
                            mainHandler.removeCallbacks(singleTapRunnable)
                            lastTapTime = 0L
                            triggerFullScreenTranslate()
                        } else {
                            lastTapTime = now
                            mainHandler.postDelayed(singleTapRunnable, 280L)
                        }
                    }
                    scheduleBubbleFade()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatingButton, params)
        } catch (e: Exception) {
            DiagnosticLogStore.error(this, "Không thể gắn nút nổi 🎯: ${e.message}")
        }
    }

    private fun updateBubbleAppearance() {
        if (!::floatingButton.isInitialized) return
        val density = resources.displayMetrics.density
        val strokeColor = when {
            isPaused -> Color.parseColor("#FFF59E0B")
            settings.manualTriggerOnly -> Color.parseColor("#FF22C55E")
            else -> Color.parseColor("#FF00D4FF")
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isPaused) Color.parseColor("#DD475569") else Color.parseColor("#DD1E293B"))
            setStroke((2f * density).toInt().coerceAtLeast(2), strokeColor)
        }
        floatingButton.background = bg
        floatingButton.text = when {
            isPaused -> "⏸"
            settings.manualTriggerOnly -> "⚡"
            else -> "🎯"
        }
    }

    // ==================== MENU NHANH TRONG GAME ====================
    private fun openQuickMenuOverlay() {
        if (quickMenuView != null || isSelectingRegion) return
        wakeBubble()
        closeFullScreenOverlay()
        closeHistoryOverlay()
        closeProfilePickerOverlay()
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#F2161B22"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#FF30363D"))
            }
        }

        fun addMenuItem(title: String, colorHex: String = "#FFF0F6FC", onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor(colorHex))
                textSize = 14f
                setPadding((12 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
                setOnClickListener {
                    closeQuickMenuOverlay()
                    onClick()
                }
            }
            container.addView(tv)
        }

        addMenuItem("🖥 Dịch toàn màn hình (Menu / Kho đồ)", "#FF00D4FF") {
            triggerFullScreenTranslate()
        }

        addMenuItem("📍 Vị trí: ${settings.subtitlePositionMode.label}", "#FF4ADE80") {
            val nextPos = when (settings.subtitlePositionMode) {
                SubtitlePositionMode.ABOVE_CROP -> SubtitlePositionMode.COVER_CROP
                SubtitlePositionMode.COVER_CROP -> SubtitlePositionMode.BOTTOM_FLOATING
                SubtitlePositionMode.BOTTOM_FLOATING -> SubtitlePositionMode.ABOVE_CROP
            }
            settings = settings.copy(subtitlePositionMode = nextPos)
            SettingsStore.save(this, settings)
            resetSampledColor()
            applyOverlayStyleAndPosition()
            deduplicator.resetFrameSignature()
            Toast.makeText(this, "Vị trí mới: ${nextPos.label}", Toast.LENGTH_SHORT).show()
        }

        addMenuItem("🎮 Profile: ${settings.activeProfileName} (đổi nhanh)", "#FFA78BFA") {
            openProfilePickerOverlay()
        }

        val logCount = DialogueHistoryStore.count(this)
        addMenuItem("📜 Lịch sử hội thoại ($logCount câu)", "#FFFDE047") {
            openDialogueHistoryOverlay()
        }

        addMenuItem(
            if (settings.manualTriggerOnly) "☑ Đang bật: 0% nháy (Bấm phím/nút)" else "☐ Bật 0% nháy (Chỉ dịch khi bấm)"
        ) {
            settings = settings.copy(manualTriggerOnly = !settings.manualTriggerOnly)
            SettingsStore.save(this, settings)
            updateBubbleAppearance()
            if (settings.manualTriggerOnly) {
                mainHandler.removeCallbacks(captureRunnable)
                Toast.makeText(this, "Đã bật 0% nháy: Chạm ⚡ hoặc bấm ${settings.hotkeyTranslate.label} để dịch", Toast.LENGTH_SHORT).show()
            } else {
                scheduleNextCapture()
                Toast.makeText(this, "Đã chuyển về quét tự động", Toast.LENGTH_SHORT).show()
            }
        }

        addMenuItem("🎯 Khoanh lại vùng Eng-Sub") {
            openCropSelectionOverlay()
        }

        addMenuItem("⚡ Chế độ: ${settings.translationMode.label} (chạm đổi)") {
            val nextMode = when (settings.translationMode) {
                TranslationMode.HYBRID -> TranslationMode.OFFLINE
                TranslationMode.OFFLINE -> TranslationMode.ONLINE
                TranslationMode.ONLINE -> TranslationMode.HYBRID
            }
            settings = settings.copy(translationMode = nextMode)
            SettingsStore.save(this, settings)
            deduplicator.resetSession()
            Toast.makeText(this, "Đã chuyển sang: ${nextMode.label}", Toast.LENGTH_SHORT).show()
        }

        if (!settings.manualTriggerOnly) {
            addMenuItem(if (isPaused) "▶ Tiếp tục dịch tự động" else "⏸ Tạm dừng dịch tự động") {
                isPaused = !isPaused
                updateBubbleAppearance()
                if (isPaused) {
                    overlayTextView.visibility = View.GONE
                    hasActiveSubtitle = false
                    lastDisplayedText = ""
                }
            }
        }

        addMenuItem("✕ Tắt hoàn toàn Overlay", "#FFEF4444") {
            stopSelf()
        }

        addMenuItem("Đóng menu", "#FF9BA6B2") {
            scheduleBubbleFade()
        }

        val params = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        quickMenuView = container
        try {
            windowManager.addView(container, params)
        } catch (_: Exception) {
            quickMenuView = null
        }
    }

    private fun closeQuickMenuOverlay() {
        quickMenuView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            quickMenuView = null
        }
    }

    // ==================== ĐỔI PROFILE GAME NGAY TRONG GAME ====================
    private fun openProfilePickerOverlay() {
        if (profilePickerView != null) return
        val density = resources.displayMetrics.density
        val profiles = GameProfileStore.listProfiles(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#F50D1117"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#FF30363D"))
            }
        }

        val title = TextView(this).apply {
            text = "🎮 Chọn Profile Game"
            setTextColor(Color.parseColor("#FF00D4FF"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        container.addView(title)

        if (profiles.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Chưa có Profile nào.\nHãy mở App chính → bấm 'Lưu' để tạo cấu hình riêng cho từng game."
                setTextColor(Color.parseColor("#FF9BA6B2"))
                textSize = 13f
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            }
            container.addView(empty)
        } else {
            for (p in profiles) {
                val isCurrent = p.name.equals(settings.activeProfileName, ignoreCase = true)
                val tv = TextView(this).apply {
                    text = if (isCurrent) "✓ ${p.name} (${p.translationStyle.label})" else "• ${p.name} (${p.translationStyle.label})"
                    setTextColor(if (isCurrent) Color.parseColor("#FF4ADE80") else Color.WHITE)
                    textSize = 14f
                    setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                    setOnClickListener {
                        settings = GameProfileStore.applyProfile(this@OverlayService, p)
                        cropLeftPct = settings.cropLeftPct
                        cropTopPct = settings.cropTopPct
                        cropRightPct = settings.cropRightPct
                        cropBottomPct = settings.cropBottomPct
                        resetSampledColor()
                        deduplicator.resetSession()
                        translationEngine.clearHistory()
                        applyOverlayStyleAndPosition()
                        updateBubbleAppearance()
                        closeProfilePickerOverlay()
                        Toast.makeText(this@OverlayService, "Đã chuyển sang Profile: ${p.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                container.addView(tv)
            }
        }

        val btnClose = TextView(this).apply {
            text = "Đóng"
            setTextColor(Color.parseColor("#FF9BA6B2"))
            textSize = 13.5f
            gravity = Gravity.END
            setPadding((10 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                closeProfilePickerOverlay()
                scheduleBubbleFade()
            }
        }
        container.addView(btnClose)

        val params = WindowManager.LayoutParams(
            (290 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        profilePickerView = container
        try {
            windowManager.addView(container, params)
        } catch (_: Exception) {
            profilePickerView = null
        }
    }

    private fun closeProfilePickerOverlay() {
        profilePickerView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            profilePickerView = null
        }
    }

    // ==================== QUÉT THỦ CÔNG ====================
    private fun triggerManualScan() {
        if (!isRunning || isSelectingRegion || isFullScreenBusy || isOcrRunning.get() || isPeeking) return
        closeQuickMenuOverlay()
        closeHistoryOverlay()
        closeProfilePickerOverlay()
        deduplicator.resetFrameSignature()
        triggerCaptureCycle(forceManual = true)
    }

    // ==================== DỊCH TOÀN MÀN HÌNH SIÊU TỐC (BACKGROUND THREAD) ====================
    private data class FullScreenBlock(
        val rect: RectF,
        val originalText: String,
        var translatedText: String
    )

    private fun triggerFullScreenTranslate() {
        if (isFullScreenBusy || isSelectingRegion) return
        synchronized(imageReaderLock) {
            if (imageReader == null) {
                Toast.makeText(this, "Chưa sẵn sàng chụp màn hình!", Toast.LENGTH_SHORT).show()
                return
            }
        }

        closeQuickMenuOverlay()
        closeHistoryOverlay()
        closeProfilePickerOverlay()
        closeFullScreenOverlay()

        isFullScreenBusy = true
        val startTimeMs = System.currentTimeMillis()
        val prevOverlayVis = overlayTextView.visibility

        drainImageReaderSafely()
        overlayTextView.visibility = View.INVISIBLE
        floatingButton.visibility = View.INVISIBLE

        captureHandler.postDelayed({
            val acquired = acquireFullBitmapSafely()
            mainHandler.post {
                floatingButton.visibility = View.VISIBLE
                if (prevOverlayVis == View.VISIBLE && hasActiveSubtitle) {
                    overlayTextView.visibility = View.VISIBLE
                }
            }

            if (acquired == null) {
                isFullScreenBusy = false
                maybeRecoverVirtualDisplay("Toàn màn hình không nhận được frame")
                mainHandler.post {
                    Toast.makeText(this, "Đang làm mới luồng chụp, bạn hãy thử bấm lại sau 1 giây!", Toast.LENGTH_SHORT).show()
                }
                return@postDelayed
            }

            val (fullBitmap, imgW, imgH) = acquired
            val (screenW, screenH) = getScreenBounds()
            val scaleX = screenW / imgW.toFloat()
            val scaleY = screenH / imgH.toFloat()

            mainHandler.post {
                showFullScreenOverlayView(emptyList(), "⚡ Đang quét & dịch toàn màn hình...")
            }

            val inputImage = InputImage.fromBitmap(fullBitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    fullBitmap.recycle()

                    val rawBlocks = mutableListOf<FullScreenBlock>()
                    val letterRegex = Regex("[A-Za-z]{2,}")

                    for (block in visionText.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val clean = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                        if (clean.length < 2 || !letterRegex.containsMatchIn(clean)) continue

                        val rect = RectF(
                            (box.left * scaleX).coerceIn(0f, screenW.toFloat()),
                            (box.top * scaleY).coerceIn(0f, screenH.toFloat()),
                            (box.right * scaleX).coerceIn(0f, screenW.toFloat()),
                            (box.bottom * scaleY).coerceIn(0f, screenH.toFloat())
                        )
                        rawBlocks.add(FullScreenBlock(rect, clean, clean))
                        if (rawBlocks.size >= 26) break
                    }

                    if (rawBlocks.isEmpty()) {
                        isFullScreenBusy = false
                        mainHandler.post {
                            closeFullScreenOverlay()
                            Toast.makeText(this, "Không tìm thấy chữ tiếng Anh trên màn hình!", Toast.LENGTH_SHORT).show()
                        }
                        return@addOnSuccessListener
                    }

                    val cacheNs = "FS|${settings.translationMode.name}|${settings.customGlossary.hashCode()}"
                    val uncachedIndices = mutableListOf<Int>()
                    val uncachedTexts = mutableListOf<String>()

                    rawBlocks.forEachIndexed { idx, blk ->
                        val cached = deduplicator.getCached(blk.originalText, cacheNs)
                        if (cached != null) {
                            blk.translatedText = cached
                        } else {
                            uncachedIndices.add(idx)
                            uncachedTexts.add(blk.originalText)
                        }
                    }

                    if (uncachedTexts.isEmpty()) {
                        val elapsed = System.currentTimeMillis() - startTimeMs
                        isFullScreenBusy = false
                        mainHandler.post {
                            showFullScreenOverlayView(
                                rawBlocks,
                                "⚡ ${rawBlocks.size} khối (${elapsed}ms • Cache) • Chạm bất kỳ đâu để đóng"
                            )
                        }
                        return@addOnSuccessListener
                    }

                    translationEngine.translateBatch(
                        texts = uncachedTexts,
                        settings = settings,
                        onSuccess = { translations, usedOffline ->
                            isFullScreenBusy = false
                            translations.forEachIndexed { i, vi ->
                                val targetIdx = uncachedIndices[i]
                                rawBlocks[targetIdx].translatedText = vi
                                deduplicator.putCached(rawBlocks[targetIdx].originalText, cacheNs, vi)
                            }
                            val elapsed = System.currentTimeMillis() - startTimeMs
                            val modeTag = if (usedOffline) "Offline" else "AI"
                            mainHandler.post {
                                if (fullScreenOverlayView != null) {
                                    showFullScreenOverlayView(
                                        rawBlocks,
                                        "⚡ ${rawBlocks.size} khối (${elapsed}ms • $modeTag) • Chạm bất kỳ đâu để đóng"
                                    )
                                }
                            }
                        },
                        onError = { err ->
                            isFullScreenBusy = false
                            mainHandler.post {
                                closeFullScreenOverlay()
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                .addOnFailureListener { e ->
                    fullBitmap.recycle()
                    isFullScreenBusy = false
                    DiagnosticLogStore.error(this, "Lỗi OCR toàn màn hình: ${e.message}")
                    mainHandler.post {
                        closeFullScreenOverlay()
                        Toast.makeText(this, "Lỗi OCR toàn màn hình: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }, 40L)
    }

    private fun showFullScreenOverlayView(blocks: List<FullScreenBlock>, statusBanner: String) {
        val existing = fullScreenOverlayView
        if (existing != null && existing.isAttachedToWindow) {
            existing.updateContent(blocks, statusBanner)
            return
        }

        val view = FullScreenTranslationView(this, blocks, statusBanner) {
            translationEngine.cancelBatchRequest()
            isFullScreenBusy = false
            closeFullScreenOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        fullScreenOverlayView = view
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            fullScreenOverlayView = null
        }
    }

    private fun closeFullScreenOverlay() {
        fullScreenOverlayView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            fullScreenOverlayView = null
        }
    }

    // ==================== LỊCH SỬ HỘI THOẠI TRONG GAME ====================
    private fun openDialogueHistoryOverlay() {
        if (historyOverlayView != null) return
        val density = resources.displayMetrics.density
        val (screenW, screenH) = getScreenBounds()
        val entries = DialogueHistoryStore.getAll(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#F50D1117"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#FF30363D"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "📜 Lịch sử hội thoại (${entries.size}/50)"
            setTextColor(Color.parseColor("#FF00D4FF"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClear = TextView(this).apply {
            text = "Xóa"
            setTextColor(Color.parseColor("#FFEF4444"))
            textSize = 13f
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                DialogueHistoryStore.clear(this@OverlayService)
                closeHistoryOverlay()
                Toast.makeText(this@OverlayService, "Đã xóa lịch sử hội thoại", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClose = TextView(this).apply {
            text = "✕ Đóng"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((10 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                closeHistoryOverlay()
                scheduleBubbleFade()
            }
        }

        header.addView(title)
        if (entries.isNotEmpty()) header.addView(btnClear)
        header.addView(btnClose)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenH * 0.55f).toInt().coerceIn((220 * density).toInt(), (480 * density).toInt())
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (entries.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Chưa có câu thoại nào được dịch.\nKhi game hiện phụ đề, lịch sử sẽ tự động lưu tại đây."
                setTextColor(Color.parseColor("#FF9BA6B2"))
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), (32 * density).toInt(), (12 * density).toInt(), (32 * density).toInt())
            }
            listContainer.addView(emptyTv)
        } else {
            for (entry in entries) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10f * density
                        setColor(Color.parseColor("#FF161B22"))
                        setStroke((1f * density).toInt(), Color.parseColor("#FF262C36"))
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = (8 * density).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Subtitle", "${entry.translatedVi}\n(${entry.sourceEn})"))
                        Toast.makeText(this@OverlayService, "Đã copy câu thoại!", Toast.LENGTH_SHORT).show()
                    }
                }

                val meta = TextView(this).apply {
                    val tag = if (entry.usedOffline) "Offline" else "AI"
                    text = "${entry.formattedTime()} • $tag  (chạm để copy)"
                    setTextColor(Color.parseColor(if (entry.usedOffline) "#FFF59E0B" else "#FF38BDF8"))
                    textSize = 11f
                }

                val tvVi = TextView(this).apply {
                    text = entry.translatedVi
                    setTextColor(Color.WHITE)
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, (3 * density).toInt(), 0, (2 * density).toInt())
                }

                val tvEn = TextView(this).apply {
                    text = entry.sourceEn
                    setTextColor(Color.parseColor("#FF8B949E"))
                    textSize = 12f
                }

                card.addView(meta)
                card.addView(tvVi)
                card.addView(tvEn)
                listContainer.addView(card)
            }
        }

        scroll.addView(listContainer)
        root.addView(scroll)

        val panelWidth = (screenW * 0.88f).toInt().coerceAtMost((440 * density).toInt())
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        historyOverlayView = root
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            historyOverlayView = null
        }
    }

    private fun closeHistoryOverlay() {
        historyOverlayView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            historyOverlayView = null
        }
    }

    // 3. Khoanh vùng Eng-Sub
    private fun openCropSelectionOverlay() {
        if (isSelectingRegion) return
        closeQuickMenuOverlay()
        closeHistoryOverlay()
        closeProfilePickerOverlay()
        closeFullScreenOverlay()
        isSelectingRegion = true
        overlayTextView.visibility = View.GONE
        hasActiveSubtitle = false
        lastDisplayedText = ""

        selectionView = CropSelectionView(this) { leftPct, topPct, rightPct, bottomPct ->
            cropLeftPct = leftPct
            cropTopPct = topPct
            cropRightPct = rightPct
            cropBottomPct = bottomPct
            SettingsStore.saveCropRegion(this, leftPct, topPct, rightPct, bottomPct)
            resetSampledColor()
            deduplicator.resetSession()

            closeCropSelectionOverlay()
            applyOverlayStyleAndPosition()
            Toast.makeText(this, "Đã lưu vùng Eng-Sub mới!", Toast.LENGTH_SHORT).show()
            if (settings.manualTriggerOnly) {
                triggerManualScan()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(selectionView, params)
        } catch (_: Exception) {
            selectionView = null
            isSelectingRegion = false
        }
    }

    private fun closeCropSelectionOverlay() {
        selectionView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            selectionView = null
        }
        isSelectingRegion = false
    }

    // ==================== MEDIA PROJECTION & WATCHDOG TỰ HỒI PHỤC ====================
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        synchronized(imageReaderLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
        mediaProjection?.stop()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                DiagnosticLogStore.warn(this@OverlayService, "Hệ thống đã thu hồi MediaProjection → dừng OverlayService")
                stopSelf()
            }
        }, mainHandler)

        recreateVirtualDisplay()
    }

    private fun recreateVirtualDisplay() {
        val projection = mediaProjection ?: return
        synchronized(imageReaderLock) {
            try {
                virtualDisplay?.release()
                imageReader?.close()
            } catch (_: Exception) {
            }

            val (screenW, screenH) = getScreenBounds()
            val width = (screenW / 2).coerceAtLeast(320)
            val height = (screenH / 2).coerceAtLeast(240)
            val density = resources.displayMetrics.densityDpi

            try {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "GameTranslatorDisplay",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
                consecutiveNullFrames = 0
                needsWakeCheck = false
            } catch (e: Exception) {
                DiagnosticLogStore.error(this, "Lỗi dựng VirtualDisplay: ${e.message}")
            }
        }
    }

    private fun maybeRecoverVirtualDisplay(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryMs < 8_000L) return
        if (now - recoveryWindowStartMs > 60_000L) {
            recoveryWindowStartMs = now
            recoveryCountInWindow = 0
        }
        if (recoveryCountInWindow >= 4) return

        lastRecoveryMs = now
        recoveryCountInWindow++
        DiagnosticLogStore.warn(this, "Watchdog tự hồi phục VirtualDisplay ($reason)")
        mainHandler.post {
            if (isRunning) {
                recreateVirtualDisplay()
            }
        }
    }

    private fun drainImageReaderSafely() {
        synchronized(imageReaderLock) {
            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun acquireFullBitmapSafely(): Triple<Bitmap, Int, Int>? {
        synchronized(imageReaderLock) {
            val reader = imageReader ?: return null
            val image = try {
                reader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                maybeRecoverVirtualDisplay("ImageReader IllegalState: ${e.message}")
                return null
            } ?: return null

            return try {
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
                Triple(fullBitmap, image.width, image.height)
            } catch (e: Exception) {
                DiagnosticLogStore.error(this, "Lỗi đọc buffer ảnh: ${e.message}")
                null
            } finally {
                image.close()
            }
        }
    }

    private fun scheduleNextCapture() {
        if (!isRunning || settings.manualTriggerOnly) return
        val delay = if (settings.coverEngSub && hasActiveSubtitle) {
            max(settings.scanIntervalMs, 1500).toLong()
        } else {
            settings.scanIntervalMs.toLong()
        }
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.postDelayed(captureRunnable, delay)
    }

    private fun triggerCaptureCycle(forceManual: Boolean) {
        val needPeek = settings.coverEngSub && overlayTextView.visibility == View.VISIBLE
        if (needPeek) {
            isPeeking = true
            drainImageReaderSafely()
            overlayTextView.visibility = View.INVISIBLE
            captureHandler.postDelayed({
                isPeeking = false
                if (!isRunning || isSelectingRegion || isFullScreenBusy) {
                    if (!forceManual) scheduleNextCapture()
                    return@postDelayed
                }
                captureAndProcessFrameOnBackground(restoreOverlayImmediately = true, forceManual = forceManual)
            }, 34L)
        } else {
            captureHandler.post {
                if (!isRunning || isSelectingRegion || isFullScreenBusy) {
                    if (!forceManual) scheduleNextCapture()
                    return@post
                }
                captureAndProcessFrameOnBackground(restoreOverlayImmediately = false, forceManual = forceManual)
            }
        }
    }

    // Chạy 100% trên captureHandler (Background Thread) → UI mượt tuyệt đối, không lag game
    private fun captureAndProcessFrameOnBackground(restoreOverlayImmediately: Boolean, forceManual: Boolean) {
        val acquired = acquireFullBitmapSafely()
        if (restoreOverlayImmediately) {
            mainHandler.post {
                if (!isSelectingRegion && hasActiveSubtitle && ::overlayTextView.isInitialized) {
                    overlayTextView.visibility = View.VISIBLE
                }
            }
        }

        if (acquired == null) {
            consecutiveNullFrames++
            if (forceManual || needsWakeCheck || (!hasActiveSubtitle && consecutiveNullFrames >= 12)) {
                maybeRecoverVirtualDisplay("Không nhận được frame (${consecutiveNullFrames} lần)")
            }
            if (!forceManual) scheduleNextCapture()
            return
        }

        consecutiveNullFrames = 0
        needsWakeCheck = false

        val (fullBitmap, imgW, imgH) = acquired
        var croppedBitmap: Bitmap? = null
        try {
            val x = (imgW * cropLeftPct).toInt().coerceIn(0, imgW - 10)
            val y = (imgH * cropTopPct).toInt().coerceIn(0, imgH - 10)
            val w = (imgW * (cropRightPct - cropLeftPct)).toInt().coerceIn(10, imgW - x)
            val h = (imgH * (cropBottomPct - cropTopPct)).toInt().coerceIn(10, imgH - y)

            croppedBitmap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
        } catch (e: Exception) {
            DiagnosticLogStore.error(this, "Lỗi cắt ảnh vùng thoại: ${e.message}")
            if (!forceManual) scheduleNextCapture()
            return
        } finally {
            fullBitmap.recycle()
        }

        if (!forceManual && settings.antiDuplicate && deduplicator.isFrameUnchanged(croppedBitmap)) {
            croppedBitmap.recycle()
            scheduleNextCapture()
            return
        }

        sampleSmartBackgroundColor(croppedBitmap)

        if (!isOcrRunning.compareAndSet(false, true)) {
            croppedBitmap.recycle()
            if (!forceManual) scheduleNextCapture()
            return
        }

        val bitmapForOcr = croppedBitmap
        val inputImage = InputImage.fromBitmap(bitmapForOcr, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                bitmapForOcr.recycle()
                isOcrRunning.set(false)
                handleRecognizedText(rawText, forceManual)
                if (!forceManual) scheduleNextCapture()
            }
            .addOnFailureListener { e ->
                bitmapForOcr.recycle()
                isOcrRunning.set(false)
                DiagnosticLogStore.error(this, "Lỗi ML Kit OCR: ${e.message}")
                showOverlayMessage("Lỗi OCR: ${e.message}")
                if (!forceManual) scheduleNextCapture()
            }
    }

    private fun handleRecognizedText(rawText: String, forceManual: Boolean) {
        if (rawText.length <= 2) {
            emptyFrameCount++
            if (forceManual || emptyFrameCount >= 2) {
                deduplicator.onEmptyFrame()
                translationEngine.cancelActiveRequest()
                hasActiveSubtitle = false
                lastDisplayedText = ""
                mainHandler.post { overlayTextView.visibility = View.GONE }
            }
            return
        }

        emptyFrameCount = 0
        val cacheNamespace = "${settings.translationMode.name}|${settings.translationStyle.name}|${settings.customGlossary.hashCode()}"
        val eval = deduplicator.evaluate(
            rawText = rawText,
            cacheNamespace = cacheNamespace,
            antiDuplicateEnabled = settings.antiDuplicate && !forceManual
        )

        when (eval.decision) {
            TextDeduplicator.Decision.SKIP_DUPLICATE,
            TextDeduplicator.Decision.WAIT_STABILIZE -> return

            TextDeduplicator.Decision.USE_CACHE -> {
                val cached = eval.cachedTranslation ?: return
                DialogueHistoryStore.add(this, rawText, cached, settings.translationMode == TranslationMode.OFFLINE)
                showOverlayMessage(cached)
            }

            TextDeduplicator.Decision.TRANSLATE -> {
                val seq = ++translationSequence
                if (!hasActiveSubtitle || forceManual) {
                    showOverlayMessage("Đang dịch...")
                }

                translationEngine.translate(
                    englishText = rawText,
                    settings = settings,
                    onSuccess = { translated, usedOffline ->
                        if (seq != translationSequence || !isRunning || isSelectingRegion) return@translate
                        deduplicator.recordTranslated(eval.normalizedText, cacheNamespace, translated)
                        DialogueHistoryStore.add(this, rawText, translated, usedOffline)
                        val display = if (usedOffline && settings.translationMode == TranslationMode.HYBRID) {
                            "$translated (Offline)"
                        } else {
                            translated
                        }
                        showOverlayMessage(display)
                    },
                    onError = { err ->
                        if (seq != translationSequence || !isRunning || isSelectingRegion) return@translate
                        showOverlayMessage(err)
                    }
                )
            }
        }
    }

    private fun showOverlayMessage(text: String) {
        mainHandler.post {
            if (!isSelectingRegion && ::overlayTextView.isInitialized) {
                if (lastDisplayedText == text && overlayTextView.visibility == View.VISIBLE) {
                    return@post
                }
                lastDisplayedText = text
                overlayTextView.text = text
                overlayTextView.visibility = View.VISIBLE
                hasActiveSubtitle = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isServiceRunning = false
        DiagnosticLogStore.info(this, "OverlayService đã tắt gọn")
        try {
            unregisterReceiver(screenWakeReceiver)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacksAndMessages(null)
        }
        if (::captureThread.isInitialized) {
            captureThread.quitSafely()
        }
        if (::translationEngine.isInitialized) {
            translationEngine.close()
        }
        textRecognizer.close()
        closeQuickMenuOverlay()
        closeHistoryOverlay()
        closeProfilePickerOverlay()
        closeFullScreenOverlay()
        closeCropSelectionOverlay()
        synchronized(imageReaderLock) {
            try {
                virtualDisplay?.release()
                imageReader?.close()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            imageReader = null
        }
        mediaProjection?.stop()
        if (::overlayTextView.isInitialized && overlayTextView.isAttachedToWindow) {
            try {
                windowManager.removeView(overlayTextView)
            } catch (_: Exception) {
            }
        }
        if (::floatingButton.isInitialized && floatingButton.isAttachedToWindow) {
            try {
                windowManager.removeView(floatingButton)
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== VIEW PHỦ DỊCH TOÀN MÀN HÌNH ====================
    private inner class FullScreenTranslationView(
        context: Context,
        private var blocks: List<FullScreenBlock>,
        private var statusBanner: String,
        private val onDismiss: () -> Unit
    ) : View(context) {

        private val density = resources.displayMetrics.density

        private val backdropPaint = Paint().apply {
            color = Color.parseColor("#55000000")
        }
        private val boxBgPaint = Paint().apply {
            color = Color.parseColor("#EE0B0F17")
            isAntiAlias = true
        }
        private val boxBorderPaint = Paint().apply {
            color = Color.parseColor("#9900D4FF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            isAntiAlias = true
        }
        private val bannerBgPaint = Paint().apply {
            color = Color.parseColor("#F0161B22")
            isAntiAlias = true
        }
        private val bannerTextPaint = Paint().apply {
            color = Color.parseColor("#FF00D4FF")
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        private val blockTextPaint = TextPaint().apply {
            color = settings.subtitleColor.colorInt
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        fun updateContent(newBlocks: List<FullScreenBlock>, newBanner: String) {
            blocks = newBlocks
            statusBanner = newBanner
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)

            val pad = 6f * density
            val corner = 8f * density

            for (blk in blocks) {
                val drawRect = RectF(
                    (blk.rect.left - pad).coerceAtLeast(4f),
                    (blk.rect.top - pad).coerceAtLeast(4f),
                    (blk.rect.right + pad).coerceAtMost(width - 4f),
                    (blk.rect.bottom + pad).coerceAtMost(height - 4f)
                )

                val contentWidth = (drawRect.width() - pad * 2).toInt().coerceAtLeast((60 * density).toInt())
                val textSizePx = (blk.rect.height() * 0.55f).coerceIn(11f * density, 18f * density)
                blockTextPaint.textSize = textSizePx

                var layout = StaticLayout.Builder
                    .obtain(blk.translatedText, 0, blk.translatedText.length, blockTextPaint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()

                if (layout.height > drawRect.height() && textSizePx > 11f * density) {
                    blockTextPaint.textSize = (textSizePx * 0.82f).coerceAtLeast(11f * density)
                    layout = StaticLayout.Builder
                        .obtain(blk.translatedText, 0, blk.translatedText.length, blockTextPaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build()
                }

                val finalBottom = max(drawRect.bottom, drawRect.top + layout.height + pad * 2)
                    .coerceAtMost(height - 4f)
                val finalRect = RectF(drawRect.left, drawRect.top, drawRect.right, finalBottom)

                canvas.drawRoundRect(finalRect, corner, corner, boxBgPaint)
                canvas.drawRoundRect(finalRect, corner, corner, boxBorderPaint)

                canvas.save()
                val textY = finalRect.top + ((finalRect.height() - layout.height) / 2f).coerceAtLeast(pad)
                canvas.translate(finalRect.left + pad, textY)
                layout.draw(canvas)
                canvas.restore()
            }

            val bannerW = min(width * 0.92f, bannerTextPaint.measureText(statusBanner) + 32f * density)
            val bannerH = 34f * density
            val bannerLeft = (width - bannerW) / 2f
            val bannerTop = 28f * density
            val bannerRect = RectF(bannerLeft, bannerTop, bannerLeft + bannerW, bannerTop + bannerH)
            canvas.drawRoundRect(bannerRect, 18f * density, 18f * density, bannerBgPaint)
            canvas.drawRoundRect(bannerRect, 18f * density, 18f * density, boxBorderPaint)
            val baseline = bannerRect.centerY() - (bannerTextPaint.descent() + bannerTextPaint.ascent()) / 2f
            canvas.drawText(statusBanner, bannerRect.centerX(), baseline, bannerTextPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onDismiss()
            }
            return true
        }
    }

    private inner class CropSelectionView(
        context: Context,
        private val onRegionSelected: (Float, Float, Float, Float) -> Unit
    ) : View(context) {

        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var isDragging = false

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#88000000")
        }
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#FF00D4FF")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            if (!isDragging) {
                canvas.drawText(
                    "Kéo ngón tay khoanh kín vùng Eng-Sub của game",
                    width / 2f,
                    height / 2f,
                    textPaint
                )
            } else {
                val rect = RectF(
                    min(startX, currentX),
                    min(startY, currentY),
                    max(startX, currentX),
                    max(startY, currentY)
                )
                canvas.drawRect(rect, clearPaint)
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

                    if (right - left > 50 && bottom - top > 40 && width > 0 && height > 0) {
                        onRegionSelected(
                            (left / width).coerceIn(0f, 0.95f),
                            (top / height).coerceIn(0f, 0.95f),
                            (right / width).coerceIn(0.05f, 1f),
                            (bottom / height).coerceIn(0.05f, 1f)
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