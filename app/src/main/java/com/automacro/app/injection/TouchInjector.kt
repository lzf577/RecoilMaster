package com.automacro.app.injection

interface TouchInjector {
    fun isAvailable(): Boolean
    fun startDrag(x: Float, y: Float): Boolean
    fun moveDrag(x: Float, y: Float): Boolean
    fun stopDrag(x: Float, y: Float): Boolean

    fun startDragMulti(targetFixedX: Float, targetFixedY: Float, slideStartX: Float, slideStartY: Float): Boolean
    fun moveDragMulti(targetFixedX: Float, targetFixedY: Float, slideCurrentX: Float, slideCurrentY: Float): Boolean
    fun stopDragMulti(targetFixedX: Float, targetFixedY: Float, slideFinalX: Float, slideFinalY: Float): Boolean
}
