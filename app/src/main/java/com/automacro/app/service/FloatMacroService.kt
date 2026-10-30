package com.automacro.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.automacro.app.MainActivity
import com.automacro.app.R
import com.automacro.app.injection.ShizukuTouchInjector
import com.automacro.app.model.MacroPreset
import com.automacro.app.util.PresetManager
import com.automacro.app.view.TargetCrosshairView
import com.automacro.app.view.VirtualTriggerButton
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatMacroService : Service() {

    companion object {
        const val ACTION_START = "action_start_macro_service"
        const val ACTION_STOP = "action_stop_macro_service"
        private const val NOTIFICATION_CHANNEL_ID = "macro_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        // 速度系数：0.5 = 原速度的 50%（降幅一半）
        private const val SPEED_MULTIPLIER = 0.5f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var presetManager: PresetManager
    private lateinit var touchInjector: ShizukuTouchInjector

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var macroJob: Job? = null
    private var currentExecutionY = 0f

    private var activePreset: MacroPreset = MacroPreset(1, "仓位 1")
    private var isEditMode: Boolean = false

    // Floating Views
    private var triggerButton: VirtualTriggerButton? = null
    private var triggerParams: WindowManager.LayoutParams? = null

    private var crosshairView: TargetCrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    private var controlPanel: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // 贴边隐藏相关尺寸与状态
    private var isDocked: Boolean = false
    private val expandedWidthPx: Int by lazy { (320 * resources.displayMetrics.density).toInt() }
    private val dockWidthPx: Int by lazy { (18 * resources.displayMetrics.density).toInt() }
    private val dockHeightPx: Int by lazy { (42 * resources.displayMetrics.density).toInt() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        presetManager = PresetManager(this)
        touchInjector = ShizukuTouchInjector()

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val slotId = presetManager.getCurrentSlotIndex()
        activePreset = presetManager.getPreset(slotId)

        initViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun initViews() {
        initTriggerButton()
        initCrosshairView()
        initControlPanel()
        applyCurrentPreset(activePreset)
        updateModeState()
    }

    // 1. 初始化虚拟触发按键 A
    private fun initTriggerButton() {
        val sizePx = (60 * resources.displayMetrics.density).toInt()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        triggerParams = WindowManager.LayoutParams(
            sizePx, sizePx, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = activePreset.triggerX
            y = activePreset.triggerY
        }

        triggerButton = VirtualTriggerButton(
            this, windowManager, triggerParams!!,
            object : VirtualTriggerButton.Listener {
                override fun onTriggerDown() { startMacroDown() }
                override fun onTriggerUp() { stopMacroUp() }
                override fun onPositionChanged(x: Int, y: Int) {
                    activePreset.triggerX = x
                    activePreset.triggerY = y
                }
            }
        ).apply {
            isEditMode = this@FloatMacroService.isEditMode
            setCustomAlpha(activePreset.alpha)
        }

        windowManager.addView(triggerButton, triggerParams)
    }

    // 2. 初始化映射目标准星 (44dp)
    private fun initCrosshairView() {
        val sizePx = (44 * resources.displayMetrics.density).toInt()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val touchFlag = if (isEditMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        crosshairParams = WindowManager.LayoutParams(
            sizePx, sizePx, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    touchFlag,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = activePreset.targetX
            y = activePreset.targetY
        }

        crosshairView = TargetCrosshairView(
            this, windowManager, crosshairParams!!,
            object : TargetCrosshairView.Listener {
                override fun onTargetPositionChanged(x: Int, y: Int) {
                    activePreset.targetX = x
                    activePreset.targetY = y
                }
            }
        ).apply {
            isEditMode = this@FloatMacroService.isEditMode
        }

        windowManager.addView(crosshairView, crosshairParams)
    }

    // 3. 初始化悬浮控制面板
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun initControlPanel() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        controlParams = WindowManager.LayoutParams(
            expandedWidthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 120
        }

        val inflater = LayoutInflater.from(this)
        controlPanel = inflater.inflate(R.layout.layout_floating_control, null)

        val header = controlPanel!!.findViewById<View>(R.id.layoutHeader)
        val edgeDock = controlPanel!!.findViewById<View>(R.id.layoutEdgeDock)
        val btnDockEdge = controlPanel!!.findViewById<Button>(R.id.btnDockEdge)
        val btnClose = controlPanel!!.findViewById<Button>(R.id.btnClose)
        val btnModeSwitch = controlPanel!!.findViewById<Button>(R.id.btnModeSwitch)
        val btnSavePreset = controlPanel!!.findViewById<Button>(R.id.btnSavePreset)
        val btnClearPreset = controlPanel!!.findViewById<Button>(R.id.btnClearPreset)
        val sbSpeed = controlPanel!!.findViewById<SeekBar>(R.id.sbSpeed)
        val sbAlpha = controlPanel!!.findViewById<SeekBar>(R.id.sbAlpha)
        val tvSpeedLabel = controlPanel!!.findViewById<TextView>(R.id.tvSpeedLabel)
        val tvAlphaLabel = controlPanel!!.findViewById<TextView>(R.id.tvAlphaLabel)
        val tvStatusDot = controlPanel!!.findViewById<TextView>(R.id.tvStatusDot)

        if (touchInjector.isAvailable()) {
            tvStatusDot.text = "● Shizuku"
            tvStatusDot.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvStatusDot.text = "● 未运行"
            tvStatusDot.setTextColor(Color.parseColor("#EF4444"))
        }

        // 拖动顶部栏
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initialX = controlParams!!.x; initialY = controlParams!!.y; touchX = event.rawX; touchY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> { controlParams!!.x = initialX + (event.rawX - touchX).toInt(); controlParams!!.y = initialY + (event.rawY - touchY).toInt(); windowManager.updateViewLayout(controlPanel, controlParams); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    if (controlParams!!.x < 60 || (controlParams!!.x + expandedWidthPx) > (screenWidth - 60)) dockToEdge(controlParams!!.x)
                    true
                }
                else -> false
            }
        }

        btnDockEdge.setOnClickListener { dockToEdge() }

        // 贴边小把手
        var dockTouchY = 0f; var dockInitialY = 0; var isDockDragging = false
        edgeDock.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dockTouchY = event.rawY; dockInitialY = controlParams!!.y; isDockDragging = false; true }
                MotionEvent.ACTION_MOVE -> { val dy = (event.rawY - dockTouchY).toInt(); if (abs(dy) > 10) { isDockDragging = true; controlParams!!.y = dockInitialY + dy; windowManager.updateViewLayout(controlPanel, controlParams) }; true }
                MotionEvent.ACTION_UP -> { if (!isDockDragging) expandFromEdge(); true }
                else -> false
            }
        }

        btnClose.setOnClickListener { stopSelf() }
        btnModeSwitch.setOnClickListener { toggleMode() }
        updateModeState()

        // 速度调节
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speedVal = progress.coerceAtLeast(1)
                activePreset.speed = speedVal
                tvSpeedLabel.text = "压枪下滑速度: $speedVal px/步"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 透明度调节
        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alphaVal = progress.coerceAtLeast(10) / 100f
                activePreset.alpha = alphaVal
                tvAlphaLabel.text = "按键 A 透明度: $progress%"
                triggerButton?.setCustomAlpha(alphaVal)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSavePreset.setOnClickListener {
            presetManager.savePreset(activePreset)
            Toast.makeText(this, "已保存至 ${activePreset.name}", Toast.LENGTH_SHORT).show()
        }

        btnClearPreset.setOnClickListener {
            presetManager.resetPreset(activePreset.id)
            applyCurrentPreset(presetManager.getPreset(activePreset.id))
            setupSlotButtons()
            Toast.makeText(this, "已清空 ${activePreset.name}，按键与准星已归屏幕中心", Toast.LENGTH_SHORT).show()
        }

        setupSlotButtons()
        windowManager.addView(controlPanel, controlParams)
    }

    private fun dockToEdge(targetX: Int? = null) {
        val screenWidth = resources.displayMetrics.widthPixels
        val currentX = targetX ?: controlParams?.x ?: 0
        val isLeft = (currentX + (expandedWidthPx / 2)) < (screenWidth / 2)
        val finalX = if (isLeft) 0 else (screenWidth - dockWidthPx)
        isDocked = true
        controlParams?.width = dockWidthPx; controlParams?.height = dockHeightPx; controlParams?.x = finalX
        controlPanel?.findViewById<TextView>(R.id.tvDockIcon)?.text = if (isLeft) "▶" else "◀"
        controlPanel?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.GONE
        controlPanel?.findViewById<View>(R.id.layoutEdgeDock)?.visibility = View.VISIBLE
        controlPanel?.let { windowManager.updateViewLayout(it, controlParams) }
    }

    private fun expandFromEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val currentX = controlParams?.x ?: 0
        val isLeft = currentX < (screenWidth / 2)
        val finalX = if (isLeft) 20 else (screenWidth - expandedWidthPx - 20).coerceAtLeast(10)
        isDocked = false
        controlParams?.width = expandedWidthPx; controlParams?.height = WindowManager.LayoutParams.WRAP_CONTENT; controlParams?.x = finalX
        controlPanel?.findViewById<View>(R.id.layoutEdgeDock)?.visibility = View.GONE
        controlPanel?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.VISIBLE
        controlPanel?.let { windowManager.updateViewLayout(it, controlParams) }
    }

    @SuppressLint("SetTextI18n")
    private fun setupSlotButtons() {
        val container = controlPanel?.findViewById<LinearLayout>(R.id.layoutSlotsContainer) ?: return
        container.removeAllViews()
        val allPresets = presetManager.getAllPresets()
        val currentSlotId = activePreset.id
        for (preset in allPresets) {
            val btn = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "${preset.id}"
                layoutParams = LinearLayout.LayoutParams(
                    (38 * resources.displayMetrics.density).toInt(),
                    (38 * resources.displayMetrics.density).toInt()
                ).apply { setMargins(4, 0, 4, 0) }
                setPadding(0, 0, 0, 0); textSize = 12f
                if (preset.id == currentSlotId) { setBackgroundColor(Color.parseColor("#2563EB")); setTextColor(Color.WHITE) }
                else { setBackgroundColor(Color.parseColor("#334155")); setTextColor(Color.parseColor("#94A3B8")) }
                setOnClickListener { switchSlot(preset.id) }
                setOnLongClickListener {
                    // 长按清空该仓位，恢复默认值（屏幕居中）
                    presetManager.resetPreset(preset.id)
                    if (preset.id == activePreset.id) {
                        applyCurrentPreset(presetManager.getPreset(preset.id))
                    }
                    setupSlotButtons()
                    Toast.makeText(this@FloatMacroService, "仓位 ${preset.id} 已清空并复位居中", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            container.addView(btn)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun switchSlot(slotId: Int) {
        presetManager.savePreset(activePreset)
        presetManager.setCurrentSlotIndex(slotId)
        val targetPreset = presetManager.getPreset(slotId)
        applyCurrentPreset(targetPreset)
        setupSlotButtons()
        Toast.makeText(this, "已切换至 ${targetPreset.name}", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun applyCurrentPreset(preset: MacroPreset) {
        activePreset = preset
        triggerParams?.x = preset.triggerX; triggerParams?.y = preset.triggerY
        triggerButton?.let { btn -> triggerParams?.let { p -> windowManager.updateViewLayout(btn, p) }; btn.setCustomAlpha(preset.alpha) }
        crosshairParams?.x = preset.targetX; crosshairParams?.y = preset.targetY
        crosshairView?.let { view -> crosshairParams?.let { p -> windowManager.updateViewLayout(view, p) } }
        controlPanel?.let { panel ->
            panel.findViewById<Button>(R.id.btnSavePreset)?.text = "💾 保存至 ${preset.name}"
            panel.findViewById<Button>(R.id.btnClearPreset)?.text = "🗑️ 清空 ${preset.name}"
            panel.findViewById<SeekBar>(R.id.sbSpeed)?.progress = preset.speed
            panel.findViewById<TextView>(R.id.tvSpeedLabel)?.text = "压枪下滑速度: ${preset.speed} px/步"
            val alphaPercent = (preset.alpha * 100).toInt()
            panel.findViewById<SeekBar>(R.id.sbAlpha)?.progress = alphaPercent
            panel.findViewById<TextView>(R.id.tvAlphaLabel)?.text = "按键 A 透明度: $alphaPercent%"
        }
    }

    private fun toggleMode() {
        isEditMode = !isEditMode
        updateModeState()
        val tip = if (isEditMode) "已切换为: 移动摆放模式 (两键可自由拖动)" else "已切换为: 锁定执行模式 (按住A键滑动)"
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
    }

    private fun updateModeState() {
        val btnModeSwitch = controlPanel?.findViewById<Button>(R.id.btnModeSwitch)
        if (btnModeSwitch != null) {
            if (isEditMode) {
                btnModeSwitch.text = "🔓 当前: 移动摆放模式 (两键随意拖动)"
                btnModeSwitch.setBackgroundColor(Color.parseColor("#F59E0B"))
                btnModeSwitch.setTextColor(Color.BLACK)
            } else {
                btnModeSwitch.text = "🔒 当前: 锁定执行模式 (按住A滑动)"
                btnModeSwitch.setBackgroundColor(Color.parseColor("#10B981"))
                btnModeSwitch.setTextColor(Color.WHITE)
            }
        }
        crosshairParams?.let {
            if (isEditMode) it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            crosshairView?.let { v -> windowManager.updateViewLayout(v, it) }
        }
        triggerButton?.isEditMode = isEditMode
        crosshairView?.isEditMode = isEditMode
    }

    // 触觉反馈
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun startContinuousHaptic() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (vibrator.hasAmplitudeControl()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(40, 12), intArrayOf(0, 35), 0))
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(50, 15), 0))
                }
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(50, 15), 0)
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(50, 15), 0)
        }
    }

    private fun stopContinuousHaptic() {
        try { vibrator.cancel() } catch (e: Exception) { }
    }

    // ====== 宏手势核心执行逻辑 ======

    private var isMacroRunning = false
    private var pressDownTime = 0L
    private var startTargetX = 0f
    private var startTargetY = 0f

    // 单点/按住 判定阈值（ms）
    private val HOLD_THRESHOLD_MS = 220L

    // 按下 A 键：立即注入 DOWN，协程等待 220ms 后确认为按住再启动滑动
    private fun startMacroDown() {
        if (isEditMode) return
        if (isMacroRunning) return
        isMacroRunning = true
        pressDownTime = SystemClock.uptimeMillis()

        macroJob?.cancel()

        val crosshairRadius = (crosshairParams?.width ?: 0) / 2f
        startTargetX = activePreset.targetX + crosshairRadius
        startTargetY = activePreset.targetY + crosshairRadius
        currentExecutionY = startTargetY

        android.util.Log.i("FloatMacroService", "startMacroDown: ($startTargetX, $startTargetY), speed=${activePreset.speed}x${SPEED_MULTIPLIER}")

        val ok = touchInjector.startDrag(startTargetX, startTargetY)
        if (!ok) {
            val err = touchInjector.lastErrorMessage.ifEmpty { "触摸事件注入未成功" }
            Toast.makeText(this, "宏启动提示: $err", Toast.LENGTH_SHORT).show()
        }

        macroJob = serviceScope.launch(Dispatchers.Default) {
            delay(HOLD_THRESHOLD_MS)

            startContinuousHaptic()
            withContext(Dispatchers.Main) {
                triggerButton?.isFiring = true
                crosshairView?.isFiring = true
            }

            // 实际步进 = 用户设定速度 × 0.5（降幅 50%）
            val step = activePreset.speed.toFloat() * SPEED_MULTIPLIER
            val screenHeight = resources.displayMetrics.heightPixels.toFloat()

            while (isActive) {
                delay(16)
                currentExecutionY = (currentExecutionY + step).coerceAtMost(screenHeight - 10f)
                touchInjector.moveDrag(startTargetX, currentExecutionY)
            }
        }
    }

    // 松手：用真实时间差判断单点 vs 按住，避免竞态条件
    private fun stopMacroUp() {
        if (isEditMode) return
        if (!isMacroRunning) return
        isMacroRunning = false

        val duration = SystemClock.uptimeMillis() - pressDownTime

        macroJob?.cancel()
        macroJob = null

        stopContinuousHaptic()

        triggerButton?.isFiring = false
        triggerButton?.forceRelease()
        crosshairView?.isFiring = false

        if (duration < HOLD_THRESHOLD_MS) {
            android.util.Log.i("FloatMacroService", "stopMacroUp: [单点] ${duration}ms, UP at ($startTargetX, $startTargetY)")
            if (duration < 35L) {
                val remaining = 35L - duration
                serviceScope.launch(Dispatchers.Default) {
                    delay(remaining)
                    touchInjector.stopDrag(startTargetX, startTargetY)
                }
            } else {
                touchInjector.stopDrag(startTargetX, startTargetY)
            }
        } else {
            android.util.Log.i("FloatMacroService", "stopMacroUp: [按住] ${duration}ms, UP at ($startTargetX, $currentExecutionY)")
            touchInjector.stopDrag(startTargetX, currentExecutionY)
        }

        currentExecutionY = startTargetY
        triggerButton?.setCustomAlpha(activePreset.alpha)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "自动宏悬浮前台服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持悬浮窗与按键映射后台运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("自动宏服务正在运行")
            .setContentText("按住按键 A 触发宏执行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMacroUp()
        stopContinuousHaptic()
        macroJob?.cancel()
        serviceScope.cancel()
        triggerButton?.let { windowManager.removeView(it) }
        crosshairView?.let { windowManager.removeView(it) }
        controlPanel?.let { windowManager.removeView(it) }
    }
}
