package com.automacro.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class VirtualTriggerButton(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val listener: Listener
) : View(context) {

    interface Listener {
        fun onTriggerDown()
        fun onTriggerUp()
        fun onPositionChanged(x: Int, y: Int)
    }

    var isEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isFiring: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var isPressedState: Boolean = false
    private var baseAlpha: Float = 0.65f

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false
    private var lastUpTime: Long = 0L

    // 游戏手柄风格画笔
    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#3B82F6") // 科技蓝边框
    }

    private val innerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BFDBFE")
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        isClickable = true
    }

    fun setCustomAlpha(alphaValue: Float) {
        baseAlpha = alphaValue.coerceIn(0.1f, 1.0f)
        if (!isPressedState) {
            alpha = baseAlpha
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - 6f

        // 1. 游戏按键渐变背景（未按下 vs 按下中 vs 移动模式）
        val gradient = if (isEditMode) {
            RadialGradient(
                cx, cy - radius * 0.3f, radius,
                Color.parseColor("#F59E0B"), Color.parseColor("#B45309"),
                Shader.TileMode.CLAMP
            )
        } else if (isPressedState) {
            RadialGradient(
                cx, cy, radius,
                Color.parseColor("#1D4ED8"), Color.parseColor("#1E40AF"),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, cy - radius * 0.3f, radius,
                Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8"),
                Shader.TileMode.CLAMP
            )
        }
        innerBodyPaint.shader = gradient
        canvas.drawCircle(cx, cy, radius, innerBodyPaint)

        // 2. 外部游戏金属质感轮廓边框
        outerBezelPaint.color = if (isEditMode) {
            Color.parseColor("#FCD34D")
        } else if (isFiring) {
            Color.parseColor("#10B981")
        } else if (isPressedState) {
            Color.parseColor("#93C5FD")
        } else {
            Color.parseColor("#60A5FA")
        }
        outerBezelPaint.strokeWidth = if (isPressedState || isFiring) 7f else 5f
        canvas.drawCircle(cx, cy, radius, outerBezelPaint)

        // 3. 内部高光浅弧线（增加游戏手柄按键立体球形质感）
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(120, 255, 255, 255)
        }
        val arcRect = RectF(cx - radius * 0.75f, cy - radius * 0.75f, cx + radius * 0.75f, cy + radius * 0.75f)
        canvas.drawArc(arcRect, 200f, 140f, false, highlightPaint)

        // 4. 游戏按键大字 "A"
        val fontMetrics = textPaint.fontMetrics
        val textY = if (isEditMode) {
            cy - (fontMetrics.top + fontMetrics.bottom) / 2f - 6f
        } else {
            cy - (fontMetrics.top + fontMetrics.bottom) / 2f
        }
        textPaint.color = Color.WHITE
        canvas.drawText("A", cx, textY, textPaint)

        // 编辑模式下提示“移动”，按住触发时提示“同步”
        if (isEditMode) {
            canvas.drawText("移动", cx, cy + radius * 0.65f, subTextPaint)
        } else if (isFiring) {
            subTextPaint.color = Color.parseColor("#10B981")
            canvas.drawText("触发", cx, cy + radius * 0.65f, subTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) {
            // 移动调整模式：直接全屏自由拖拽按键 A
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > 6 || abs(dy) > 6)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(this, layoutParams)
                        listener.onPositionChanged(layoutParams.x, layoutParams.y)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        listener.onPositionChanged(layoutParams.x, layoutParams.y)
                    }
                    return true
                }
            }
        } else {
            // 工作锁定模式：按住按键 A 触发滑动宏，松手立即刹车
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    // 防重入与硬件杂波过滤：已按下或距离上次松开小于 35ms 直接忽略
                    if (isPressedState || (now - lastUpTime < 35L)) {
                        return true
                    }
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isPressedState = true
                    alpha = 1.0f
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    android.util.Log.i("VirtualTriggerButton", "onTouchEvent: Button A pressed")
                    listener.onTriggerDown()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 按住期间手指微动保持捕获，防止事件流断开
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 彻底忽略系统合成的 ACTION_CANCEL（真实人手抬起只会触发 ACTION_UP）
                    android.util.Log.d("VirtualTriggerButton", "onTouchEvent: Ignoring synthetic ACTION_CANCEL during button hold")
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    lastUpTime = SystemClock.uptimeMillis()
                    android.util.Log.i("VirtualTriggerButton", "onTouchEvent: Button A released ACTION_UP")
                    isPressedState = false
                    alpha = baseAlpha
                    invalidate()
                    listener.onTriggerUp()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
