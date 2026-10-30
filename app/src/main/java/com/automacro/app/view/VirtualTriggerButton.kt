package com.automacro.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
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

    var isLongPressActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // 触控与视觉状态分离：确保即使单点仅耗时数毫秒，人眼仍能清晰看到至少 140ms 的凹陷与剧烈变色
    private var isPhysicallyPressed: Boolean = false
    private var isVisuallyPressed: Boolean = false
    private var resetVisualRunnable: Runnable? = null
    private var lastPressDownTime: Long = 0L
    private val MIN_VISUAL_HOLD_MS = 140L

    private var baseAlpha: Float = 0.65f

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false
    private var lastUpTime: Long = 0L

    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val innerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        isClickable = true
    }

    private fun startVisualPress() {
        lastPressDownTime = SystemClock.uptimeMillis()
        isPhysicallyPressed = true
        isVisuallyPressed = true
        resetVisualRunnable?.let { removeCallbacks(it) }
        resetVisualRunnable = null
        alpha = 1.0f
        invalidate()
    }

    private fun endVisualPress() {
        isPhysicallyPressed = false
        val elapsed = SystemClock.uptimeMillis() - lastPressDownTime
        val remaining = (MIN_VISUAL_HOLD_MS - elapsed).coerceAtLeast(0L)

        resetVisualRunnable?.let { removeCallbacks(it) }
        val r = Runnable {
            if (!isPhysicallyPressed) {
                isVisuallyPressed = false
                alpha = baseAlpha
                invalidate()
            }
        }
        resetVisualRunnable = r
        if (remaining > 0) {
            postDelayed(r, remaining)
        } else {
            r.run()
        }
    }

    /** 外部强制复位按下状态（保证视觉反馈完成后再归位） */
    fun forceRelease() {
        isPhysicallyPressed = false
        val elapsed = SystemClock.uptimeMillis() - lastPressDownTime
        if (elapsed >= MIN_VISUAL_HOLD_MS) {
            isVisuallyPressed = false
            alpha = baseAlpha
            invalidate()
        } else {
            postDelayed({
                if (!isPhysicallyPressed && !isLongPressActive) {
                    isVisuallyPressed = false
                    alpha = baseAlpha
                    invalidate()
                }
            }, MIN_VISUAL_HOLD_MS - elapsed)
        }
    }

    fun setCustomAlpha(alphaValue: Float) {
        baseAlpha = alphaValue.coerceIn(0.1f, 1.0f)
        if (!isVisuallyPressed && !isPhysicallyPressed) {
            alpha = baseAlpha
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - 6f
        val isPressed = isVisuallyPressed || isPhysicallyPressed

        // 1. 底座凹槽：绘制外围深色内壁，增强按压凹陷时的机械纵深
        val socketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#090D16")
        }
        canvas.drawCircle(cx, cy, radius + 4f, socketPaint)

        // 2. 按键主体变色与物理位移：
        // 按下时键帽下沉 4px，产生实体物理按压感
        val bodyOffsetY = if (isPressed) 4f else 0f
        val bodyOffsetX = if (isPressed) 1f else 0f

        val gradient = if (isEditMode) {
            RadialGradient(cx, cy - radius * 0.3f, radius,
                Color.parseColor("#F59E0B"), Color.parseColor("#B45309"), Shader.TileMode.CLAMP)
        } else if (isFiring) {
            RadialGradient(cx, cy, radius,
                Color.parseColor("#34D399"), Color.parseColor("#047857"), Shader.TileMode.CLAMP)
        } else if (isPressed) {
            // 【按压变色】：由冷蓝瞬间反转为极高反差的炽热金黄 / 荧光烈焰
            RadialGradient(cx + 2f, cy + 3f, radius,
                Color.parseColor("#FEF08A"), Color.parseColor("#D97706"), Shader.TileMode.CLAMP)
        } else {
            // 【凸起态】：左上方高光，球形凸面穹顶
            RadialGradient(cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.1f,
                Color.parseColor("#3B82F6"), Color.parseColor("#172554"), Shader.TileMode.CLAMP)
        }
        innerBodyPaint.shader = gradient
        canvas.drawCircle(cx + bodyOffsetX, cy + bodyOffsetY, radius, innerBodyPaint)

        // 3. 【凹凸 3D 机械光影核心算法】
        val arcRect = RectF(
            cx - radius * 0.82f + bodyOffsetX,
            cy - radius * 0.82f + bodyOffsetY,
            cx + radius * 0.82f + bodyOffsetX,
            cy + radius * 0.82f + bodyOffsetY
        )

        if (isPressed) {
            // --- 【凹陷效果 (Concave)】 ---
            // 顶部绘制深暗内凹阴影 (Inner Shadow)，形成强烈的深陷底座视觉
            val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 7f
                color = Color.argb(220, 69, 26, 3) // 深暗焦糖色内阴影
            }
            canvas.drawArc(arcRect, 190f, 160f, false, innerShadowPaint)

            // 底部边缘金光反光
            val bottomRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                color = Color.argb(190, 254, 240, 138)
            }
            canvas.drawArc(arcRect, 10f, 160f, false, bottomRimPaint)
        } else {
            // --- 【凸起效果 (Convex)】 ---
            // 顶部绘制饱满白色反光弧 (Top Specular Highlight)
            val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.argb(220, 255, 255, 255)
            }
            canvas.drawArc(arcRect, 200f, 140f, false, topHighlightPaint)

            // 底部绘制深色立体投影弧 (Bottom Drop Shadow)
            val bottomShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f
                color = Color.argb(160, 15, 23, 42)
            }
            canvas.drawArc(arcRect, 20f, 140f, false, bottomShadowPaint)
        }

        // 4. 外边框 (Bezel) & 能量光环
        if (isPressed) {
            // 按下时光芒四射的金色亮圈
            outerBezelPaint.color = Color.parseColor("#FDE047")
            outerBezelPaint.strokeWidth = 8f
            canvas.drawCircle(cx, cy, radius, outerBezelPaint)

            // 外围扩散金色脉冲光圈
            val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = Color.argb(170, 245, 158, 11)
            }
            canvas.drawCircle(cx, cy, radius + 4f, auraPaint)
        } else if (isFiring) {
            outerBezelPaint.color = Color.parseColor("#34D399")
            outerBezelPaint.strokeWidth = 8f
            canvas.drawCircle(cx, cy, radius, outerBezelPaint)
        } else {
            outerBezelPaint.color = Color.parseColor("#60A5FA")
            outerBezelPaint.strokeWidth = 5f
            canvas.drawCircle(cx, cy, radius, outerBezelPaint)
        }

        // 5. 核心文字 "A"
        val fontMetrics = textPaint.fontMetrics
        val baseTextY = cy - (fontMetrics.top + fontMetrics.bottom) / 2f
        val textX = cx + bodyOffsetX
        val textY = baseTextY + bodyOffsetY // 文字跟随按键下沉

        if (isPressed) {
            textPaint.color = Color.parseColor("#451A03") // 炽金色下的深黑褐立体字
            textPaint.setShadowLayer(5f, 0f, -2f, Color.parseColor("#FEF08A")) // 顶部内凹反光
        } else {
            textPaint.color = Color.WHITE
            textPaint.setShadowLayer(6f, 0f, 3f, Color.parseColor("#0F172A")) // 底部凸起投影
        }
        canvas.drawText("A", textX, textY, textPaint)

        if (isEditMode) {
            subTextPaint.color = Color.parseColor("#BFDBFE")
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
                    if (isPhysicallyPressed || (now - lastUpTime < 35L)) {
                        return true
                    }
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startVisualPress()
                    android.util.Log.i("VirtualTriggerButton", "Button A pressed")
                    listener.onTriggerDown()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL && isLongPressActive) {
                        android.util.Log.i("VirtualTriggerButton", "Button A 正在长按中，忽略系统 CANCEL 信号，维持长按状态")
                        return true
                    }
                    val tag = if (event.actionMasked == MotionEvent.ACTION_CANCEL) "CANCEL" else "UP"
                    lastUpTime = SystemClock.uptimeMillis()
                    android.util.Log.i("VirtualTriggerButton", "Button A released $tag")
                    endVisualPress()
                    listener.onTriggerUp()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
