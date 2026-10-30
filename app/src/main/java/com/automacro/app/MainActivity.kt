package com.automacro.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.automacro.app.service.FloatMacroService
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQ_CODE = 2001
    }

    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnRequestShizuku: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateShizukuStatus()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateShizukuStatus()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQ_CODE) {
            updateShizukuStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 授权成功！", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (intent.getBooleanExtra("auto_start", false)) {
            btnStartService.postDelayed({
                btnStartService.performClick()
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun initViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnRequestShizuku = findViewById(R.id.btnRequestShizuku)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
    }

    private fun setupListeners() {
        // 申请悬浮窗权限
        btnRequestOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "悬浮窗权限已就绪", Toast.LENGTH_SHORT).show()
            }
        }

        // 申请 Shizuku 权限
        btnRequestShizuku.setOnClickListener {
            requestShizukuPermission()
        }

        // 启动悬浮宏
        btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isShizukuGranted()) {
                Toast.makeText(this, "请先启动并授权 Shizuku，以支持底层触控模拟", Toast.LENGTH_LONG).show()
                requestShizukuPermission()
                return@setOnClickListener
            }

            val intent = Intent(this, FloatMacroService::class.java).apply {
                action = FloatMacroService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "悬浮宏服务已启动！", Toast.LENGTH_SHORT).show()
            // 开启悬浮窗后，自动最小化主窗口
            moveTaskToBack(true)
        }

        // 停止悬浮宏
        btnStopService.setOnClickListener {
            val intent = Intent(this, FloatMacroService::class.java).apply {
                action = FloatMacroService.ACTION_STOP
            }
            stopService(intent)
            Toast.makeText(this, "悬浮宏服务已停止", Toast.LENGTH_SHORT).show()
        }

    }

    private fun updateOverlayStatus() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (granted) {
            tvOverlayStatus.text = "已授予 (正常)"
            tvOverlayStatus.setTextColor(Color.parseColor("#10B981"))
            btnRequestOverlay.isEnabled = false
            btnRequestOverlay.text = "已就绪"
        } else {
            tvOverlayStatus.text = "未授予 (点击右侧设置)"
            tvOverlayStatus.setTextColor(Color.parseColor("#EF4444"))
            btnRequestOverlay.isEnabled = true
            btnRequestOverlay.text = "去授权"
        }
    }

    private fun updateShizukuStatus() {
        if (!isShizukuRunning()) {
            tvShizukuStatus.text = "Shizuku 服务未运行 (请在 Shizuku 应用中启动)"
            tvShizukuStatus.setTextColor(Color.parseColor("#F59E0B"))
            btnRequestShizuku.isEnabled = false
            return
        }

        if (isShizukuGranted()) {
            tvShizukuStatus.text = "已授权 (正常运行中)"
            tvShizukuStatus.setTextColor(Color.parseColor("#10B981"))
            btnRequestShizuku.isEnabled = false
            btnRequestShizuku.text = "已授权"
        } else {
            tvShizukuStatus.text = "已运行但未授权给本应用"
            tvShizukuStatus.setTextColor(Color.parseColor("#EF4444"))
            btnRequestShizuku.isEnabled = true
            btnRequestShizuku.text = "申请授权"
        }
    }

    private fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestShizukuPermission() {
        if (!isShizukuRunning()) {
            Toast.makeText(this, "Shizuku 未运行，请先打开 Shizuku App 激活服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(this, "请在弹窗中允许 Shizuku 权限", Toast.LENGTH_SHORT).show()
        }
        Shizuku.requestPermission(SHIZUKU_REQ_CODE)
    }
}
