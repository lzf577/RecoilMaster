package com.automacro.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#3B82F6")
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

    /** 外部强制复位按下状态（防止 isPressedState 卡死） */
    fun forceRelease() {
        if (isPressedState) {
            isPressedState = false
            alpha = baseAlpha
            invalidate()
        }
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

        val gradient = if (isEditMode) {
            RadialGradient(cx, cy - radius * 0.3f, radius,
                Color.parseColor("#F59E0B"), Color.parseColor("#B45309"), Shader.TileMode.CLAMP)
        } else if (isPressedState) {
            RadialGradient(cx, cy, radius,
                Color.parseColor("#1D4ED8"), Color.parseColor("#1E40AF"), Shader.TileMode.CLAMP)
        } else {
            RadialGradient(cx, cy - radius * 0.3f, radius,
                Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8"), Shader.TileMode.CLAMP)
        }
        innerBodyPaint.shader = gradient
        canvas.drawCircle(cx, cy, radius, innerBodyPaint)

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

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(120, 255, 255, 255)
        }
        val arcRect = RectF(cx - radius * 0.75f, cy - radius * 0.75f, cx + radius * 0.75f, cy + radius * 0.75f)
        canvas.drawArc(arcRect, 200f, 140f, false, highlightPaint)

        val fontMetrics = textPaint.fontMetrics
        val textY = if (isEditMode) {
            cy - (fontMetrics.top + fontMetrics.bottom) / 2f - 6f
        } else {
            cy - (fontMetrics.top + fontMetrics.bottom) / 2f
        }
        textPaint.color = Color.WHITE
        canvas.drawText("A", cx, textY, textPaint)

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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    if (isPressedState || (now - lastUpTime < 35L)) {
                        return true
                    }
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isPressedState = true
                    alpha = 1.0f
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    android.util.Log.i("VirtualTriggerButton", "Button A pressed")
                    listener.onTriggerDown()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tag = if (event.actionMasked == MotionEvent.ACTION_CANCEL) "CANCEL" else "UP"
                    lastUpTime = SystemClock.uptimeMillis()
                    android.util.Log.i("VirtualTriggerButton", "Button A released $tag")
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
