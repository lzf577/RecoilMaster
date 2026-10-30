package com.automacro.app.model

import java.io.Serializable

data class MacroPreset(
    val id: Int,                        // 1..10
    var name: String,                   // 预设名称
    var triggerX: Int = 100,            // 虚拟按键屏幕坐标 X
    var triggerY: Int = 500,            // 虚拟按键屏幕坐标 Y
    var targetX: Int = 540,             // 映射执行起点坐标 X
    var targetY: Int = 800,             // 映射执行起点坐标 Y
    var speed: Int = 5,                 // 每次步进像素 (1 ~ 30)
    var alpha: Float = 0.6f,            // 虚拟按键透明度 (0.1 ~ 1.0)
    var isSlideUp: Boolean = false      // 滑动方向: false=持续向下滑动压枪(默认)，true=向上滑动
) : Serializable
