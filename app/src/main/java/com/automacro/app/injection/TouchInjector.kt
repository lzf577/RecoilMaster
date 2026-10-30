package com.automacro.app.injection

interface TouchInjector {
    fun isAvailable(): Boolean
    fun startDrag(x: Float, y: Float): Boolean
    fun moveDrag(x: Float, y: Float): Boolean
    fun stopDrag(x: Float, y: Float): Boolean
}
