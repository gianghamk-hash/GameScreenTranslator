package com.giang.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
    private val CURRENT_VERSION = "v1.0"
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
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Đã bật khung dịch AI!", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Bạn cần cấp quyền chụp màn hình để dịch game!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val txtTitle = TextView(this).apply {
            text = "Game Screen Translator ($CURRENT_VERSION)"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        edtApiKey = EditText(this).apply {
            hint = "Dán Gemini API Key (AQ...)"
            setText(prefs.getString("API_KEY", ""))
            isSingleLine = true
        }

        edtModelName = EditText(this).apply {
            hint = "Tên Model (vd: gemini-3.5-flash-lite)"
            setText(prefs.getString("MODEL_NAME", "gemini-3.5-flash-lite"))
            isSingleLine = true
        }

        val btnStart = Button(this).apply {
            text = "BẬT KHUNG DỊCH AI"
            textSize = 16f
            setOnClickListener {
                saveSettings()
                checkPermissionsAndStart()
            }
        }

        val btnStop = Button(this).apply {
            text = "TẮT KHUNG DỊCH"
            textSize = 16f
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Đã tắt khung dịch", Toast.LENGTH_SHORT).show()
            }
        }

        val btnUpdate = Button(this).apply {
            text = "KIỂM TRA CẬP NHẬT (OTA)"
            textSize = 15f
            setOnClickListener { checkForOtaUpdate() }
        }

        txtStatus = TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }

        layout.addView(txtTitle)
        layout.addView(edtApiKey)
        layout.addView(edtModelName)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(btnUpdate)
        layout.addView(txtStatus)
        setContentView(layout)
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
                            if (latestTag != CURRENT_VERSION && apkUrl.isNotEmpty()) {
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