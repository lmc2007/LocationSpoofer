package com.suseoaa.locationspoofer.xposed.hooks.gait

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject

/**
 * 步时钟调度器 —— [StepClockCore] 的 Android 壳。
 *
 * 以固定节奏（TICK_MS ≈ 200 ms，独立 HandlerThread，不占主线程）驱动步时钟核心；
 * 每当核心越过一次步事件的时间点，就通过 [onStep] 回调向宿主推送
 * STEP_DETECTOR / STEP_COUNTER 事件（由 SensorStepHooker 注册具体推送逻辑）。
 *
 * 设计要点：
 * - 不再依赖 ConfigPoller 的 1 Hz 节拍推事件（那是 detector 路恒 60 SPM 的根源）；
 *   ConfigPoller 只负责 [syncConfig] 同步最新速度/步幅配置。
 * - 事件推送回调运行在步时钟线程，具体 listener 回调由注册方自行 post 到
 *   目标 Handler（沿用 SensorStepHooker 原有的 handler.post 行为）。
 */
object StepEventScheduler {

    private val core = StepClockCore()
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var started = false
    @Volatile private var cachedSpeedMs = 0.0
    @Volatile private var cachedTargetStride = DEFAULT_TARGET_STRIDE
    @Volatile private var cachedJitter = true

    /** 步事件回调（counter 口径总步数、步事件时刻），SensorStepHooker 注册。 */
    @Volatile
    var onStep: ((stepTotal: Long, stepAtMs: Long) -> Unit)? = null

    /** 同步最新配置；ConfigPoller 每秒调用，代价为一次 volatile 写 + 会话比对。 */
    fun syncConfig(config: JSONObject) {
        val startTs = config.optLong("start_timestamp", 0L)
        val baselineOverride = if (config.has("step_baseline")) config.optLong("step_baseline", -1L) else -1L
        core.bindSession(startTs, baselineOverride)
        // 速度同源（P1-1）：优先 RouteEngine 配速曲线的瞬时输出，保证
        // 步频/步态波形与 GPS 推送共享同一条速度曲线
        val instant = com.suseoaa.locationspoofer.xposed.utils.RouteEngine.lastInstantSpeed.toDouble()
        cachedSpeedMs = if (instant > 0.05) {
            instant
        } else {
            config.optDouble("speed_m_s", 0.0).let { if (it.isNaN()) 0.0 else it }
        }
        cachedTargetStride = if (config.has("target_stride_m")) {
            config.optDouble("target_stride_m", DEFAULT_TARGET_STRIDE).let { if (it.isNaN()) DEFAULT_TARGET_STRIDE else it }
        } else DEFAULT_TARGET_STRIDE
        cachedJitter = config.optBoolean("enable_jitter", true)
        ensureStarted()
    }

    /** 三级基线回退中的最高优先级：真实 TYPE_STEP_COUNTER 首帧值。 */
    fun adoptRealCounterBaseline(realValue: Long): Boolean = core.adoptRealCounterBaseline(realValue)

    /** counter 口径总步数（任意线程可读）。 */
    val totalSteps: Long get() = core.totalSteps

    /** 当前步频（SPM），静止为 0。 */
    val currentCadence: Int get() = core.currentCadence

    /** 最近一步发生时刻（单调毫秒），步态波形对齐用。 */
    val lastStepAtMs: Long get() = core.lastStepAtMs

    fun computeCadenceStatic(speedMs: Double, targetStride: Double): Int =
        core.computeCadenceStatic(speedMs, targetStride)

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            val thread = HandlerThread("LocationSpoofer_StepClock").apply { start() }
            handlerThread = thread
            handler = Handler(thread.looper)
            handler?.postDelayed(tickRunnable, TICK_MS)
            started = true
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                val result = core.onTick(now, cachedSpeedMs, cachedTargetStride, cachedJitter)
                if (result.stepsEmitted > 0) {
                    GaitModel.onStep(core.lastStepAtMs)
                    onStep?.invoke(result.totalSteps, core.lastStepAtMs)
                }
            } catch (_: Throwable) {
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 停止模拟：速度归零并释放步时钟线程。
     *
     * 必须显式调用 —— 步时钟一旦启动就不再检查 active 状态，
     * 若只在配置轮询的 active 分支同步配置，停止模拟后 cachedSpeedMs 会保持旧值，
     * 线程继续按旧速度产步事件并推给宿主，表现为"已停止模拟但步数仍在涨"。
     * 再次 [syncConfig] 会自动重启线程（[ensureStarted]），步数状态保留。
     */
    fun stop() {
        synchronized(this) {
            cachedSpeedMs = 0.0
            handler?.removeCallbacksAndMessages(null)
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
            started = false
        }
    }

    /** libxposed 热重载前清理，防线程泄漏。 */
    fun shutdownForReload() {
        synchronized(this) {
            handler?.removeCallbacksAndMessages(null)
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
            started = false
        }
    }

    const val DEFAULT_TARGET_STRIDE = 0.85
    const val TICK_MS = 200L
}
