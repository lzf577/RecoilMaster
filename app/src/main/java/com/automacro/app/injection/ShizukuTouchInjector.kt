package com.automacro.app.injection

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.reflect.Method

class ShizukuTouchInjector : TouchInjector {

    companion object {
        private const val TAG = "ShizukuTouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    private var inputManagerInstance: Any? = null
    private var injectMethod: Method? = null
    private var isTargetUidMethod: Boolean = false
    private var lastDownTime: Long = 0L
    private var moveLogCounter = 0

    // Shell 管道兜底
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var useShellFallback: Boolean = false

    var lastErrorMessage: String = ""
        private set

    init {
        val binderOk = initService()
        if (!binderOk) {
            initShellFallback()
        }
    }

    private fun initService(): Boolean {
        if (!isAvailable()) {
            lastErrorMessage = "Shizuku 未授权或服务未运行"
            Log.w(TAG, lastErrorMessage)
            return false
        }
        if (inputManagerInstance != null && injectMethod != null) {
            return true
        }

        return try {
            val binder: IBinder? = try {
                val sm = Class.forName("android.os.ServiceManager")
                sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
            } catch (e: Throwable) {
                null
            } ?: SystemServiceHelper.getSystemService("input")

            if (binder == null) {
                lastErrorMessage = "获取系统 input 服务失败"
                Log.w(TAG, lastErrorMessage)
                initShellFallback()
                return false
            }

            val wrappedBinder = ShizukuBinderWrapper(binder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            inputManagerInstance = asInterface.invoke(null, wrappedBinder)

            val methods = inputManagerInstance?.javaClass?.methods ?: return false

            for (m in methods) {
                if (m.name == "injectInputEventToTarget") {
                    val p = m.parameterTypes
                    if (p.size == 3 && InputEvent::class.java.isAssignableFrom(p[0])) {
                        injectMethod = m
                        isTargetUidMethod = true
                        m.isAccessible = true
                        Log.i(TAG, "已找到 injectInputEventToTarget (3参数)")
                        break
                    }
                }
            }

            if (injectMethod == null) {
                for (m in methods) {
                    if (m.name == "injectInputEvent") {
                        val p = m.parameterTypes
                        if (p.size == 2 && InputEvent::class.java.isAssignableFrom(p[0])) {
                            injectMethod = m
                            isTargetUidMethod = false
                            m.isAccessible = true
                            Log.i(TAG, "已找到 injectInputEvent (2参数)")
                            break
                        }
                    }
                }
            }

            if (injectMethod == null) {
                lastErrorMessage = "未找到 injectInputEvent 反射方法"
                Log.w(TAG, lastErrorMessage)
                initShellFallback()
                return false
            }

            useShellFallback = false
            Log.i(TAG, "Shizuku InputManager 初始化成功！")
            true
        } catch (e: Throwable) {
            lastErrorMessage = "Binder初始化异常: ${e.message}"
            Log.e(TAG, "Failed to initialize Shizuku input manager", e)
            initShellFallback()
            false
        }
    }

    private fun initShellFallback(): Boolean {
        if (shellProcess != null && shellWriter != null) return true
        return try {
            val requireServiceMethod = Shizuku::class.java.getDeclaredMethod("requireService")
            requireServiceMethod.isAccessible = true
            val service = requireServiceMethod.invoke(null) as moe.shizuku.server.IShizukuService
            val remoteProcess = service.newProcess(arrayOf("sh"), null, null)

            val constructor = rikka.shizuku.ShizukuRemoteProcess::class.java.getDeclaredConstructor(
                moe.shizuku.server.IRemoteProcess::class.java
            )
            constructor.isAccessible = true
            val process = constructor.newInstance(remoteProcess)
            shellWriter = BufferedWriter(OutputStreamWriter(process.outputStream))
            shellProcess = process
            Log.i(TAG, "Shizuku Shell 进程兜底管道已建立！")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init Shell fallback", e)
            lastErrorMessage = "Shell管道创建失败: ${e.message}"
            false
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun injectEvent(event: MotionEvent): Boolean {
        if (!useShellFallback) {
            if (inputManagerInstance == null || injectMethod == null) {
                if (!initService()) {
                    return injectViaShell(event)
                }
            }

            try {
                val result = if (isTargetUidMethod) {
                    injectMethod?.invoke(inputManagerInstance, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
                } else {
                    injectMethod?.invoke(inputManagerInstance, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                }
                val success = (result as? Boolean) ?: true
                if (success) {
                    return true
                }
                Log.w(TAG, "injectMethod returned false, switching to shell fallback")
            } catch (e: Throwable) {
                Log.w(TAG, "Binder inject failed: ${e.message}, switching to shell fallback")
                lastErrorMessage = "Binder注入失败: ${e.message}"
                useShellFallback = true
            }
        }

        return injectViaShell(event)
    }

    private fun injectViaShell(event: MotionEvent): Boolean {
        if (!initShellFallback()) return false
        val writer = shellWriter ?: return false
        val actionStr = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return false
        }
        val x = event.x.toInt()
        val y = event.y.toInt()

        return try {
            writer.write("cmd input motionevent $actionStr $x $y\n")
            writer.flush()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Shell inject error", e)
            lastErrorMessage = "Shell注入失败: ${e.message}"
            shellProcess = null
            shellWriter = null
            false
        }
    }

    // ====== 单指针原生触摸注入（按住A键，映射键精准滑动，松手即停） ======

    override fun tap(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        lastDownTime = now
        val downEvent = createSingleMotionEvent(MotionEvent.ACTION_DOWN, x, y, now)
        val okDown = injectEvent(downEvent)
        downEvent.recycle()

        // 维持 35ms 模拟真实点击脉冲，确保游戏物理引擎捕获开火，之后立即松手抬起
        SystemClock.sleep(35)

        val upTime = SystemClock.uptimeMillis()
        val upEvent = createSingleMotionEvent(MotionEvent.ACTION_UP, x, y, upTime)
        val okUp = injectEvent(upEvent)
        upEvent.recycle()

        Log.i(TAG, "tap at ($x, $y) -> down:$okDown, up:$okUp")
        return okDown && okUp
    }

    private fun sendMotion(action: String, x: Float, y: Float): Boolean {
        val actionInt = when (action) {
            "DOWN" -> MotionEvent.ACTION_DOWN
            "MOVE" -> MotionEvent.ACTION_MOVE
            "UP" -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_CANCEL
        }
        val event = createSingleMotionEvent(actionInt, x, y, SystemClock.uptimeMillis())
        val ok = injectEvent(event)
        event.recycle()
        return ok
    }

    private fun createSingleMotionEvent(action: Int, x: Float, y: Float, eventTime: Long): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1.0f
            size = 1.0f
        }

        return MotionEvent.obtain(
            lastDownTime,
            eventTime,
            action,
            1,
            arrayOf(pp),
            arrayOf(pc),
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    setDisplayIdMethod.invoke(this, 0)
                } catch (ignored: Throwable) {}
            }
        }
    }

    override fun startDrag(x: Float, y: Float): Boolean {
        lastDownTime = SystemClock.uptimeMillis()
        val ok = sendMotion("DOWN", x, y)
        Log.i(TAG, "startDrag at ($x, $y) -> $ok")
        return ok
    }

    override fun moveDrag(x: Float, y: Float): Boolean {
        return sendMotion("MOVE", x, y)
    }

    override fun stopDrag(x: Float, y: Float): Boolean {
        val ok = sendMotion("UP", x, y)
        Log.i(TAG, "stopDrag at ($x, $y) -> $ok")
        return ok
    }
}
