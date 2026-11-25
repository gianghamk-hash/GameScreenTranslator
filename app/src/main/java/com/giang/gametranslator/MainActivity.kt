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
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {

    private val GITHUB_USER = "gianghamk-hash"
    private val GITHUB_REPO = "GameScreenTranslator"

    private lateinit var projectionManager: MediaProjectionManager

    // Profile bar
    private lateinit var txtActiveProfile: TextView
    private lateinit var btnSwitchProfile: MaterialButton
    private lateinit var btnSaveProfile: MaterialButton

    // Engine & Offline
    private lateinit var chipGroupMode: ChipGroup
    private lateinit var edtApiKey: TextInputEditText
    private lateinit var edtModelName: TextInputEditText
    private lateinit var txtOfflineStatus: TextView
    private lateinit var btnDownloadOffline: MaterialButton

    // Style & Deduplication
    private lateinit var chipGroupStyle: ChipGroup
    private lateinit var edtGlossary: TextInputEditText
    private lateinit var switchAntiDuplicate: MaterialSwitch
    private lateinit var switchContextMemory: MaterialSwitch

    // Position & Appearance
    private lateinit var chipGroupSubPos: ChipGroup
    private lateinit var switchSmartColorMatch: MaterialSwitch
    private lateinit var switchAutoFadeBubble: MaterialSwitch
    private lateinit var txtSubtitlePreview: TextView
    private lateinit var txtScanSpeedLabel: TextView
    private lateinit var sliderScanSpeed: Slider
    private lateinit var txtFontSizeLabel: TextView
    private lateinit var sliderFontSize: Slider
    private lateinit var txtBgAlphaLabel: TextView
    private lateinit var sliderBgAlpha: Slider
    private lateinit var chipGroupColor: ChipGroup

    // Gamepad & Stability
    private lateinit var switchManualTriggerOnly: MaterialSwitch
    private lateinit var chipGroupHotkeySub: ChipGroup
    private lateinit var chipGroupHotkeyFull: ChipGroup
    private lateinit var btnEnableGamepadService: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton

    private lateinit var txtStatus: TextView
    private lateinit var txtVersionBadge: TextView

    private var isOfflineDownloaded = false
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

    private fun getCurrentVersionTag(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val vName = info.versionName ?: "2.4.1"
            if (vName.startsWith("v", ignoreCase = true)) vName else "v$vName"
        } catch (_: Exception) {
            "v2.4.1"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        bindViews()
        loadSettingsIntoUi()
        setupListeners()
        refreshOfflineModelStatus()

        checkForOtaUpdate(silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshGamepadServiceButton()
        refreshBatteryOptButton()
    }

    private fun bindViews() {
        txtVersionBadge = findViewById(R.id.txtVersionBadge)
        txtVersionBadge.text = getCurrentVersionTag()

        txtActiveProfile = findViewById(R.id.txtActiveProfile)
        btnSwitchProfile = findViewById(R.id.btnSwitchProfile)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)

        chipGroupMode = findViewById(R.id.chipGroupMode)
        edtApiKey = findViewById(R.id.edtApiKey)
        edtModelName = findViewById(R.id.edtModelName)
        txtOfflineStatus = findViewById(R.id.txtOfflineStatus)
        btnDownloadOffline = findViewById(R.id.btnDownloadOffline)

        chipGroupStyle = findViewById(R.id.chipGroupStyle)
        edtGlossary = findViewById(R.id.edtGlossary)
        switchAntiDuplicate = findViewById(R.id.switchAntiDuplicate)
        switchContextMemory = findViewById(R.id.switchContextMemory)

        chipGroupSubPos = findViewById(R.id.chipGroupSubPos)
        switchSmartColorMatch = findViewById(R.id.switchSmartColorMatch)
        switchAutoFadeBubble = findViewById(R.id.switchAutoFadeBubble)
        txtSubtitlePreview = findViewById(R.id.txtSubtitlePreview)
        txtScanSpeedLabel = findViewById(R.id.txtScanSpeedLabel)
        sliderScanSpeed = findViewById(R.id.sliderScanSpeed)
        txtFontSizeLabel = findViewById(R.id.txtFontSizeLabel)
        sliderFontSize = findViewById(R.id.sliderFontSize)
        txtBgAlphaLabel = findViewById(R.id.txtBgAlphaLabel)
        sliderBgAlpha = findViewById(R.id.sliderBgAlpha)
        chipGroupColor = findViewById(R.id.chipGroupColor)

        switchManualTriggerOnly = findViewById(R.id.switchManualTriggerOnly)
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

        btnEnableGamepadService.setOnClickListener {
            Toast.makeText(
                this,
                "Hãy tìm và bật 'GameScreenTranslator' trong mục Dịch vụ đã cài đặt",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnBatteryOpt.setOnClickListener { requestIgnoreBatteryOptimizations() }

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
            btnBatteryOpt.text = "✓ ĐANG BẬT CHỐNG TẮT NỀN (KHÔNG GIỚI HẠN PIN)"
            btnBatteryOpt.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnBatteryOpt.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
        } else {
            btnBatteryOpt.text = "BẬT CHỐNG TẮT NỀN (MIỄN TỐI ƯU PIN)"
            btnBatteryOpt.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnBatteryOpt.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_amber)
        }
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
            btnEnableGamepadService.text = "✓ DỊCH VỤ PHÍM TAY CẦM ĐANG BẬT"
        } else {
            btnEnableGamepadService.text = "BẬT DỊCH VỤ PHÍM TAY CẦM (TRỢ NĂNG)"
        }
    }

    private fun showSaveProfileDialog() {
        val currentSettings = collectSettingsFromUi()
        val input = EditText(this).apply {
            hint = "VD: Final Fantasy VII, Persona 5..."
            setText(if (currentSettings.activeProfileName == "Mặc định") "" else currentSettings.activeProfileName)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Lưu Profile Game")
            .setMessage("Sẽ lưu vùng Eng-Sub hiện tại, văn phong, tên riêng và vị trí phụ đề.")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập tên game!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                GameProfileStore.saveFromSettings(this, name, currentSettings)
                txtActiveProfile.text = "🎮 $name"
                notifyOverlayReloadIfRunning()
                Toast.makeText(this, "Đã lưu Profile '$name'!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showSwitchProfileDialog() {
        val profiles = GameProfileStore.listProfiles(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Chưa có Profile nào. Hãy bấm 'Lưu' để tạo mới!", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = profiles.map { "${it.name}  •  ${it.translationStyle.label}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn Profile Game")
            .setItems(labels) { _, which ->
                val chosen = profiles[which]
                GameProfileStore.applyProfile(this, chosen)
                loadSettingsIntoUi()
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
        txtActiveProfile.text = "🎮 ${s.activeProfileName}"

        when (s.translationMode) {
            TranslationMode.HYBRID -> chipGroupMode.check(R.id.chipModeHybrid)
            TranslationMode.ONLINE -> chipGroupMode.check(R.id.chipModeOnline)
            TranslationMode.OFFLINE -> chipGroupMode.check(R.id.chipModeOffline)
        }

        edtApiKey.setText(s.apiKey)
        edtModelName.setText(s.modelName)

        when (s.translationStyle) {
            TranslationStyle.RPG_FANTASY -> chipGroupStyle.check(R.id.chipStyleRpg)
            TranslationStyle.ACTION_MODERN -> chipGroupStyle.check(R.id.chipStyleAction)
            TranslationStyle.VISUAL_NOVEL -> chipGroupStyle.check(R.id.chipStyleVn)
            TranslationStyle.LITERAL_UI -> chipGroupStyle.check(R.id.chipStyleLiteral)
        }

        edtGlossary.setText(s.customGlossary)
        switchAntiDuplicate.isChecked = s.antiDuplicate
        switchContextMemory.isChecked = s.useContextMemory

        when (s.subtitlePositionMode) {
            SubtitlePositionMode.ABOVE_CROP -> chipGroupSubPos.check(R.id.chipPosAbove)
            SubtitlePositionMode.COVER_CROP -> chipGroupSubPos.check(R.id.chipPosCover)
            SubtitlePositionMode.BOTTOM_FLOATING -> chipGroupSubPos.check(R.id.chipPosBottom)
        }

        switchSmartColorMatch.isChecked = s.smartColorMatch
        switchAutoFadeBubble.isChecked = s.autoFadeBubble
        switchManualTriggerOnly.isChecked = s.manualTriggerOnly

        sliderScanSpeed.value = s.scanIntervalMs.toFloat().coerceIn(300f, 1500f)
        sliderFontSize.value = s.subtitleFontSizeSp.coerceIn(12f, 28f)
        sliderBgAlpha.value = s.subtitleBgAlphaPct.toFloat().coerceIn(40f, 100f)

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
    }

    private fun setupListeners() {
        sliderScanSpeed.addOnChangeListener { _, _, _ -> updateSliderLabelsAndPreview() }
        sliderFontSize.addOnChangeListener { _, _, _ -> updateSliderLabelsAndPreview() }
        sliderBgAlpha.addOnChangeListener { _, _, _ -> updateSliderLabelsAndPreview() }
        chipGroupColor.setOnCheckedStateChangeListener { _, _ -> updateSliderLabelsAndPreview() }

        btnDownloadOffline.setOnClickListener {
            if (isOfflineDownloaded) {
                AlertDialog.Builder(this)
                    .setTitle("Xóa gói dịch Offline?")
                    .setMessage("Bạn có muốn xóa gói ngôn ngữ Anh → Việt trên máy để giải phóng bộ nhớ không?")
                    .setPositiveButton("Xóa") { _, _ ->
                        btnDownloadOffline.isEnabled = false
                        txtOfflineStatus.text = "Đang xóa gói Offline..."
                        TranslationEngine.deleteOfflineModel {
                            runOnUiThread {
                                btnDownloadOffline.isEnabled = true
                                refreshOfflineModelStatus()
                            }
                        }
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                btnDownloadOffline.isEnabled = false
                txtOfflineStatus.text = "Đang tải gói Anh → Việt (~30MB)..."
                TranslationEngine.downloadOfflineModel(
                    onSuccess = {
                        runOnUiThread {
                            btnDownloadOffline.isEnabled = true
                            refreshOfflineModelStatus()
                            Toast.makeText(this, "Đã tải xong gói dịch Offline!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            btnDownloadOffline.isEnabled = true
                            txtOfflineStatus.text = "Tải thất bại: $err"
                        }
                    }
                )
            }
        }
    }

    private fun refreshOfflineModelStatus() {
        TranslationEngine.isOfflineModelDownloaded { downloaded ->
            runOnUiThread {
                isOfflineDownloaded = downloaded
                if (downloaded) {
                    txtOfflineStatus.text = "Đã sẵn sàng trên máy (không cần mạng)"
                    txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                    btnDownloadOffline.text = "Xóa gói"
                } else {
                    txtOfflineStatus.text = "Chưa tải (~30MB, cần mạng lần đầu)"
                    txtOfflineStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    btnDownloadOffline.text = "Tải gói"
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

        val current = SettingsStore.load(this)
        return current.copy(
            apiKey = edtApiKey.text?.toString()?.trim() ?: "",
            modelName = edtModelName.text?.toString()?.trim()?.ifEmpty { "gemini-3.5-flash-lite" }
                ?: "gemini-3.5-flash-lite",
            translationMode = mode,
            translationStyle = style,
            customGlossary = edtGlossary.text?.toString()?.trim() ?: "",
            subtitlePositionMode = posMode,
            smartColorMatch = switchSmartColorMatch.isChecked,
            autoFadeBubble = switchAutoFadeBubble.isChecked,
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

        txtScanSpeedLabel.text = "Tốc độ quét: $scanMs ms"
        txtFontSizeLabel.text = "Cỡ chữ phụ đề: ${fontSize.toInt()} sp"
        txtBgAlphaLabel.text = "Độ đậm nền che phụ đề: $bgAlphaPct%"

        val selectedColor = when (chipGroupColor.checkedChipId) {
            R.id.chipColorYellow -> SubtitleColor.YELLOW.colorInt
            R.id.chipColorCyan -> SubtitleColor.CYAN.colorInt
            R.id.chipColorGreen -> SubtitleColor.GREEN.colorInt
            else -> SubtitleColor.WHITE.colorInt
        }

        val alpha = ((bgAlphaPct / 100f) * 255f).toInt().coerceIn(100, 255)
        val density = resources.displayMetrics.density
        val previewBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.argb(alpha, 11, 15, 23))
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
        }

        txtSubtitlePreview.background = previewBg
        txtSubtitlePreview.setTextColor(selectedColor)
        txtSubtitlePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
    }

    private fun checkPermissionsAndStart() {
        val s = collectSettingsFromUi()

        if (s.translationMode == TranslationMode.ONLINE && s.apiKeys().isEmpty()) {
            Toast.makeText(this, "Vui lòng dán Gemini API Key hoặc chuyển sang chế độ Offline!", Toast.LENGTH_LONG).show()
            return
        }

        if (s.translationMode == TranslationMode.OFFLINE && !isOfflineDownloaded) {
            Toast.makeText(this, "Vui lòng bấm 'Tải gói' Offline (Anh → Việt) trước!", Toast.LENGTH_LONG).show()
            return
        }

        if (s.translationMode == TranslationMode.HYBRID && s.apiKeys().isEmpty() && !isOfflineDownloaded) {
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