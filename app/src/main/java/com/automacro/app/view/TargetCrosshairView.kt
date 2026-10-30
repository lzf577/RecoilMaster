package com.automacro.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class TargetCrosshairView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val listener: Listener
) : View(context) {

    interface Listener {
        fun onTargetPositionChanged(x: Int, y: Int)
    }

    var isEditMode: Boolean = false
        set(value) {
            field = value
            alpha = if (value || isFiring) 1.0f else 0.5f
            invalidate()
        }

    // 触发执行中动态反馈（按住 A 键时激活）
    var isFiring: Boolean = false
        set(value) {
            field = value
            alpha = if (value || isEditMode) 1.0f else 0.5f
            invalidate()
        }

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false

    // 十字准星画笔
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.argb(140, 0, 0, 0)
    }

    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 16, 185, 129) // 触发中绿色微光背景
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#10B981") // 绿色下滑箭头
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    init {
        alpha = 0.5f
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - 8f

        // 颜色动态适配：
        // 1. 触发执行中：高亮亮绿 (#10B981) + 动态指示
        // 2. 移动模式：亮黄色 (#F59E0B)
        // 3. 静止工作模式：鲜艳红 (#EF4444)
        val currentColor = when {
            isFiring -> Color.parseColor("#10B981")
            isEditMode -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#EF4444")
        }

        crosshairPaint.color = currentColor
        ringPaint.color = currentColor
        centerDotPaint.color = currentColor

        // 1. 触发中时绘制内部能量微光圆
        if (isFiring) {
            canvas.drawCircle(cx, cy, radius, activeGlowPaint)
        }

        // 2. 绘制深色阴影外圈（确保在任何壁纸/视频背景下清晰锐利）
        canvas.drawCircle(cx, cy, radius, shadowPaint)

        // 3. 绘制准星圆环
        ringPaint.strokeWidth = if (isFiring) 5f else 3.5f
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 4. 绘制中心十字准星线
        // 横线
        canvas.drawLine(cx - radius - 4f, cy, cx - 6f, cy, crosshairPaint)
        canvas.drawLine(cx + 6f, cy, cx + radius + 4f, cy, crosshairPaint)
        // 竖线
        canvas.drawLine(cx, cy - radius - 4f, cx, cy - 6f, crosshairPaint)
        canvas.drawLine(cx, cy + 6f, cx, cy + radius + 4f, crosshairPaint)

        // 5. 中心准星点
        val dotRadius = if (isFiring) 6f else 4.5f
        canvas.drawCircle(cx, cy, dotRadius, centerDotPaint)

        // 6. 触发执行时显示向下压枪滑动箭头 (↓) 与文字
        if (isFiring) {
            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.5f
                color = Color.parseColor("#10B981")
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val arrowPath = Path().apply {
                // 向下滑动箭头 ↓
                moveTo(cx, cy + 8f)
                lineTo(cx, cy + radius * 0.85f)
                moveTo(cx - 7f, cy + radius * 0.85f - 7f)
                lineTo(cx, cy + radius * 0.85f)
                lineTo(cx + 7f, cy + radius * 0.85f - 7f)
            }
            canvas.drawPath(arrowPath, arrowPaint)

            // 显示动态压枪文字
            hintTextPaint.color = Color.parseColor("#10B981")
            val textY = cy - radius * 0.65f
            canvas.drawText("压枪", cx, textY, hintTextPaint)
        }

        // 7. 编辑模式下显示简短提示，所见即所得
        if (isEditMode) {
            hintTextPaint.color = Color.WHITE
            canvas.drawText("映射", cx, cy - radius - 4f, hintTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) {
            // 工作模式下点击穿透
            return false
        }
        // 移动模式下：直接手指拖拽，拖到哪就是哪，完全所见即所得
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
                    listener.onTargetPositionChanged(layoutParams.x, layoutParams.y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                listener.onTargetPositionChanged(layoutParams.x, layoutParams.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
