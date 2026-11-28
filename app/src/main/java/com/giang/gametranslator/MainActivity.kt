package com.giang.gametranslator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val GITHUB_USER = "gianghamk-hash"
    private val GITHUB_REPO = "GameScreenTranslator"

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var previewSpeaker: SubtitleSpeaker

    // Profile bar
    private lateinit var txtActiveProfile: TextView
    private lateinit var btnSwitchProfile: MaterialButton
    private lateinit var btnSaveProfile: MaterialButton

    // Summaries for the 6 collapsible folder headers
    private lateinit var summaryEngine: TextView
    private lateinit var summaryStyle: TextView
    private lateinit var summaryUi: TextView
    private lateinit var summaryTts: TextView
    private lateinit var summaryGamepad: TextView

    // Language, Engine & Offline
    private lateinit var chipGroupSourceLang: ChipGroup
    private lateinit var chipGroupMode: ChipGroup
    private lateinit var chipGroupAiProvider: ChipGroup
    private lateinit var edtApiKey: TextInputEditText
    private lateinit var edtModelName: TextInputEditText
    private lateinit var edtAltApiKey: TextInputEditText
    private lateinit var edtAltModelName: TextInputEditText
    private lateinit var switchHdOcrEnhance: MaterialSwitch
    private lateinit var switchAiVisionOcr: MaterialSwitch
    private lateinit var txtOfflinePackTitle: TextView
    private lateinit var txtOfflineStatus: TextView
    private lateinit var btnDownloadOffline: MaterialButton
    private lateinit var progressOfflineDownload: LinearProgressIndicator

    // Style & Deduplication
    private lateinit var chipGroupStyle: ChipGroup
    private lateinit var edtGlossary: TextInputEditText
    private lateinit var edtOcrBlacklist: TextInputEditText
    private lateinit var switchAntiDuplicate: MaterialSwitch
    private lateinit var switchContextMemory: MaterialSwitch
    private lateinit var switchChoiceFormatter: MaterialSwitch
    private lateinit var switchVerticalCjk: MaterialSwitch

    // Position & Appearance
    private lateinit var chipGroupSubPos: ChipGroup
    private lateinit var switchSmartColorMatch: MaterialSwitch
    private lateinit var switchCinemaOutline: MaterialSwitch
    private lateinit var switchAutoFitText: MaterialSwitch
    private lateinit var switchAutoFadeBubble: MaterialSwitch
    private lateinit var switchLockBubble: MaterialSwitch
    private lateinit var txtSubtitlePreview: StrokeTextView
    private lateinit var txtScanSpeedLabel: TextView
    private lateinit var sliderScanSpeed: Slider
    private lateinit var txtFontSizeLabel: TextView
    private lateinit var sliderFontSize: Slider
    private lateinit var txtBgAlphaLabel: TextView
    private lateinit var sliderBgAlpha: Slider
    private lateinit var txtAutoHideLabel: TextView
    private lateinit var sliderAutoHide: Slider
    private lateinit var chipGroupColor: ChipGroup

    // TTS Offline
    private lateinit var switchTtsEnabled: MaterialSwitch
    private lateinit var btnSelectVoice: MaterialButton
    private lateinit var btnTestVoice: MaterialButton
    private lateinit var txtTtsRateLabel: TextView
    private lateinit var sliderTtsRate: Slider
    private var selectedTtsVoiceName: String = ""

    // Gamepad & Stability
    private lateinit var switchManualTriggerOnly: MaterialSwitch
    private lateinit var switchAutoSwitchProfile: MaterialSwitch
    private lateinit var switchAutoPauseOutsideGame: MaterialSwitch
    private lateinit var chipGroupHotkeySub: ChipGroup
    private lateinit var chipGroupHotkeyFull: ChipGroup
    private lateinit var btnEnableGamepadService: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton

    private lateinit var txtStatus: TextView
    private lateinit var txtVersionBadge: TextView

    private var isOfflineDownloaded = false
    private var isDownloadingOffline = false
    private var cancelDownloadPoller: (() -> Unit)? = null
    private val httpClient = OkHttpClient()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Đã bật khung dịch game!", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Bạn cần cấp quyền chụp màn hình để dịch game!", Toast.LENGTH_SHORT).show()
        }
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val current = collectSettingsFromUi()
            SettingsStore.save(this, current)
            val json = ConfigBackupManager.exportToJson(this, current)
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Đã xuất toàn bộ cài đặt, API Key & Profile ra file JSON!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi xuất file sao lưu: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Không đọc được nội dung file")
            val result = ConfigBackupManager.importFromJson(this, raw)
            loadSettingsIntoUi()
            refreshOfflineModelStatus()
            notifyOverlayReloadIfRunning()
            Toast.makeText(
                this,
                "Đã khôi phục cấu hình & ${result.profileCount} Profile game thành công!",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi nhập file sao lưu: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentVersionTag(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val vName = info.versionName ?: "3.4"
            if (vName.startsWith("v", ignoreCase = true)) vName else "v$vName"
        } catch (_: Exception) {
            "v3.4"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        previewSpeaker = SubtitleSpeaker(this) {
            runOnUiThread { updateVoiceButtonLabel() }
        }

        bindViews()
        setupCollapsibleFolders()
        loadSettingsIntoUi()
        setupListeners()
        refreshOfflineModelStatus()

        checkForOtaUpdate(silent = true)

        if (intent?.getBooleanExtra(TranslatorTileService.EXTRA_AUTO_START, false) == true) {
            intent.removeExtra(TranslatorTileService.EXTRA_AUTO_START)
            checkPermissionsAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGamepadServiceButton()
        refreshBatteryOptButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDownloadPoller?.invoke()
        cancelDownloadPoller = null
        if (::previewSpeaker.isInitialized) {
            previewSpeaker.shutdown()
        }
    }

    private fun setupCollapsibleFolders() {
        val sections = listOf(
            Triple(R.id.headerEngine, R.id.contentEngine, R.id.arrowEngine),
            Triple(R.id.headerStyle, R.id.contentStyle, R.id.arrowStyle),
            Triple(R.id.headerUi, R.id.contentUi, R.id.arrowUi),
            Triple(R.id.headerTts, R.id.contentTts, R.id.arrowTts),
            Triple(R.id.headerGamepad, R.id.contentGamepad, R.id.arrowGamepad),
            Triple(R.id.headerTools, R.id.contentTools, R.id.arrowTools)
        )

        for ((headerId, contentId, arrowId) in sections) {
            val header = findViewById<View>(headerId)
            val content = findViewById<View>(contentId)
            val arrow = findViewById<ImageView>(arrowId)

            header.setOnClickListener {
                val expanding = content.visibility != View.VISIBLE
                content.visibility = if (expanding) View.VISIBLE else View.GONE
                arrow.animate().rotation(if (expanding) 180f else 0f).setDuration(180).start()
            }
        }
    }

    private fun bindViews() {
        txtVersionBadge = findViewById(R.id.txtVersionBadge)
        txtVersionBadge.text = getCurrentVersionTag()

        txtActiveProfile = findViewById(R.id.txtActiveProfile)
        btnSwitchProfile = findViewById(R.id.btnSwitchProfile)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)

        summaryEngine = findViewById(R.id.summaryEngine)
        summaryStyle = findViewById(R.id.summaryStyle)
        summaryUi = findViewById(R.id.summaryUi)
        summaryTts = findViewById(R.id.summaryTts)
        summaryGamepad = findViewById(R.id.summaryGamepad)

        chipGroupSourceLang = findViewById(R.id.chipGroupSourceLang)
        chipGroupMode = findViewById(R.id.chipGroupMode)
        chipGroupAiProvider = findViewById(R.id.chipGroupAiProvider)
        edtApiKey = findViewById(R.id.edtApiKey)
        edtModelName = findViewById(R.id.edtModelName)
        edtAltApiKey = findViewById(R.id.edtAltApiKey)
        edtAltModelName = findViewById(R.id.edtAltModelName)
        switchHdOcrEnhance = findViewById(R.id.switchHdOcrEnhance)
        switchAiVisionOcr = findViewById(R.id.switchAiVisionOcr)
        txtOfflinePackTitle = findViewById(R.id.txtOfflinePackTitle)
        txtOfflineStatus = findViewById(R.id.txtOfflineStatus)
        btnDownloadOffline = findViewById(R.id.btnDownloadOffline)
        progressOfflineDownload = findViewById(R.id.progressOfflineDownload)

        chipGroupStyle = findViewById(R.id.chipGroupStyle)
        edtGlossary = findViewById(R.id.edtGlossary)
        edtOcrBlacklist = findViewById(R.id.edtOcrBlacklist)
        switchAntiDuplicate = findViewById(R.id.switchAntiDuplicate)
        switchContextMemory = findViewById(R.id.switchContextMemory)
        switchChoiceFormatter = findViewById(R.id.switchChoiceFormatter)
        switchVerticalCjk = findViewById(R.id.switchVerticalCjk)

        chipGroupSubPos = findViewById(R.id.chipGroupSubPos)
        switchSmartColorMatch = findViewById(R.id.switchSmartColorMatch)
        switchCinemaOutline = findViewById(R.id.switchCinemaOutline)
        switchAutoFitText = findViewById(R.id.switchAutoFitText)
        switchAutoFadeBubble = findViewById(R.id.switchAutoFadeBubble)
        switchLockBubble = findViewById(R.id.switchLockBubble)
        txtSubtitlePreview = findViewById(R.id.txtSubtitlePreview)
        txtScanSpeedLabel = findViewById(R.id.txtScanSpeedLabel)
        sliderScanSpeed = findViewById(R.id.sliderScanSpeed)
        txtFontSizeLabel = findViewById(R.id.txtFontSizeLabel)
        sliderFontSize = findViewById(R.id.sliderFontSize)
        txtBgAlphaLabel = findViewById(R.id.txtBgAlphaLabel)
        sliderBgAlpha = findViewById(R.id.sliderBgAlpha)
        txtAutoHideLabel = findViewById(R.id.txtAutoHideLabel)
        sliderAutoHide = findViewById(R.id.sliderAutoHide)
        chipGroupColor = findViewById(R.id.chipGroupColor)

        switchTtsEnabled = findViewById(R.id.switchTtsEnabled)
        btnSelectVoice = findViewById(R.id.btnSelectVoice)
        btnTestVoice = findViewById(R.id.btnTestVoice)
        txtTtsRateLabel = findViewById(R.id.txtTtsRateLabel)
        sliderTtsRate = findViewById(R.id.sliderTtsRate)

        switchManualTriggerOnly = findViewById(R.id.switchManualTriggerOnly)
        switchAutoSwitchProfile = findViewById(R.id.switchAutoSwitchProfile)
        switchAutoPauseOutsideGame = findViewById(R.id.switchAutoPauseOutsideGame)
        chipGroupHotkeySub = findViewById(R.id.chipGroupHotkeySub)
        chipGroupHotkeyFull = findViewById(R.id.chipGroupHotkeyFull)
        btnEnableGamepadService = findViewById(R.id.btnEnableGamepadService)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)

        txtStatus = findViewById(R.id.txtStatus)

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            saveCurrentSettings()
            checkPermissionsAndStart()
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Đã tắt khung dịch", Toast.LENGTH_SHORT).show()
        }

        btnSaveProfile.setOnClickListener { showSaveProfileDialog() }
        btnSwitchProfile.setOnClickListener { showSwitchProfileDialog() }

        btnSelectVoice.setOnClickListener { showVoicePickerDialog() }
        btnTestVoice.setOnClickListener {
            val rate = sliderTtsRate.value
            if (!previewSpeaker.isReady) {
                Toast.makeText(this, "Đang khởi tạo bộ đọc tiếng Việt của Android...", Toast.LENGTH_SHORT).show()
            } else {
                previewSpeaker.speak(
                    "Chúng ta phải rời khỏi Midgar ngay đêm nay!",
                    selectedTtsVoiceName,
                    rate
                )
            }
        }

        btnEnableGamepadService.setOnClickListener {
            Toast.makeText(
                this,
                "Hãy tìm và bật 'GameScreenTranslator' trong mục Dịch vụ đã cài đặt",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimizations() }

        findViewById<MaterialButton>(R.id.btnExportBackup).setOnClickListener {
            exportBackupLauncher.launch(ConfigBackupManager.defaultBackupFilename())
        }

        findViewById<MaterialButton>(R.id.btnImportBackup).setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        findViewById<MaterialButton>(R.id.btnViewStats).setOnClickListener {
            showTranslationStatsDialog()
        }

        findViewById<MaterialButton>(R.id.btnViewHistory).setOnClickListener {
            showDialogueHistoryDialog()
        }

        findViewById<MaterialButton>(R.id.btnViewLogs).setOnClickListener {
            showDiagnosticLogsDialog()
        }

        findViewById<MaterialButton>(R.id.btnUpdate).setOnClickListener {
            checkForOtaUpdate(silent = false)
        }
    }

    private fun showVoicePickerDialog() {
        val voices = previewSpeaker.getVietnameseVoices()
        if (voices.isEmpty()) {
            Toast.makeText(
                this,
                "Máy chưa có giọng đọc tiếng Việt. Hãy kiểm tra Cài đặt → Chuyển văn bản thành giọng nói (Google TTS).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = mutableListOf("Giọng mặc định hệ thống")
        voices.forEachIndexed { idx, v ->
            labels.add(SubtitleSpeaker.friendlyVoiceLabel(v, idx))
        }

        AlertDialog.Builder(this)
            .setTitle("Chọn giọng đọc tiếng Việt (${voices.size} giọng)")
            .setItems(labels.toTypedArray()) { _, which ->
                selectedTtsVoiceName = if (which == 0) "" else voices[which - 1].name
                updateVoiceButtonLabel()
                updateFolderSummaries()
                previewSpeaker.speak("Xin chào, đây là giọng đọc phụ đề game của bạn.", selectedTtsVoiceName, sliderTtsRate.value)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun updateVoiceButtonLabel() {
        val voices = previewSpeaker.getVietnameseVoices()
        val matchIdx = voices.indexOfFirst { it.name == selectedTtsVoiceName }
        btnSelectVoice.text = if (selectedTtsVoiceName.isEmpty() || matchIdx < 0) {
            "Giọng: Mặc định (${voices.size} giọng)"
        } else {
            SubtitleSpeaker.friendlyVoiceLabel(voices[matchIdx], matchIdx)
        }
    }

    private fun selectedSourceLanguage(): SourceLanguage {
        return when (chipGroupSourceLang.checkedChipId) {
            R.id.chipLangJa -> SourceLanguage.JAPANESE
            R.id.chipLangZh -> SourceLanguage.CHINESE
            R.id.chipLangKo -> SourceLanguage.KOREAN
            else -> SourceLanguage.ENGLISH
        }
    }

    private fun updateFolderSummaries() {
        val s = collectSettingsFromUi()
        val shortMode = when (s.translationMode) {
            TranslationMode.HYBRID -> "Tự động"
            TranslationMode.ONLINE -> "Chỉ AI"
            TranslationMode.OFFLINE -> "Offline"
        }
        val shortProvider = when (s.aiProvider) {
            AiProvider.GEMINI -> "Gemini"
            AiProvider.GROQ -> "Groq"
            AiProvider.OPENROUTER -> "OpenRouter"
            AiProvider.DEEPSEEK -> "DeepSeek"
        }
        val visionTag = if (s.aiVisionOcr) " • Vision" else if (s.hdOcrEnhance) " • HD" else ""
        summaryEngine.text = "${s.sourceLanguage.shortLabel} • $shortProvider • $shortMode$visionTag"

        val shortStyle = when (s.translationStyle) {
            TranslationStyle.RPG_FANTASY -> "RPG"
            TranslationStyle.ACTION_MODERN -> "Hành động"
            TranslationStyle.VISUAL_NOVEL -> "VN"
            TranslationStyle.LITERAL_UI -> "Menu"
        }
        summaryStyle.text = if (s.antiDuplicate) "$shortStyle • Chống lặp" else shortStyle

        val shortPos = when (s.subtitlePositionMode) {
            SubtitlePositionMode.ABOVE_CROP -> "Trên Sub"
            SubtitlePositionMode.COVER_CROP -> "Đè Sub"
            SubtitlePositionMode.BOTTOM_FLOATING -> "Dưới màn"
        }
        val cinemaTag = if (s.cinemaOutlineMode) " • Viền đen" else ""
        val hideTag = if (s.autoHideSeconds > 0) " • Ẩn ${s.autoHideSeconds}s" else ""
        summaryUi.text = "$shortPos • ${s.subtitleFontSizeSp.toInt()}sp$cinemaTag$hideTag"

        val rateStr = String.format(Locale.US, "%.2fx", s.ttsSpeechRate)
        txtTtsRateLabel.text = "Tốc độ: $rateStr"
        summaryTts.text = if (s.ttsEnabled) "Bật • $rateStr" else "Tắt"

        val manualTag = if (s.manualTriggerOnly) " • 0% nháy" else ""
        summaryGamepad.text = "${s.hotkeyTranslate.label} / ${s.hotkeyFullScreen.label}$manualTag"
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "App đã được miễn tối ưu hóa pin (chạy nền ổn định)!", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Hãy vào Cài đặt → Pin → chọn Không giới hạn cho GameScreenTranslator", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshBatteryOptButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) {
            btnBatteryOpt.text = "Chống tắt: Bật"
            btnBatteryOpt.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnBatteryOpt.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            btnBatteryOpt.iconTint = ContextCompat.getColorStateList(this, R.color.accent_green)
        } else {
            btnBatteryOpt.text = "Chống tắt: Tắt"
            btnBatteryOpt.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnBatteryOpt.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_amber)
            btnBatteryOpt.iconTint = ContextCompat.getColorStateList(this, R.color.accent_amber)
        }
    }

    private fun showTranslationStatsDialog() {
        val snap = TranslationStatsStore.snapshot(this)
        AlertDialog.Builder(this)
            .setTitle("📊 Thống kê tốc độ & tiết kiệm AI")
            .setMessage(snap.formatReport())
            .setNeutralButton("Đặt lại số liệu") { _, _ ->
                TranslationStatsStore.clear(this)
                Toast.makeText(this, "Đã đặt lại thống kê về 0!", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun showDiagnosticLogsDialog() {
        val logs = DiagnosticLogStore.getAll(this)
        if (logs.isEmpty()) {
            Toast.makeText(this, "Chưa có nhật ký sự kiện nào (app đang chạy sạch)!", Toast.LENGTH_SHORT).show()
            return
        }
        val fullText = logs.joinToString("\n") { it.formatLine() }
        AlertDialog.Builder(this)
            .setTitle("Nhật ký chẩn đoán (${logs.size}/30)")
            .setMessage(fullText)
            .setPositiveButton("Copy toàn bộ") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DiagnosticLog", fullText))
                Toast.makeText(this, "Đã copy nhật ký chẩn đoán!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xóa log") { _, _ ->
                DiagnosticLogStore.clear(this)
                Toast.makeText(this, "Đã xóa nhật ký chẩn đoán", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun isGamepadAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/.GamepadHotkeyService") ||
            enabled.contains("$packageName/com.giang.gametranslator.GamepadHotkeyService")
    }

    private fun refreshGamepadServiceButton() {
        if (isGamepadAccessibilityEnabled()) {
            btnEnableGamepadService.text = "Trợ năng: Bật"
        } else {
            btnEnableGamepadService.text = "Trợ năng: Tắt"
        }
    }

    private fun showSaveProfileDialog() {
        val currentSettings = collectSettingsFromUi()
        val input = EditText(this).apply {
            hint = "VD: Final Fantasy VII, Persona 5 JP..."
            setText(if (currentSettings.activeProfileName == "Mặc định") "" else currentSettings.activeProfileName)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Lưu Profile Game")
            .setMessage("Sẽ lưu ngôn ngữ gốc, vùng thoại, văn phong, tên riêng và vị trí phụ đề. Có thể liên kết với 1 Game/Giả lập để tự đổi Profile.")
            .setView(input)
            .setPositiveButton("Lưu nhanh") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập tên game!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                GameProfileStore.saveFromSettings(this, name, currentSettings)
                txtActiveProfile.text = name
                notifyOverlayReloadIfRunning()
                Toast.makeText(this, "Đã lưu Profile '$name'!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Lưu & Liên kết Game") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập tên game!", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                showLinkGameAppPicker(name, currentSettings)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showLinkGameAppPicker(profileName: String, currentSettings: TranslatorSettings) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = ri.loadLabel(packageManager)?.toString()?.trim()?.ifEmpty { pkg } ?: pkg
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            GameProfileStore.saveFromSettings(this, profileName, currentSettings)
            txtActiveProfile.text = profileName
            Toast.makeText(this, "Đã lưu Profile '$profileName' (không tìm thấy app để liên kết)", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map { "${it.first} (${it.second})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn Game/Giả lập để tự đổi sang '$profileName'")
            .setItems(labels) { _, which ->
                val (appLabel, pkg) = apps[which]
                GameProfileStore.saveFromSettings(this, profileName, currentSettings, linkedPackageName = pkg)
                txtActiveProfile.text = profileName
                notifyOverlayReloadIfRunning()
                Toast.makeText(this, "Đã lưu '$profileName' & liên kết với $appLabel!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Bỏ qua") { _, _ ->
                GameProfileStore.saveFromSettings(this, profileName, currentSettings)
                txtActiveProfile.text = profileName
                notifyOverlayReloadIfRunning()
            }
            .show()
    }

    private fun showSwitchProfileDialog() {
        val profiles = GameProfileStore.listProfiles(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Chưa có Profile nào. Hãy bấm 'Lưu' để tạo mới!", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = profiles.map { "${it.name}  •  ${it.sourceLanguage.shortLabel} • ${it.translationStyle.label}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn Profile Game")
            .setItems(labels) { _, which ->
                val chosen = profiles[which]
                GameProfileStore.applyProfile(this, chosen)
                loadSettingsIntoUi()
                refreshOfflineModelStatus()
                notifyOverlayReloadIfRunning()
                Toast.makeText(this, "Đã áp dụng Profile '${chosen.name}'", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xóa 1 Profile") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Chọn Profile cần xóa")
                    .setItems(labels) { _, delIdx ->
                        val toDel = profiles[delIdx]
                        GameProfileStore.deleteProfile(this, toDel.name)
                        loadSettingsIntoUi()
                        Toast.makeText(this, "Đã xóa Profile '${toDel.name}'", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun notifyOverlayReloadIfRunning() {
        if (OverlayService.isServiceRunning) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = SettingsStore.ACTION_RELOAD_SETTINGS
            })
        }
    }

    private fun showDialogueHistoryDialog() {
        val entries = DialogueHistoryStore.getAll(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Chưa có lịch sử hội thoại nào!", Toast.LENGTH_SHORT).show()
            return
        }

        val items = entries.map { e ->
            val tag = if (e.usedOffline) "Offline" else "AI"
            "[${e.formattedTime()} • $tag]\n${e.translatedVi}\n(${e.sourceEn})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Lịch sử hội thoại (${entries.size}/50)")
            .setItems(items) { _, which ->
                val chosen = entries[which]
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Subtitle", "${chosen.translatedVi}\n(${chosen.sourceEn})"))
                Toast.makeText(this, "Đã copy câu thoại!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xóa tất cả") { _, _ ->
                DialogueHistoryStore.clear(this)
                Toast.makeText(this, "Đã xóa lịch sử hội thoại", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun loadSettingsIntoUi() {
        val s = SettingsStore.load(this)
        txtActiveProfile.text = s.activeProfileName

        when (s.sourceLanguage) {
            SourceLanguage.JAPANESE -> chipGroupSourceLang.check(R.id.chipLangJa)
            SourceLanguage.CHINESE -> chipGroupSourceLang.check(R.id.chipLangZh)
            SourceLanguage.KOREAN -> chipGroupSourceLang.check(R.id.chipLangKo)
            SourceLanguage.ENGLISH -> chipGroupSourceLang.check(R.id.chipLangEn)
        }

        when (s.translationMode) {
            TranslationMode.HYBRID -> chipGroupMode.check(R.id.chipModeHybrid)
            TranslationMode.ONLINE -> chipGroupMode.check(R.id.chipModeOnline)
            TranslationMode.OFFLINE -> chipGroupMode.check(R.id.chipModeOffline)
        }

        when (s.aiProvider) {
            AiProvider.GROQ -> chipGroupAiProvider.check(R.id.chipProviderGroq)
            AiProvider.OPENROUTER -> chipGroupAiProvider.check(R.id.chipProviderOpenRouter)
            AiProvider.DEEPSEEK -> chipGroupAiProvider.check(R.id.chipProviderDeepSeek)
            AiProvider.GEMINI -> chipGroupAiProvider.check(R.id.chipProviderGemini)
        }
        edtApiKey.setText(s.apiKey)
        edtModelName.setText(s.modelName)
        edtAltApiKey.setText(s.altApiKey)
        edtAltModelName.setText(s.altModelName)
        switchHdOcrEnhance.isChecked = s.hdOcrEnhance
        switchAiVisionOcr.isChecked = s.aiVisionOcr

        when (s.translationStyle) {
            TranslationStyle.RPG_FANTASY -> chipGroupStyle.check(R.id.chipStyleRpg)
            TranslationStyle.ACTION_MODERN -> chipGroupStyle.check(R.id.chipStyleAction)
            TranslationStyle.VISUAL_NOVEL -> chipGroupStyle.check(R.id.chipStyleVn)
            TranslationStyle.LITERAL_UI -> chipGroupStyle.check(R.id.chipStyleLiteral)
        }

        edtGlossary.setText(s.customGlossary)
        edtOcrBlacklist.setText(s.ocrBlacklist)
        switchAntiDuplicate.isChecked = s.antiDuplicate
        switchContextMemory.isChecked = s.useContextMemory
        switchChoiceFormatter.isChecked = s.choiceFormatter
        switchVerticalCjk.isChecked = s.verticalCjkMode

        when (s.subtitlePositionMode) {
            SubtitlePositionMode.ABOVE_CROP -> chipGroupSubPos.check(R.id.chipPosAbove)
            SubtitlePositionMode.COVER_CROP -> chipGroupSubPos.check(R.id.chipPosCover)
            SubtitlePositionMode.BOTTOM_FLOATING -> chipGroupSubPos.check(R.id.chipPosBottom)
        }

        switchSmartColorMatch.isChecked = s.smartColorMatch
        switchCinemaOutline.isChecked = s.cinemaOutlineMode
        switchAutoFitText.isChecked = s.autoFitTextSize
        switchAutoFadeBubble.isChecked = s.autoFadeBubble
        switchLockBubble.isChecked = s.lockBubblePosition

        switchTtsEnabled.isChecked = s.ttsEnabled
        selectedTtsVoiceName = s.ttsVoiceName
        sliderTtsRate.value = s.ttsSpeechRate.coerceIn(0.80f, 1.80f)
        updateVoiceButtonLabel()

        switchManualTriggerOnly.isChecked = s.manualTriggerOnly
        switchAutoSwitchProfile.isChecked = s.autoSwitchProfile
        switchAutoPauseOutsideGame.isChecked = s.autoPauseOutsideGame

        sliderScanSpeed.value = s.scanIntervalMs.toFloat().coerceIn(300f, 1500f)
        sliderFontSize.value = s.subtitleFontSizeSp.coerceIn(12f, 28f)
        sliderBgAlpha.value = s.subtitleBgAlphaPct.toFloat().coerceIn(40f, 100f)
        sliderAutoHide.value = s.autoHideSeconds.toFloat().coerceIn(0f, 15f)

        when (s.subtitleColor) {
            SubtitleColor.WHITE -> chipGroupColor.check(R.id.chipColorWhite)
            SubtitleColor.YELLOW -> chipGroupColor.check(R.id.chipColorYellow)
            SubtitleColor.CYAN -> chipGroupColor.check(R.id.chipColorCyan)
            SubtitleColor.GREEN -> chipGroupColor.check(R.id.chipColorGreen)
        }

        when (s.hotkeyTranslate) {
            GamepadHotkey.R3 -> chipGroupHotkeySub.check(R.id.chipSubR3)
            GamepadHotkey.L3 -> chipGroupHotkeySub.check(R.id.chipSubL3)
            GamepadHotkey.SELECT -> chipGroupHotkeySub.check(R.id.chipSubSelect)
            GamepadHotkey.NONE -> chipGroupHotkeySub.check(R.id.chipSubNone)
        }

        when (s.hotkeyFullScreen) {
            GamepadHotkey.L3 -> chipGroupHotkeyFull.check(R.id.chipFullL3)
            GamepadHotkey.R3 -> chipGroupHotkeyFull.check(R.id.chipFullR3)
            GamepadHotkey.SELECT -> chipGroupHotkeyFull.check(R.id.chipFullSelect)
            GamepadHotkey.NONE -> chipGroupHotkeyFull.check(R.id.chipFullNone)
        }

        updateSliderLabelsAndPreview()
        updateFolderSummaries()
    }

    private fun setupListeners() {
        sliderScanSpeed.addOnChangeListener { _, _, _ ->
            updateSliderLabelsAndPreview()
            updateFolderSummaries()
        }
        sliderFontSize.addOnChangeListener { _, _, _ ->
            updateSliderLabelsAndPreview()
            updateFolderSummaries()
        }
        sliderBgAlpha.addOnChangeListener { _, _, _ -> updateSliderLabelsAndPreview() }
        sliderAutoHide.addOnChangeListener { _, _, _ ->
            updateSliderLabelsAndPreview()
            updateFolderSummaries()
        }
        sliderTtsRate.addOnChangeListener { _, _, _ -> updateFolderSummaries() }
        chipGroupColor.setOnCheckedStateChangeListener { _, _ -> updateSliderLabelsAndPreview() }

        chipGroupSourceLang.setOnCheckedStateChangeListener { _, _ ->
            updateFolderSummaries()
            if (!isDownloadingOffline) {
                refreshOfflineModelStatus()
            }
        }
        chipGroupMode.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }
        chipGroupAiProvider.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }
        chipGroupStyle.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }
        chipGroupSubPos.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }
        chipGroupHotkeySub.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }
        chipGroupHotkeyFull.setOnCheckedStateChangeListener { _, _ -> updateFolderSummaries() }

        switchHdOcrEnhance.setOnCheckedChangeListener { _, _ -> updateFolderSummaries() }
        switchAiVisionOcr.setOnCheckedChangeListener { _, _ -> updateFolderSummaries() }
        switchAntiDuplicate.setOnCheckedChangeListener { _, _ -> updateFolderSummaries() }
        switchCinemaOutline.setOnCheckedChangeListener { _, _ ->
            updateSliderLabelsAndPreview()
            updateFolderSummaries()
        }
        switchTtsEnabled.setOnCheckedChangeListener { _, _ -> updateFolderSummaries() }
        switchManualTriggerOnly.setOnCheckedChangeListener { _, _ -> updateFolderSummaries() }

        btnDownloadOffline.setOnClickListener {
            val lang = selectedSourceLanguage()
            if (isDownloadingOffline) {
                cancelDownloadPoller?.invoke()
                cancelDownloadPoller = null
                isDownloadingOffline = false
                progressOfflineDownload.visibility = View.GONE
                refreshOfflineModelStatus()
                return@setOnClickListener
            }

            if (isOfflineDownloaded) {
                AlertDialog.Builder(this)
                    .setTitle("Xóa gói dịch Offline (${lang.shortLabel} → Việt)?")
                    .setMessage("Bạn có muốn xóa gói ngôn ngữ ${lang.vietnameseName} trên máy để giải phóng bộ nhớ không?")
                    .setPositiveButton("Xóa") { _, _ ->
                        btnDownloadOffline.isEnabled = false
                        txtOfflineStatus.text = "Đang xóa gói Offline..."
                        TranslationEngine.deleteOfflineModel(lang) {
                            runOnUiThread {
                                btnDownloadOffline.isEnabled = true
                                refreshOfflineModelStatus()
                            }
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                startOfflineDownloadWithProgress(lang)
            }
        }
    }

    private fun startOfflineDownloadWithProgress(lang: SourceLanguage) {
        isDownloadingOffline = true
        btnDownloadOffline.text = "Hủy"
        progressOfflineDownload.visibility = View.VISIBLE
        progressOfflineDownload.isIndeterminate = true
        txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        txtOfflineStatus.text = "Đang khởi tạo tải gói ${lang.shortLabel} → Việt..."

        cancelDownloadPoller?.invoke()
        cancelDownloadPoller = TranslationEngine.downloadOfflineModelWithProgress(
            context = this,
            lang = lang,
            onProgress = { pct, indeterminate, statusText ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressOfflineDownload.visibility = View.VISIBLE
                    if (indeterminate) {
                        if (!progressOfflineDownload.isIndeterminate) {
                            progressOfflineDownload.isIndeterminate = true
                        }
                    } else {
                        progressOfflineDownload.setProgressCompat(pct, true)
                    }
                    txtOfflineStatus.text = statusText
                }
            },
            onSuccess = {
                runOnUiThread {
                    isDownloadingOffline = false
                    cancelDownloadPoller = null
                    progressOfflineDownload.visibility = View.GONE
                    refreshOfflineModelStatus()
                    Toast.makeText(this, "Đã tải xong gói Offline (${lang.shortLabel} → Việt)!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { err ->
                runOnUiThread {
                    isDownloadingOffline = false
                    cancelDownloadPoller = null
                    progressOfflineDownload.visibility = View.GONE
                    btnDownloadOffline.text = "Thử lại"
                    txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                    txtOfflineStatus.text = "Tải thất bại: $err"
                }
            }
        )
    }

    private fun refreshOfflineModelStatus() {
        val lang = selectedSourceLanguage()
        txtOfflinePackTitle.text = "Gói Offline (${lang.shortLabel})"
        txtOfflineStatus.text = "Đang kiểm tra..."
        txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        TranslationEngine.isOfflineModelDownloaded(lang) { downloaded ->
            runOnUiThread {
                if (lang != selectedSourceLanguage() || isDownloadingOffline) return@runOnUiThread
                isOfflineDownloaded = downloaded
                val approxSize = if (lang == SourceLanguage.ENGLISH) "~30MB" else "~60MB"
                if (downloaded) {
                    txtOfflineStatus.text = "Đã sẵn sàng trên máy (không cần mạng)"
                    txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                    btnDownloadOffline.text = "Xóa"
                } else {
                    txtOfflineStatus.text = "Chưa tải ($approxSize, cần mạng lần đầu)"
                    txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    btnDownloadOffline.text = "Tải"
                }
            }
        }
    }

    private fun collectSettingsFromUi(): TranslatorSettings {
        val mode = when (chipGroupMode.checkedChipId) {
            R.id.chipModeOnline -> TranslationMode.ONLINE
            R.id.chipModeOffline -> TranslationMode.OFFLINE
            else -> TranslationMode.HYBRID
        }

        val style = when (chipGroupStyle.checkedChipId) {
            R.id.chipStyleAction -> TranslationStyle.ACTION_MODERN
            R.id.chipStyleVn -> TranslationStyle.VISUAL_NOVEL
            R.id.chipStyleLiteral -> TranslationStyle.LITERAL_UI
            else -> TranslationStyle.RPG_FANTASY
        }

        val posMode = when (chipGroupSubPos.checkedChipId) {
            R.id.chipPosCover -> SubtitlePositionMode.COVER_CROP
            R.id.chipPosBottom -> SubtitlePositionMode.BOTTOM_FLOATING
            else -> SubtitlePositionMode.ABOVE_CROP
        }

        val color = when (chipGroupColor.checkedChipId) {
            R.id.chipColorYellow -> SubtitleColor.YELLOW
            R.id.chipColorCyan -> SubtitleColor.CYAN
            R.id.chipColorGreen -> SubtitleColor.GREEN
            else -> SubtitleColor.WHITE
        }

        val hotkeySub = when (chipGroupHotkeySub.checkedChipId) {
            R.id.chipSubL3 -> GamepadHotkey.L3
            R.id.chipSubSelect -> GamepadHotkey.SELECT
            R.id.chipSubNone -> GamepadHotkey.NONE
            else -> GamepadHotkey.R3
        }

        val hotkeyFull = when (chipGroupHotkeyFull.checkedChipId) {
            R.id.chipFullR3 -> GamepadHotkey.R3
            R.id.chipFullSelect -> GamepadHotkey.SELECT
            R.id.chipFullNone -> GamepadHotkey.NONE
            else -> GamepadHotkey.L3
        }

        val provider = when (chipGroupAiProvider.checkedChipId) {
            R.id.chipProviderGroq -> AiProvider.GROQ
            R.id.chipProviderOpenRouter -> AiProvider.OPENROUTER
            R.id.chipProviderDeepSeek -> AiProvider.DEEPSEEK
            else -> AiProvider.GEMINI
        }

        val current = SettingsStore.load(this)
        return current.copy(
            aiProvider = provider,
            apiKey = edtApiKey.text?.toString()?.trim() ?: "",
            altApiKey = edtAltApiKey.text?.toString()?.trim() ?: "",
            modelName = edtModelName.text?.toString()?.trim()?.ifEmpty { "gemini-3.5-flash-lite" }
                ?: "gemini-3.5-flash-lite",
            altModelName = edtAltModelName.text?.toString()?.trim() ?: "",
            sourceLanguage = selectedSourceLanguage(),
            translationMode = mode,
            translationStyle = style,
            customGlossary = edtGlossary.text?.toString()?.trim() ?: "",
            ocrBlacklist = edtOcrBlacklist.text?.toString()?.trim() ?: "",
            verticalCjkMode = switchVerticalCjk.isChecked,
            hdOcrEnhance = switchHdOcrEnhance.isChecked,
            aiVisionOcr = switchAiVisionOcr.isChecked,
            autoSwitchProfile = switchAutoSwitchProfile.isChecked,
            autoPauseOutsideGame = switchAutoPauseOutsideGame.isChecked,
            subtitlePositionMode = posMode,
            smartColorMatch = switchSmartColorMatch.isChecked,
            cinemaOutlineMode = switchCinemaOutline.isChecked,
            choiceFormatter = switchChoiceFormatter.isChecked,
            lockBubblePosition = switchLockBubble.isChecked,
            autoFitTextSize = switchAutoFitText.isChecked,
            autoHideSeconds = sliderAutoHide.value.toInt(),
            autoFadeBubble = switchAutoFadeBubble.isChecked,
            ttsEnabled = switchTtsEnabled.isChecked,
            ttsVoiceName = selectedTtsVoiceName,
            ttsSpeechRate = sliderTtsRate.value,
            manualTriggerOnly = switchManualTriggerOnly.isChecked,
            hotkeyTranslate = hotkeySub,
            hotkeyFullScreen = hotkeyFull,
            antiDuplicate = switchAntiDuplicate.isChecked,
            useContextMemory = switchContextMemory.isChecked,
            scanIntervalMs = sliderScanSpeed.value.toInt(),
            subtitleFontSizeSp = sliderFontSize.value,
            subtitleBgAlphaPct = sliderBgAlpha.value.toInt(),
            subtitleColor = color
        )
    }

    private fun saveCurrentSettings() {
        val s = collectSettingsFromUi()
        SettingsStore.save(this, s)
        notifyOverlayReloadIfRunning()
    }

    private fun updateSliderLabelsAndPreview() {
        val scanMs = sliderScanSpeed.value.toInt()
        val fontSize = sliderFontSize.value
        val bgAlphaPct = sliderBgAlpha.value.toInt()

        txtScanSpeedLabel.text = "Quét: ${scanMs}ms"
        txtFontSizeLabel.text = "Cỡ chữ: ${fontSize.toInt()}sp"
        txtBgAlphaLabel.text = "Độ đậm nền: $bgAlphaPct%"
        val hideSec = sliderAutoHide.value.toInt()
        txtAutoHideLabel.text = if (hideSec <= 0) "Tự ẩn: Tắt" else "Tự ẩn: ${hideSec}s"

        val selectedColor = when (chipGroupColor.checkedChipId) {
            R.id.chipColorYellow -> SubtitleColor.YELLOW.colorInt
            R.id.chipColorCyan -> SubtitleColor.CYAN.colorInt
            R.id.chipColorGreen -> SubtitleColor.GREEN.colorInt
            else -> SubtitleColor.WHITE.colorInt
        }

        val density = resources.displayMetrics.density
        if (switchCinemaOutline.isChecked) {
            txtSubtitlePreview.background = null
            txtSubtitlePreview.outlineEnabled = true
            txtSubtitlePreview.outlineColor = Color.BLACK
            txtSubtitlePreview.outlineWidthPx = 4.5f * density
        } else {
            val alpha = ((bgAlphaPct / 100f) * 255f).toInt().coerceIn(100, 255)
            val previewBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.argb(alpha, 11, 15, 23))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
            }
            txtSubtitlePreview.outlineEnabled = false
            txtSubtitlePreview.background = previewBg
        }

        txtSubtitlePreview.setTextColor(selectedColor)
        txtSubtitlePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
    }

    private fun checkPermissionsAndStart() {
        val s = collectSettingsFromUi()

        if (s.translationMode == TranslationMode.ONLINE && !s.hasAnyAiKey()) {
            Toast.makeText(this, "Vui lòng dán Gemini API Key hoặc chuyển sang chế độ Offline!", Toast.LENGTH_LONG).show()
            return
        }

        if (s.translationMode == TranslationMode.OFFLINE && !isOfflineDownloaded) {
            Toast.makeText(this, "Vui lòng bấm 'Tải gói' Offline (${s.sourceLanguage.shortLabel} → Việt) trước!", Toast.LENGTH_LONG).show()
            return
        }

        if (s.translationMode == TranslationMode.HYBRID && !s.hasAnyAiKey() && !isOfflineDownloaded) {
            Toast.makeText(this, "Hãy nhập Gemini API Key hoặc tải gói dịch Offline!", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy bật 'Xuất hiện trên cùng' cho GameScreenTranslator", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ==================== HỆ THỐNG CẬP NHẬT OTA TỰ ĐỘNG QUA GITHUB RELEASES ====================
    private fun isNewerVersion(latestTag: String, currentTag: String): Boolean {
        val latestParts = latestTag.trim().removePrefix("v").removePrefix("V")
            .split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = currentTag.trim().removePrefix("v").removePrefix("V")
            .split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun checkForOtaUpdate(silent: Boolean = false) {
        val currentTag = getCurrentVersionTag()
        if (!silent) {
            txtStatus.text = "Đang kiểm tra bản mới trên GitHub..."
        }
        val url = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GameScreenTranslator-Android")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!silent) {
                    runOnUiThread { txtStatus.text = "Lỗi kết nối GitHub: ${e.message}" }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (!silent) {
                            runOnUiThread { txtStatus.text = "Chưa có bản Release nào (Mã ${it.code})" }
                        }
                        return
                    }
                    try {
                        val json = JSONObject(it.body?.string() ?: "")
                        val latestTag = json.getString("tag_name")
                        val changelog = json.optString("body", "Bản cập nhật mới")
                        val assets = json.getJSONArray("assets")

                        var apkUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }

                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (isNewerVersion(latestTag, currentTag) && apkUrl.isNotEmpty()) {
                                txtStatus.text = "Có bản mới: $latestTag (hiện tại: $currentTag)"
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Có bản cập nhật mới ($latestTag)")
                                    .setMessage(changelog)
                                    .setPositiveButton("Tải & Cài ngay") { _, _ -> downloadAndInstallApk(apkUrl) }
                                    .setNegativeButton("Để sau", null)
                                    .show()
                            } else if (!silent) {
                                txtStatus.text = "Bạn đang dùng bản mới nhất ($currentTag)"
                            }
                        }
                    } catch (e: Exception) {
                        if (!silent) {
                            runOnUiThread { txtStatus.text = "Lỗi đọc dữ liệu Release: ${e.message}" }
                        }
                    }
                }
            }
        })
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Hãy cấp quyền cài đặt ứng dụng rồi bấm cập nhật lại!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        txtStatus.text = "Đang tải file APK..."
        val request = Request.Builder().url(apkUrl).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { txtStatus.text = "Tải thất bại: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { txtStatus.text = "Lỗi tải file (Mã ${it.code})" }
                        return
                    }
                    try {
                        val apkFile = File(cacheDir, "update.apk")
                        it.body?.byteStream()?.use { input ->
                            FileOutputStream(apkFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        val apkUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.provider",
                            apkFile
                        )

                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                        runOnUiThread {
                            txtStatus.text = "Tải xong! Đang mở trình cài đặt..."
                            startActivity(installIntent)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { txtStatus.text = "Lỗi cài đặt: ${e.message}" }
                    }
                }
            }
        })
    }
}