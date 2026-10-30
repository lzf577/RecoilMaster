package com.automacro.app.injection

interface TouchInjector {
    fun isAvailable(): Boolean
    fun tap(x: Float, y: Float): Boolean
    fun startDrag(x: Float, y: Float): Boolean
    fun moveDrag(x: Float, y: Float): Boolean
    fun stopDrag(x: Float, y: Float): Boolean
}
