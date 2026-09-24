@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.suseoaa.locationspoofer.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.hooks.gait.GaitModel
import com.suseoaa.locationspoofer.xposed.hooks.gait.StepEventScheduler
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SensorStepHooker {

    data class CapturedSensorListener(
        val listener: Any,
        val sensor: Sensor?,
        val handler: Handler?
    )

    val capturedListeners = CopyOnWriteArrayList<CapturedSensorListener>()

    /**
     * 单调钳制锚:发给宿主的 counter 绝不小于上次的值。
     * 微信等宿主按"counter 差值"累计步数,任何一次回退都会被记成新增步数(表现为步数暴增);
     * 这一层是独立于 StepClockCore 的最后防线,即使上游出现异常也保证外部可见值单调。
     */
    @Volatile
    private var lastCounterOut = 0L

    @Volatile
    private var lastStepDiagAt = 0L

    private fun monotonicCounter(raw: Long): Long {
        val v = if (raw < lastCounterOut) lastCounterOut else raw
        lastCounterOut = v
        return v
    }
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()

    /** 缓存虚拟 Sensor 实例 */
    private val mockSensorCache = ConcurrentHashMap<Int, Sensor>()

    // 跟踪 handle 到 sensor type 的映射
    private val handleToTypeMap = ConcurrentHashMap<Int, Int>()

    /**
     * 本模块合成/伪造的传感器类型全集（P0-5 修复：3 → 11 类）。
     * 运动世界端内消费 gyro/rotvec/pressure/linear（报告 §4.2/§5.4），
     * 缺失会让 all_sensor_zero_while_valid 与 useMobilityTools 判别拿到空输入。
     */
    val SIMULATED_SENSOR_TYPES = setOf(
        Sensor.TYPE_STEP_COUNTER,               // 19
        Sensor.TYPE_STEP_DETECTOR,              // 18
        Sensor.TYPE_ACCELEROMETER,              // 1
        Sensor.TYPE_LINEAR_ACCELERATION,        // 10（报告 §5.4 实证 App 用去重力回调判峰）
        Sensor.TYPE_GRAVITY,                    // 9
        Sensor.TYPE_GYROSCOPE,                  // 4
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,     // 16
        Sensor.TYPE_ROTATION_VECTOR,            // 11
        Sensor.TYPE_GAME_ROTATION_VECTOR,       // 15
        Sensor.TYPE_PRESSURE,                   // 6（驱动 totalAscent）
        Sensor.TYPE_MAGNETIC_FIELD              // 2
    )

    fun hookSensorStepSimulation(classLoader: ClassLoader) {
        val targetClasses = listOf(
            "android.hardware.SensorManager",
            "android.hardware.SystemSensorManager"
        )

        for (className in targetClasses) {
            val managerClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

            // 1. Hook getDefaultSensor: 当设备无物理传感器时注入虚拟 Sensor
            try {
                XposedHelpers.hookAllMethods(managerClass, "getDefaultSensor") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (result == null && type in SIMULATED_SENSOR_TYPES) {
                        return@hookAllMethods getOrCreateMockSensor(type, classLoader)
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 2. Hook getSensorList
            try {
                XposedHelpers.hookAllMethods(managerClass, "getSensorList") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (type in SIMULATED_SENSOR_TYPES) {
                        if (result is List<*> && result.isEmpty()) {
                            val mock = getOrCreateMockSensor(type, classLoader)
                            if (mock != null) return@hookAllMethods listOf(mock)
                        }
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 3. Hook registerListener / registerListenerImpl
            val regMethods = listOf("registerListener", "registerListenerImpl")
            for (methodName in regMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        var listener: Any? = null
                        var sensor: Sensor? = null
                        var handler: Handler? = null

                        for (arg in args) {
                            if (arg != null) {
                                if (arg is SensorEventListener || LocationHooker.hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                                    listener = arg
                                } else if (arg is Sensor) {
                                    sensor = arg
                                } else if (arg is Handler) {
                                    handler = arg
                                }
                            }
                        }

                        if (listener != null) {
                            val targetSensor = sensor ?: getOrCreateMockSensor(Sensor.TYPE_STEP_COUNTER, classLoader)
                            if (targetSensor != null && targetSensor.type in SIMULATED_SENSOR_TYPES) {
                                val entry = CapturedSensorListener(listener, targetSensor, handler)
                                if (!capturedListeners.any { it.listener === listener && it.sensor?.type == targetSensor.type }) {
                                    capturedListeners.add(entry)
                                }

                                val handle = getSensorHandle(targetSensor)
                                if (handle != null) {
                                    handleToTypeMap[handle] = targetSensor.type
                                }

                                // 动态 Hook 该 Listener 的具体实现类中的 onSensorChanged 方法
                                hookConcreteListenerClass(listener.javaClass, classLoader)
                            }
                        }

                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }

            // 4. Hook unregisterListener / unregisterListenerImpl
            val unregMethods = listOf("unregisterListener", "unregisterListenerImpl")
            for (methodName in unregMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        val listener = args.firstOrNull { it != null && (it is SensorEventListener || LocationHooker.hasTypeByName(it.javaClass, "android.hardware.SensorEventListener")) }
                        if (listener != null) {
                            capturedListeners.removeAll { it.listener === listener }
                        }
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }

        // 5. Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent 底层原生分发接口
        hookSensorEventQueue(classLoader)

        // 6. 注册步时钟回调：STEP_DETECTOR / STEP_COUNTER 由 StepEventScheduler 驱动（F1）
        StepEventScheduler.onStep = { totalSteps, _ ->
            pushStepEvents(totalSteps, classLoader)
        }
    }

    /**
     * 当前有效速度（m/s）：优先取 RouteEngine 的瞬时配速曲线输出（与 GPS 推送
     * 同源 —— 步频、加速度波形、位移三者共享同一条速度曲线，P1-1）；
     * 非路线模式回退 config.speed_m_s（保持旧语义）。
     */
    private fun currentSpeed(config: JSONObject): Double {
        val instant = com.suseoaa.locationspoofer.xposed.utils.RouteEngine.lastInstantSpeed.toDouble()
        if (instant > 0.05) return instant
        return config.optDouble("speed_m_s", 0.0).let { if (it.isNaN()) 0.0 else it }
    }

    private fun hookSensorEventQueue(classLoader: ClassLoader) {
        try {
            val eventQueueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
                ?: XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
                ?: return

            XposedHelpers.hookAllMethods(eventQueueClass, "dispatchSensorEvent") { chain, _ ->
                val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)) {
                    val handle = chain.args.firstOrNull() as? Int
                    val values = chain.args.getOrNull(1) as? FloatArray

                    if (handle != null && values != null) {
                        val sensorType = handleToTypeMap[handle]
                        val speed = currentSpeed(config)
                        // 真实事件流统一改写（静止时透传，运动时全部换成本模块合成值）
                        applySpoofedValues(sensorType, values, config, speed)
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    /**
     * 动态 Hook 具体的 Listener 实现类（兜底：直接改写回调参数）
     */
    private fun hookConcreteListenerClass(clazz: Class<*>, classLoader: ClassLoader) {
        if (hookedListenerClasses.putIfAbsent(clazz, true) == null) {
            try {
                XposedHelpers.hookAllMethods(clazz, "onSensorChanged") { chain, _ ->
                    val event = chain.args.firstOrNull() as? SensorEvent
                    if (event != null && event.sensor != null) {
                        val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                        if (config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)) {
                            val speed = currentSpeed(config)
                            try { event.timestamp = SystemClock.elapsedRealtimeNanos() } catch (_: Throwable) {}
                            applySpoofedValues(event.sensor.type, event.values, config, speed)
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 统一的合成值改写入口（真实事件流覆盖点）。两处覆盖点（eventQueue /
     * concrete listener）共用此函数 —— 步数只有一个真值源（修复原缺陷 ④）。
     *
     * 静止（speed <= 0.1）时除 counter/detector 外全部透传真实传感器，
     * 保证人脸识别等敏感场景的传感器纯净。
     */
    private fun applySpoofedValues(sensorType: Int?, values: FloatArray, config: JSONObject, speed: Double) {
        if (sensorType == null) return
        val now = SystemClock.elapsedRealtime()
        val cadence = StepEventScheduler.currentCadence

        when (sensorType) {            Sensor.TYPE_STEP_COUNTER -> {
                if (values.isNotEmpty()) {
                    // 三级基线回退最高优先级：采纳真实 counter 首帧（仅首次且未推步时生效）
                    StepEventScheduler.adoptRealCounterBaseline(values[0].toLong())
                    // 单一真值：baseline + dispatchedSteps，与 detector 路天然一致（不变量 #1）
                    // 输出前过单调钳制：会话切换/基线修正都不得让外部可见 counter 回退
                    values[0] = monotonicCounter(StepEventScheduler.totalSteps).toFloat()
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                if (values.isNotEmpty() && speed > 0.1) {
                    values[0] = 1.0f
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (values.size >= 3 && speed > 0.1) {
                    val v = GaitModel.withGravity(now, speed, cadence)
                    values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (values.size >= 3 && speed > 0.1) {
                    val v = GaitModel.linearAcceleration(now, speed, cadence)
                    values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
                }
            }
            Sensor.TYPE_GRAVITY -> {
                if (values.size >= 3 && speed > 0.1) {
                    val v = GaitModel.gravityVector(now)
                    values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (values.size >= 3 && speed > 0.1) {
                    val v = GaitModel.gyroscope(now, speed, cadence)
                    values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
                }
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (values.size >= 6 && speed > 0.1) {
                    val v = GaitModel.gyroscopeUncalibrated(now, speed, cadence)
                    for (i in 0 until 6) values[i] = v[i]
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (values.size >= 4 && speed > 0.1) {
                    val v = GaitModel.rotationVector(now, speed, cadence, useGeographic = true)
                    val n = minOf(values.size, v.size)
                    for (i in 0 until n) values[i] = v[i]
                }
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (values.size >= 4 && speed > 0.1) {
                    val v = GaitModel.rotationVector(now, speed, cadence, useGeographic = false)
                    val n = minOf(values.size, 4)
                    for (i in 0 until n) values[i] = v[i]
                }
            }
            Sensor.TYPE_PRESSURE -> {
                if (values.isNotEmpty() && speed > 0.1) {
                    val altitude = config.optDouble("altitude", 25.0).let { if (it.isNaN()) 25.0 else it }
                    values[0] = GaitModel.pressure(now, altitude).toFloat()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (values.size >= 3 && speed > 0.1) {
                    val v = GaitModel.magneticField(now)
                    values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
                }
            }
        }

        if (config.optBoolean("diag_logs", true)) {
            logDiag(sensorType, values, speed, cadence, now)
        }
    }

    // ---- 诊断日志（每类型 3s 节流），真机验证用：观察伪造值与调用频率 ----
    private val diagCallsByType = ConcurrentHashMap<Int, Long>()
    private val diagLastLogAt = ConcurrentHashMap<Int, Long>()

    private fun logDiag(sensorType: Int, values: FloatArray, speed: Double, cadence: Int, nowMs: Long) {
        val calls = diagCallsByType.merge(sensorType, 1L, Long::plus) ?: 1L
        val last = diagLastLogAt[sensorType] ?: 0L
        if (nowMs - last < 3000L) return
        diagLastLogAt[sensorType] = nowMs
        val sb = StringBuilder()
        for (i in 0 until minOf(values.size, 6)) {
            if (i > 0) sb.append(',')
            sb.append(values[i])
        }
        XposedBridge.log("[DIAG] sensor=$sensorType speed=$speed cadence=$cadence steps=${StepEventScheduler.totalSteps} calls=$calls v=[$sb]")
    }

    /**
     * 兼容旧调用点：当前仿真总步数。改读步时钟单一真值（原闭式算法已删除）。
     */
    fun calculateCurrentSteps(config: JSONObject, now: Long = System.currentTimeMillis()): Long {
        return StepEventScheduler.totalSteps
    }

    /**
     * 兼容旧调用点：按速度给出生理步频。委托步时钟的步幅优先公式。
     */
    fun calculateAutoCadence(speedMs: Double): Int {
        return StepEventScheduler.computeCadenceStatic(speedMs, StepEventScheduler.DEFAULT_TARGET_STRIDE)
    }

    /**
     * 兼容旧调用点：加速度计合成波形。委托 GaitModel（exp 冲击脉冲 + 高斯噪声）。
     */
    private fun applySyntheticVibration(values: FloatArray, config: JSONObject, speed: Double) {
        val now = SystemClock.elapsedRealtime()
        val cadence = StepEventScheduler.currentCadence
        val v = GaitModel.withGravity(now, speed, cadence)
        if (values.size >= 3) {
            values[0] = v[0]; values[1] = v[1]; values[2] = v[2]
        }
    }

    /**
     * ConfigPoller 每秒调用：只负责同步配置 + 连续型传感器 1 Hz 兜底推送。
     * 步事件（counter/detector）改由 StepEventScheduler 的步时钟按真实 cadence 推送。
     */
    fun dispatchStepEvents(config: JSONObject, classLoader: ClassLoader) {
        if (!config.optBoolean("active", false)) return
        val enableStep = config.optBoolean("enable_step_simulation", true)
        if (!enableStep) return

        // 步时钟：同步速度/步幅/基线配置，内部按 60/cadence 间隔产出步事件
        StepEventScheduler.syncConfig(config)

        // 连续型传感器兜底推送（仅当设备无真实传感器、App 只能靠主动推送取数的场景起作用；
        // 真实设备上真实事件流会被 dispatchSensorEvent 覆盖，频率真实）
        pushContinuousEvents(config, classLoader)
    }

    /** 步时钟回调：向捕获的 listener 推送 counter/detector 事件（两路同源，不变量 #1）。 */
    private fun pushStepEvents(totalSteps: Long, classLoader: ClassLoader) {
        // 步时钟停止后 cadence 归零，此时不得再向宿主推送步事件（兜底防线）
        if (StepEventScheduler.currentCadence <= 0) return
        val listeners = capturedListeners.toList()
        if (listeners.isEmpty()) return

        // 节流诊断：核对推送速率与 counter 步进（定位"步数涨得过快"类问题）
        val diagNow = SystemClock.elapsedRealtime()
        if (diagNow - lastStepDiagAt > 3000L) {
            lastStepDiagAt = diagNow
            XposedBridge.log(
                "[DIAG] step-push total=$totalSteps cadence=${StepEventScheduler.currentCadence} " +
                    "listeners=${listeners.size}"
            )
        }
        val mainHandler = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null } ?: return

        for (entry in listeners) {
            val sensor = entry.sensor ?: continue
            val targetHandler = entry.handler ?: mainHandler
            when (sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    postSensorEvent(
                        entry,
                        floatArrayOf(monotonicCounter(totalSteps).toFloat()),
                        classLoader,
                        targetHandler
                    )
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    postSensorEvent(entry, floatArrayOf(1.0f), classLoader, targetHandler)
                }
            }
        }
    }

    /** 连续型传感器 1 Hz 兜底推送。 */
    private fun pushContinuousEvents(config: JSONObject, classLoader: ClassLoader) {
        val speed = currentSpeed(config)
        // 静止时绝不主动推送，保证主线程和人脸识别传感器纯净
        if (speed <= 0.05) return

        val now = SystemClock.elapsedRealtime()
        val cadence = StepEventScheduler.currentCadence
        val mainHandler = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null } ?: return

        for (entry in capturedListeners.toList()) {
            val sensor = entry.sensor ?: continue
            val values: FloatArray = when (sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> GaitModel.withGravity(now, speed, cadence)
                Sensor.TYPE_LINEAR_ACCELERATION -> GaitModel.linearAcceleration(now, speed, cadence)
                Sensor.TYPE_GRAVITY -> GaitModel.gravityVector(now)
                Sensor.TYPE_GYROSCOPE -> GaitModel.gyroscope(now, speed, cadence)
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> GaitModel.gyroscopeUncalibrated(now, speed, cadence)
                Sensor.TYPE_ROTATION_VECTOR -> GaitModel.rotationVector(now, speed, cadence, useGeographic = true)
                Sensor.TYPE_GAME_ROTATION_VECTOR -> GaitModel.rotationVector(now, speed, cadence, useGeographic = false).copyOf(4)
                Sensor.TYPE_PRESSURE -> {
                    val altitude = config.optDouble("altitude", 25.0).let { if (it.isNaN()) 25.0 else it }
                    floatArrayOf(GaitModel.pressure(now, altitude).toFloat())
                }
                Sensor.TYPE_MAGNETIC_FIELD -> GaitModel.magneticField(now)
                else -> continue
            }
            postSensorEvent(entry, values, classLoader, entry.handler ?: mainHandler)
        }
    }

    private fun postSensorEvent(entry: CapturedSensorListener, values: FloatArray, classLoader: ClassLoader, targetHandler: Handler) {
        val event = createSensorEvent(entry.sensor ?: return, values)
        if (event != null) {
            targetHandler.post {
                try {
                    if (entry.listener is SensorEventListener) {
                        entry.listener.onSensorChanged(event)
                    } else {
                        XposedHelpers.callMethod(entry.listener, "onSensorChanged", event)
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun getSensorHandle(sensor: Sensor): Int? {
        return try {
            val handleField = Sensor::class.java.getDeclaredField("mHandle")
            handleField.isAccessible = true
            handleField.getInt(sensor)
        } catch (_: Throwable) {
            null
        }
    }

    // ---- 虚拟 Sensor 工厂（表驱动，11 类，补齐 maxRange/minDelay/maxDelay/fifo） ----

    private class SensorSpec(
        val name: String,
        val stringType: String,
        val maxRange: Float,
        val resolution: Float,
        val power: Float,
        val minDelayUs: Int,
        val maxDelayUs: Int,
        val fifoMaxEventCount: Int
    )

    private val SENSOR_SPECS: Map<Int, SensorSpec> = mapOf(
        Sensor.TYPE_STEP_COUNTER to SensorSpec("Step Counter Sensor", "android.sensor.step_counter", 100000f, 1f, 0.05f, 0, Int.MAX_VALUE, 100),
        Sensor.TYPE_STEP_DETECTOR to SensorSpec("Step Detector Sensor", "android.sensor.step_detector", 1f, 1f, 0.05f, 0, Int.MAX_VALUE, 100),
        Sensor.TYPE_ACCELEROMETER to SensorSpec("Accelerometer Sensor", "android.sensor.accelerometer", 78.45f, 0.0024f, 0.13f, 5000, 500000, 500),
        Sensor.TYPE_LINEAR_ACCELERATION to SensorSpec("Linear Acceleration Sensor", "android.sensor.linear_acceleration", 78.45f, 0.0024f, 0.13f, 5000, 500000, 500),
        Sensor.TYPE_GRAVITY to SensorSpec("Gravity Sensor", "android.sensor.gravity", 78.45f, 0.0024f, 0.13f, 5000, 500000, 500),
        Sensor.TYPE_GYROSCOPE to SensorSpec("Gyroscope Sensor", "android.sensor.gyroscope", 34.9f, 0.001f, 0.25f, 2500, 500000, 500),
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED to SensorSpec("Uncalibrated Gyroscope Sensor", "android.sensor.gyroscope_uncalibrated", 34.9f, 0.001f, 0.25f, 2500, 500000, 500),
        Sensor.TYPE_ROTATION_VECTOR to SensorSpec("Rotation Vector Sensor", "android.sensor.rotation_vector", 1f, 0.000001f, 0.25f, 5000, 500000, 300),
        Sensor.TYPE_GAME_ROTATION_VECTOR to SensorSpec("Game Rotation Vector Sensor", "android.sensor.game_rotation_vector", 1f, 0.000001f, 0.25f, 5000, 500000, 300),
        Sensor.TYPE_PRESSURE to SensorSpec("Pressure Sensor", "android.sensor.pressure", 1100f, 0.002f, 0.12f, 20000, 500000, 300),
        Sensor.TYPE_MAGNETIC_FIELD to SensorSpec("Magnetic Field Sensor", "android.sensor.magnetic_field", 4912f, 0.15f, 0.2f, 10000, 500000, 300)
    )

    private fun getOrCreateMockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        mockSensorCache[type]?.let { return it }
        val spec = SENSOR_SPECS[type] ?: return null

        try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val constructor: Constructor<*> = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor

            setSensorField(sensor, "mType", type)
            setSensorField(sensor, "mName", spec.name)
            setSensorField(sensor, "mStringType", spec.stringType)
            setSensorField(sensor, "mVendor", "Android")
            setSensorField(sensor, "mVersion", 1)
            setSensorField(sensor, "mMaxRange", spec.maxRange)
            setSensorField(sensor, "mResolution", spec.resolution)
            setSensorField(sensor, "mPower", spec.power)
            setSensorField(sensor, "mMinDelay", spec.minDelayUs)
            setSensorField(sensor, "mMaxDelay", spec.maxDelayUs)
            setSensorField(sensor, "mFifoMaxEventCount", spec.fifoMaxEventCount)
            setSensorField(sensor, "mFifoReservedEventCount", 0)
            setSensorField(sensor, "mIsWakeUpSensor", false)
            setSensorField(sensor, "mReportingMode", 0) // REPORTING_MODE_CONTINUOUS

            mockSensorCache[type] = sensor
            return sensor
        } catch (_: Throwable) {
            return null
        }
    }

    private fun setSensorField(sensor: Sensor, fieldName: String, value: Any) {
        try {
            val field: Field = Sensor::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(sensor, value)
        } catch (_: Throwable) {}
    }

    private fun createSensorEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        try {
            val eventConstructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            eventConstructor.isAccessible = true
            val event = eventConstructor.newInstance(values.size) as SensorEvent
            System.arraycopy(values, 0, event.values, 0, values.size)

            val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
            sensorField.isAccessible = true
            sensorField.set(event, sensor)

            val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
            timestampField.isAccessible = true
            timestampField.setLong(event, SystemClock.elapsedRealtimeNanos())

            val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
            accuracyField.isAccessible = true
            accuracyField.setInt(event, 3) // SENSOR_STATUS_ACCURACY_HIGH

            return event
        } catch (_: Throwable) {
            try {
                // Fallback 1: search any constructors
                val constructors = SensorEvent::class.java.declaredConstructors
                for (ctor in constructors) {
                    ctor.isAccessible = true
                    val paramTypes = ctor.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size)
                    for (i in args.indices) {
                        if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = values.size
                    }
                    val event = ctor.newInstance(*args) as SensorEvent
                    System.arraycopy(values, 0, event.values, 0, values.size)
                    try {
                        val sf = SensorEvent::class.java.getDeclaredField("sensor")
                        sf.isAccessible = true
                        sf.set(event, sensor)
                        val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                        tf.isAccessible = true
                        tf.setLong(event, SystemClock.elapsedRealtimeNanos())
                        val af = SensorEvent::class.java.getDeclaredField("accuracy")
                        af.isAccessible = true
                        af.setInt(event, 3)
                    } catch (_: Throwable) {}
                    return event
                }
            } catch (_: Throwable) {}

            try {
                // Fallback 2: sun.misc.Unsafe.allocateInstance
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val event = allocateMethod.invoke(unsafe, SensorEvent::class.java) as SensorEvent

                val valuesField = SensorEvent::class.java.getDeclaredField("values")
                valuesField.isAccessible = true
                val arr = FloatArray(values.size)
                System.arraycopy(values, 0, arr, 0, values.size)
                valuesField.set(event, arr)

                val sf = SensorEvent::class.java.getDeclaredField("sensor")
                sf.isAccessible = true
                sf.set(event, sensor)

                val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                tf.isAccessible = true
                tf.setLong(event, SystemClock.elapsedRealtimeNanos())

                val af = SensorEvent::class.java.getDeclaredField("accuracy")
                af.isAccessible = true
                af.setInt(event, 3)
                return event
            } catch (_: Throwable) {}

            return null
        }
    }
}
