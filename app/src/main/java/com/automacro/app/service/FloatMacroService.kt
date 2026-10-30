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
    }

    private lateinit var windowManager: WindowManager
    private lateinit var presetManager: PresetManager
    private lateinit var touchInjector: ShizukuTouchInjector

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var macroJob: Job? = null
    private var currentExecutionY = 0f

    private var activePreset: MacroPreset = MacroPreset(1, "仓位 1")
    private var isEditMode: Boolean = false // 默认进入【工作锁定模式】，按住A键直接触发宏

    // Floating Views
    private var triggerButton: VirtualTriggerButton? = null
    private var triggerParams: WindowManager.LayoutParams? = null

    private var crosshairView: TargetCrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    private var controlPanel: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // 贴边隐藏相关尺寸与状态 (迷你纤细版贴边键: 18dp x 42dp)
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

    // 1. 初始化虚拟触发按键 A (游戏按键风格, 60dp)
    private fun initTriggerButton() {
        val sizePx = (60 * resources.displayMetrics.density).toInt()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        triggerParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
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
            this,
            windowManager,
            triggerParams!!,
            object : VirtualTriggerButton.Listener {
                override fun onTriggerDown() {
                    startMacroDown()
                }

                override fun onTriggerUp() {
                    stopMacroUp()
                }

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

    // 2. 初始化映射目标按键 (比按键 A 小 1 号: 44dp, 中心十字准星, 可直接拖动)
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
            sizePx,
            sizePx,
            layoutType,
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
            this,
            windowManager,
            crosshairParams!!,
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

    // 3. 初始化悬浮控制面板 (支持贴边隐藏与点击唤出)
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun initControlPanel() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        controlParams = WindowManager.LayoutParams(
            expandedWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        val sbSpeed = controlPanel!!.findViewById<SeekBar>(R.id.sbSpeed)
        val sbAlpha = controlPanel!!.findViewById<SeekBar>(R.id.sbAlpha)
        val tvSpeedLabel = controlPanel!!.findViewById<TextView>(R.id.tvSpeedLabel)
        val tvAlphaLabel = controlPanel!!.findViewById<TextView>(R.id.tvAlphaLabel)
        val tvStatusDot = controlPanel!!.findViewById<TextView>(R.id.tvStatusDot)

        // 状态检查
        if (touchInjector.isAvailable()) {
            tvStatusDot.text = "● Shizuku"
            tvStatusDot.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvStatusDot.text = "● 未运行"
            tvStatusDot.setTextColor(Color.parseColor("#EF4444"))
        }

        // 支持拖拽控制台顶部条：靠近边缘时自动贴边隐藏
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams!!.x
                    initialY = controlParams!!.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    controlParams!!.x = initialX + (event.rawX - touchX).toInt()
                    controlParams!!.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(controlPanel, controlParams)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    // 拖动至距左右屏幕边缘 60px 以内时自动贴边隐藏
                    if (controlParams!!.x < 60 || (controlParams!!.x + expandedWidthPx) > (screenWidth - 60)) {
                        dockToEdge(controlParams!!.x)
                    }
                    true
                }
                else -> false
            }
        }

        // 点击贴边隐藏按钮
        btnDockEdge.setOnClickListener {
            dockToEdge()
        }

        // 贴边小把手：点击唤出完整面板，按住可上下滑动位置
        var dockTouchY = 0f
        var dockInitialY = 0
        var isDockDragging = false
        edgeDock.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dockTouchY = event.rawY
                    dockInitialY = controlParams!!.y
                    isDockDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - dockTouchY).toInt()
                    if (abs(dy) > 10) {
                        isDockDragging = true
                        controlParams!!.y = dockInitialY + dy
                        windowManager.updateViewLayout(controlPanel, controlParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDockDragging) {
                        expandFromEdge()
                    }
                    true
                }
                else -> false
            }
        }

        // 关闭服务
        btnClose.setOnClickListener {
            stopSelf()
        }

        // 模式切换
        btnModeSwitch.setOnClickListener {
            toggleMode()
        }
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

        // 保存当前仓位按钮
        btnSavePreset.setOnClickListener {
            presetManager.savePreset(activePreset)
            Toast.makeText(this, "已保存至 ${activePreset.name}", Toast.LENGTH_SHORT).show()
        }

        // 加载 10 个预设仓位按钮
        setupSlotButtons()

        windowManager.addView(controlPanel, controlParams)
    }

    // 贴边隐藏到最近的屏幕边缘
    private fun dockToEdge(targetX: Int? = null) {
        val screenWidth = resources.displayMetrics.widthPixels
        val currentX = targetX ?: controlParams?.x ?: 0
        val isLeft = (currentX + (expandedWidthPx / 2)) < (screenWidth / 2)
        val finalX = if (isLeft) 0 else (screenWidth - dockWidthPx)

        isDocked = true
        controlParams?.width = dockWidthPx
        controlParams?.height = dockHeightPx
        controlParams?.x = finalX

        controlPanel?.findViewById<TextView>(R.id.tvDockIcon)?.text = if (isLeft) "▶" else "◀"
        controlPanel?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.GONE
        controlPanel?.findViewById<View>(R.id.layoutEdgeDock)?.visibility = View.VISIBLE
        controlPanel?.let { windowManager.updateViewLayout(it, controlParams) }
    }

    // 从屏幕边缘唤出完整控制台
    private fun expandFromEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val currentX = controlParams?.x ?: 0
        val isLeft = currentX < (screenWidth / 2)
        val finalX = if (isLeft) 20 else (screenWidth - expandedWidthPx - 20).coerceAtLeast(10)

        isDocked = false
        controlParams?.width = expandedWidthPx
        controlParams?.height = WindowManager.LayoutParams.WRAP_CONTENT
        controlParams?.x = finalX

        controlPanel?.findViewById<View>(R.id.layoutEdgeDock)?.visibility = View.GONE
        controlPanel?.findViewById<View>(R.id.layoutExpanded)?.visibility = View.VISIBLE
        controlPanel?.let { windowManager.updateViewLayout(it, controlParams) }
    }

    // 生成 10 个预设仓位横向按钮
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
                ).apply {
                    setMargins(4, 0, 4, 0)
                }
                setPadding(0, 0, 0, 0)
                textSize = 12f

                if (preset.id == currentSlotId) {
                    setBackgroundColor(Color.parseColor("#2563EB"))
                    setTextColor(Color.WHITE)
                } else {
                    setBackgroundColor(Color.parseColor("#334155"))
                    setTextColor(Color.parseColor("#94A3B8"))
                }

                setOnClickListener {
                    switchSlot(preset.id)
                }
            }
            container.addView(btn)
        }
    }

    // 切换预设仓位
    @SuppressLint("SetTextI18n")
    private fun switchSlot(slotId: Int) {
        // 先保存当前仓位
        presetManager.savePreset(activePreset)

        presetManager.setCurrentSlotIndex(slotId)
        val targetPreset = presetManager.getPreset(slotId)
        applyCurrentPreset(targetPreset)

        setupSlotButtons()
        Toast.makeText(this, "已切换至 ${targetPreset.name}", Toast.LENGTH_SHORT).show()
    }

    // 应用指定预设参数与移动按键位置
    @SuppressLint("SetTextI18n")
    private fun applyCurrentPreset(preset: MacroPreset) {
        activePreset = preset

        // 移动虚拟按键 A
        triggerParams?.x = preset.triggerX
        triggerParams?.y = preset.triggerY
        triggerButton?.let { btn ->
            triggerParams?.let { p -> windowManager.updateViewLayout(btn, p) }
            btn.setCustomAlpha(preset.alpha)
        }

        // 移动映射目标准星
        crosshairParams?.x = preset.targetX
        crosshairParams?.y = preset.targetY
        crosshairView?.let { view ->
            crosshairParams?.let { p -> windowManager.updateViewLayout(view, p) }
        }

        // 同步面板控件状态
        controlPanel?.let { panel ->
            panel.findViewById<Button>(R.id.btnSavePreset)?.text = "💾 保存当前摆放与速度至 ${preset.name}"
            val sbSpeed = panel.findViewById<SeekBar>(R.id.sbSpeed)
            sbSpeed?.progress = preset.speed
            panel.findViewById<TextView>(R.id.tvSpeedLabel)?.text = "压枪下滑速度: ${preset.speed} px/步"

            val sbAlpha = panel.findViewById<SeekBar>(R.id.sbAlpha)
            val tvAlphaLabel = panel.findViewById<TextView>(R.id.tvAlphaLabel)
            val alphaPercent = (preset.alpha * 100).toInt()
            sbAlpha?.progress = alphaPercent
            tvAlphaLabel?.text = "按键 A 透明度: $alphaPercent%"
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

        // 更新准星的穿透属性：工作模式完全穿透，移动模式接受触摸
        crosshairParams?.let {
            if (isEditMode) {
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            crosshairView?.let { v -> windowManager.updateViewLayout(v, it) }
        }

        triggerButton?.isEditMode = isEditMode
        crosshairView?.isEditMode = isEditMode
    }

    // 触觉反馈系统 (微震动持续反馈)
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
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
                    // 节拍：间隔 40ms，微震 12ms，振幅 35 (高质感机械阻尼齿轮微震动，持续不断)
                    val timings = longArrayOf(40, 12)
                    val amplitudes = intArrayOf(0, 35)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                } else {
                    val timings = longArrayOf(50, 15)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, 0))
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
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ====== 宏手势核心执行逻辑（1. 单击=原点单击；2. 按住=持续向下滑动压枪；松手=立刻抬起并复位） ======

    private var isMacroRunning = false
    private var isHoldingConfirmed = false
    private var pressDownTime = 0L
    private var startTargetX = 0f
    private var startTargetY = 0f

    // 1. 按下按键 A：映射目标键原位注入 DOWN。短按抬起（<220ms）确认为单点，击发一下立即松手；长按（≥220ms）确认为按住，启动持续向下滑动压枪
    private fun startMacroDown() {
        if (isEditMode) return
        if (isMacroRunning) return
        isMacroRunning = true
        isHoldingConfirmed = false
        pressDownTime = SystemClock.uptimeMillis()

        macroJob?.cancel()

        // 映射目标按键中心起始坐标
        val crosshairRadius = (crosshairParams?.width ?: 0) / 2f
        startTargetX = activePreset.targetX + crosshairRadius
        startTargetY = activePreset.targetY + crosshairRadius
        currentExecutionY = startTargetY

        android.util.Log.i("FloatMacroService", "startMacroDown: MappedTarget start=($startTargetX, $startTargetY), speed=${activePreset.speed}")

        // 在映射目标键起始位置立即注入 DOWN（保证首发子弹零延迟即刻击发！）
        val ok = touchInjector.startDrag(startTargetX, startTargetY)
        if (!ok) {
            val err = (touchInjector as? ShizukuTouchInjector)?.lastErrorMessage?.ifEmpty { "触摸事件注入未成功" } ?: "注入异常"
            Toast.makeText(this, "宏启动提示: $err", Toast.LENGTH_SHORT).show()
        }

        // 长按判定：门限定为 220ms
        // - 若在 220ms 内抬起：属于【单点】，stopMacroUp 立即取消协程，原位释放 UP，完成单发击发并立即松手！
        // - 若按住超过 220ms：确认为【按住】，启动微震动，开始持续平滑向下滑动压枪！
        macroJob = serviceScope.launch(Dispatchers.Default) {
            delay(220) // 220ms 严谨区分单点与按住

            isHoldingConfirmed = true

            // 确认为按住状态：启动齿轮微震动与视觉流光反馈
            startContinuousHaptic()
            withContext(Dispatchers.Main) {
                triggerButton?.isFiring = true
                crosshairView?.isFiring = true
            }

            val step = activePreset.speed.toFloat()
            val screenHeight = resources.displayMetrics.heightPixels.toFloat()

            // 只有持续向下滑动
            while (isActive) {
                delay(16) // 约 60 帧每秒平滑步进
                currentExecutionY = (currentExecutionY + step).coerceAtMost(screenHeight - 10f)
                touchInjector.moveDrag(startTargetX, currentExecutionY)
            }
        }
    }

    // 2. 放手：单点则原位释放松手，按住则刹车抬起并瞬间复位
    private fun stopMacroUp() {
        if (isEditMode) return
        if (!isMacroRunning) return
        isMacroRunning = false

        // 1. 立即停止协程滑动循环
        macroJob?.cancel()
        macroJob = null

        // 2. 立即彻底停止微震动反馈
        stopContinuousHaptic()

        // 3. 恢复静止状态
        triggerButton?.isFiring = false
        crosshairView?.isFiring = false

        val duration = SystemClock.uptimeMillis() - pressDownTime
        if (!isHoldingConfirmed) {
            // 【单点】：击发一下就松手！
            android.util.Log.i("FloatMacroService", "stopMacroUp: [单点] 击发一下就松手 (duration=${duration}ms), 原位抬起UP at ($startTargetX, $startTargetY)")
            if (duration < 35L) {
                // 若用户点击过快(<35ms)，延迟微毫秒补足脉宽，确保游戏输入系统 100% 捕获单发射击
                val remaining = 35L - duration
                serviceScope.launch(Dispatchers.Default) {
                    delay(remaining)
                    touchInjector.stopDrag(startTargetX, startTargetY)
                }
            } else {
                touchInjector.stopDrag(startTargetX, startTargetY)
            }
        } else {
            // 【按住】：滑动压枪刹车
            android.util.Log.i("FloatMacroService", "stopMacroUp: [按住] 压枪松手刹车 (duration=${duration}ms) at currentExecutionY=$currentExecutionY, 复位至 ($startTargetX, $startTargetY)")
            touchInjector.stopDrag(startTargetX, currentExecutionY)
        }

        // 4. 复位触控指针为初始设定点
        currentExecutionY = startTargetY

        // 恢复按键原本的透明度
        triggerButton?.setCustomAlpha(activePreset.alpha)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "自动宏悬浮前台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮窗与按键映射后台运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("自动宏服务正在运行")
            .setContentText("按住按键 A 触发宏执行 (按键A向下滑动，映射键定位)")
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
