package com.giang.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // CẤU HÌNH PHIÊN BẢN & GITHUB REPO CHO OTA
    private val CURRENT_VERSION: String get() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    private val GITHUB_USER = "gianghamk-hash"
    private val GITHUB_REPO = "GameScreenTranslator"

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var edtApiKey: EditText
    private lateinit var edtModelName: EditText
    private lateinit var txtStatus: TextView

    private val httpClient = OkHttpClient()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            android.os.Handler(mainLooper).postDelayed({
                val status = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("CAPTURE_STATUS", "")
                if (status == "running") {
                    txtStatus.text = "Đã bật khung dịch AI"
                    moveTaskToBack(true)
                } else {
                    txtStatus.text = status?.takeIf { it.isNotBlank() }
                        ?: "Không khởi động được khung dịch. Kiểm tra quyền xuất hiện trên cùng."
                }
            }, 1800)
        } else {
            Toast.makeText(this, "Bạn cần cấp quyền chụp màn hình để dịch game!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        setContentView(R.layout.activity_main)
        edtApiKey = findViewById(R.id.edtApiKey)
        edtModelName = findViewById(R.id.edtModelName)
        txtStatus = findViewById(R.id.txtStatus)
        findViewById<TextView>(R.id.txtVersion).text = "PHIÊN BẢN $CURRENT_VERSION"
        edtApiKey.setText(prefs.getString("API_KEY", ""))
        edtModelName.setText(prefs.getString("MODEL_NAME", "gemini-3.5-flash-lite"))
        val style = findViewById<android.widget.Spinner>(R.id.styleSpinner)
        style.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, GameStyle.entries.map { it.label })
        style.setSelection(GameStyle.entries.indexOfFirst { it.name == prefs.getString("STYLE", "") }.coerceAtLeast(0))
        val profile = findViewById<EditText>(R.id.edtGameProfile)
        profile.setText(prefs.getString("GAME_PROFILE", "Mặc định"))
        val smart = findViewById<android.widget.CheckBox>(R.id.smartDialogue)
        smart.isChecked = prefs.getBoolean("SMART_DIALOGUE", true)
        val hybrid = findViewById<android.widget.CheckBox>(R.id.hybridTranslation)
        hybrid.isChecked = prefs.getBoolean("HYBRID", false)
        findViewById<Button>(R.id.btnClearMemory).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Xóa bộ nhớ bản dịch của mọi game?")
                .setMessage("Dừng dịch trước khi xóa. Không ảnh hưởng API Key hoặc OTA.")
                .setPositiveButton("Xóa") { _, _ ->
                    stopService(Intent(this, OverlayService::class.java))
                    TranslationMemory.clearAll(this)
                    txtStatus.text = "Đã xóa cache và lịch sử ngữ cảnh"
                }.setNegativeButton("Hủy", null).show()
        }
        val size = findViewById<android.widget.SeekBar>(R.id.sizeSlider)
        size.progress = prefs.getInt("SUBTITLE_SIZE", 18) - 12
        val alpha = findViewById<android.widget.SeekBar>(R.id.alphaSlider)
        alpha.progress = prefs.getInt("SUBTITLE_ALPHA", 204) - 80
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.edit().putString("STYLE", GameStyle.entries[style.selectedItemPosition].name)
                .putString("GAME_PROFILE", profile.text.toString().trim().take(80).ifBlank { "Mặc định" })
                .putBoolean("SMART_DIALOGUE", smart.isChecked).putBoolean("HYBRID", hybrid.isChecked)
                .putInt("SUBTITLE_SIZE", size.progress + 12).putInt("SUBTITLE_ALPHA", alpha.progress + 80).apply()
            saveSettings()
            checkPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            txtStatus.text = "Đã dừng dịch"
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkForOtaUpdate() }
        checkForOtaUpdate()
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit()
            .putString("API_KEY", edtApiKey.text.toString().trim())
            .putString("MODEL_NAME", edtModelName.text.toString().trim())
            .apply()
    }

    private fun checkPermissionsAndStart() {
        if (edtApiKey.text.toString().trim().isEmpty()) {
            Toast.makeText(this, "Vui lòng dán Gemini API Key trước!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!edtModelName.text.toString().trim().matches(Regex("(models/)?[A-Za-z0-9._-]+"))) {
            Toast.makeText(this, "Tên model không hợp lệ", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            // Notification permission is optional for starting a foreground service.
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy bật 'Xuất hiện trên cùng' cho GameScreenTranslator", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        // OCR and draggable controls use whole-display coordinates.
        val captureIntent = if (android.os.Build.VERSION.SDK_INT >= 34)
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else projectionManager.createScreenCaptureIntent()
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().remove("CAPTURE_STATUS").apply()
        screenCaptureLauncher.launch(captureIntent)
    }

    // ==================== HỆ THỐNG CẬP NHẬT OTA QUA GITHUB RELEASES ====================
    private fun checkForOtaUpdate() {
        txtStatus.text = "Đang kiểm tra bản mới trên GitHub..."
        val url = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GameScreenTranslator-Android")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { txtStatus.text = "Lỗi kết nối GitHub: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { txtStatus.text = "Chưa có bản Release nào (Mã ${it.code})" }
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
                            if (VersionOrder.isNewer(latestTag, CURRENT_VERSION) && apkUrl.isNotEmpty()) {
                                txtStatus.text = "Có bản mới: $latestTag"
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Có bản cập nhật mới ($latestTag)")
                                    .setMessage(changelog)
                                    .setPositiveButton("Tải & Cài ngay") { _, _ -> downloadAndInstallApk(apkUrl) }
                                    .setNegativeButton("Để sau", null)
                                    .show()
                            } else {
                                txtStatus.text = "Bạn đang dùng bản mới nhất ($CURRENT_VERSION)"
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { txtStatus.text = "Lỗi đọc dữ liệu Release: ${e.message}" }
                    }
                }
            }
        })
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        // Kiểm tra quyền cài ứng dụng không rõ nguồn gốc
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
                        require(it.body != null) { "APK rỗng" }
                        it.body?.byteStream()?.use { input ->
                            FileOutputStream(apkFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                        val installed = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        require(archive != null && archive.packageName == packageName && archive.longVersionCode > installed.longVersionCode) { "APK không đúng ứng dụng hoặc không mới hơn" }
                        require(archive.signingInfo?.apkContentsSigners?.toSet() == installed.signingInfo?.apkContentsSigners?.toSet() && archive.signingInfo != null) { "Chữ ký APK không khớp bản đã cài" }
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
