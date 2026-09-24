package com.suseoaa.locationspoofer.xposed.hooks.gait

import kotlin.math.max
import java.util.Random

/**
 * 步时钟核心 —— 计步单一真值源（纯 JVM，无 Android 依赖，可单测）
 *
 * 解决的三个问题（见 reports/校园跑适配-P0-3至5复查与修复计划.md §2.1）：
 * 1. detector 与 counter 事件率脱钩：两路数据由同一次 [onTick] 同源产出，
 *    counter 值恒等于 baseline + 已推送的 detector 事件数，取哪一路仲裁都对得上。
 * 2. 基线硬编码/冷启动重置：基线由会话绑定（startTimestamp）解析，
 *    同一次运动内跨进程、跨 App 重启保持一致；App 冷启动重新锚定属正常语义。
 * 3. 暂停后恢复步数突跳：暂停期间不再用"全程墙钟 × cadence"闭式补算，
 *    恢复后从下一次步间隔继续，历史步数分毫不动。
 *
 * 线程模型：[onTick] 只允许单一线程调用（Android 壳侧的步时钟线程）；
 * 读侧（totalSteps/currentCadence/lastStepAtMs）为 @Volatile，任意线程可读。
 */
class StepClockCore(private val rng: Random = Random(System.nanoTime())) {

    /** 已推送的 STEP_DETECTOR 事件数（每步 +1）。这一句注释就是不变量 #1 的全部内容。 */
    @Volatile
    var dispatchedSteps: Long = 0L
        private set

    /** 本次会话的计步基线（真实 counter 首帧 / config 指定 / 稳定派生，见 [bindSession]）。 */
    @Volatile
    var stepBaseline: Long = 0L
        private set

    /** 当前瞬时步频（步/分钟），静止时为 0。 */
    @Volatile
    var currentCadence: Int = 0
        private set

    /** 最近一步的发生时刻（单调毫秒），供步态波形引擎对齐冲击脉冲相位。 */
    @Volatile
    var lastStepAtMs: Long = 0L
        private set

    /** counter 口径总步数 = 基线 + 已推送 detector 数（不变量 #1）。 */
    val totalSteps: Long
        get() = stepBaseline + dispatchedSteps

    private var sessionStartTimestamp = 0L
    private var baselineOverride = -1L
    /** 基线锁定:采纳过真实 counter(或显式基线)后,任何会话切换都不得改写基线值。 */
    private var baselineLocked = false

    /**
     * 派生基线的锚点(首次绑定的 startTimestamp)。
     * 派生值必须固定基于它,而不是每次会话的 startTimestamp ——
     * 否则摇杆/UI 刷新配置导致 startTimestamp 变化时,派生值随权重项跳变,
     * 宿主会把跳升记成新增步数(与回退同类问题)。固定锚点同时保证跨进程重启派生一致。
     */
    private var anchorStartTimestamp = 0L
    private var nextStepAtMs = 0L
    private var lastOnTickMs = 0L
    private var cadenceDrift = 0.0

    /**
     * 绑定运动会话。startTimestamp 变化（新一次运动）时重置步计数并解析新基线；
     * startTimestamp 不变（App 进程重启/热重载）时保留全部状态，步数跨重启连续。
     *
     * @param startTimestamp    本次运动开始时间（config.start_timestamp，墙钟毫秒）
     * @param baselineOverride  config.step_baseline 显式基线（>=0 时优先于派生值）
     */
    fun bindSession(startTimestamp: Long, baselineOverride: Long) {
        synchronized(this) {
            if (startTimestamp == sessionStartTimestamp && sessionStartTimestamp != 0L) return
            // 单调锚:会话切换不得让 counter 回退。
            // 微信等按"counter 差值"累计步数的宿主,一旦看到回退就会把跳变记成新增步数,
            // 表现为"每秒暴增几千步"。因此新基线取 max(候选值, 当前已发出总步数)。
            val carried = totalSteps
            if (anchorStartTimestamp == 0L) anchorStartTimestamp = startTimestamp
            this.sessionStartTimestamp = startTimestamp
            this.baselineOverride = baselineOverride
            val derived = 2000L + (anchorStartTimestamp / 1000L % 800L)
            this.stepBaseline = when {
                baselineOverride >= 0 -> maxOf(baselineOverride, carried)
                baselineLocked -> maxOf(stepBaseline, carried)
                else -> maxOf(derived, carried)
            }
            if (baselineOverride >= 0) baselineLocked = true
            this.dispatchedSteps = 0L
            this.nextStepAtMs = 0L
            this.lastStepAtMs = 0L
            this.lastOnTickMs = 0L
            this.cadenceDrift = 0.0
        }
    }

    /**
     * 采纳真实 TYPE_STEP_COUNTER 首帧值作为基线（三级回退中最优先的一级）。
     * 仅在尚未采纳过、且尚未推送任何步时接受，防止运动中途基线切换造成总步跳变。
     */
    fun adoptRealCounterBaseline(realValue: Long): Boolean {
        synchronized(this) {
            if (baselineLocked || dispatchedSteps > 0L || realValue < 0L) return false
            // 只增不减:真实 counter 低于已发出的值时不采纳新值(否则立即造成回退跳变)
            stepBaseline = maxOf(realValue, totalSteps)
            baselineLocked = true
            return true
        }
    }

    /**
     * 步时钟推进。由 Android 壳以固定节奏（约 5 Hz）调用；内部按当前 cadence
     * 决定本 tick 是否越过了一次步事件的时间点。
     *
     * @param nowMs        单调时钟毫秒（SystemClock.elapsedRealtime()）
     * @param speedMs      当前伪造速度（m/s），<= 0.05 视为暂停
     * @param targetStride 目标步幅（米），决定 cadence = speed*60/stride
     * @param jitter       是否叠加 ±8% 步间隔抖动（真实步态节律不均匀）
     * @return 本 tick 推送的步数（0 或 1；补步场景可能 >1）与当前总步数
     */
    fun onTick(nowMs: Long, speedMs: Double, targetStride: Double, jitter: Boolean): TickResult {
        // 时钟回拨保护（测试时间倒流 / 系统时钟异常）：重锚步相位，绝不补历史步
        if (nowMs < lastOnTickMs) {
            nextStepAtMs = nowMs
        }
        lastOnTickMs = nowMs

        updateCadence(speedMs, targetStride)

        var emitted = 0L
        if (speedMs > PAUSE_SPEED_EPS) {
            if (nextStepAtMs == 0L) {
                // 起步：立即迈出第一步
                nextStepAtMs = nowMs
            }
            val intervalBase = 60000.0 / currentCadence.coerceAtLeast(1)
            var guard = 0
            while (nextStepAtMs <= nowMs && guard < MAX_CATCHUP_STEPS) {
                dispatchedSteps++
                lastStepAtMs = max(nextStepAtMs, lastStepAtMs)
                emitted++
                val factor = if (jitter) 1.0 + STEP_JITTER_SIGMA * rng.nextGaussian() else 1.0
                nextStepAtMs += (intervalBase * factor).toLong().coerceAtLeast(MIN_STEP_INTERVAL_MS)
                guard++
            }
        } else {
            // 暂停：冻结计数，把下一步顶到"暂停结束后一个步间隔"，恢复时不补步
            val intervalBase = 60000.0 / RUNNING_CADENCE_LOW
            nextStepAtMs = max(nextStepAtMs, nowMs + intervalBase.toLong())
        }
        return TickResult(emitted, totalSteps)
    }

    /**
     * cadence 计算（步幅优先）：
     *   cadence = speed × 60 / stride，clamp 到生理区间 [90, 195]。
     * 公式本身保证步幅 = speed/(cadence/60) 落在目标值附近（不变量 #2），
     * clamp 只防极端速度。跑步叠加 ±6 SPM 的 OU 游走，严禁全程恒定步频。
     */
    private fun updateCadence(speedMs: Double, targetStride: Double) {
        if (speedMs <= PAUSE_SPEED_EPS) {
            currentCadence = 0
            return
        }
        val stride = targetStride.coerceIn(MIN_STRIDE_M, MAX_STRIDE_M)
        val raw = speedMs * 60.0 / stride
        val clamped = raw.coerceIn(MIN_CADENCE_SPM.toDouble(), MAX_CADENCE_SPM.toDouble())
        cadenceDrift += CADENCE_DRIFT_SIGMA * rng.nextGaussian() - CADENCE_DRIFT_ALPHA * cadenceDrift
        cadenceDrift = cadenceDrift.coerceIn(-CADENCE_DRIFT_MAX, CADENCE_DRIFT_MAX)
        currentCadence = (clamped + cadenceDrift).toInt().coerceIn(MIN_CADENCE_SPM, MAX_CADENCE_SPM + 10)
    }

    /** 兼容旧调用点：按速度与目标步幅给出生理步频（不推进任何状态）。 */
    fun computeCadenceStatic(speedMs: Double, targetStride: Double): Int {
        val stride = targetStride.coerceIn(MIN_STRIDE_M, MAX_STRIDE_M)
        return (speedMs * 60.0 / stride)
            .coerceIn(MIN_CADENCE_SPM.toDouble(), MAX_CADENCE_SPM.toDouble())
            .toInt()
    }

    data class TickResult(val stepsEmitted: Long, val totalSteps: Long)

    companion object {
        /** 低于该速度（m/s）视为静止/暂停，冻结步数。 */
        const val PAUSE_SPEED_EPS = 0.05

        /** 步间隔抖动强度（±8%），对应步态节律统计量 maxDiff/minDiff/avgDiff 的非零方差。 */
        const val STEP_JITTER_SIGMA = 0.08

        /** 单个 tick 允许的最大补步数（防止时钟异常后死循环）。 */
        private const val MAX_CATCHUP_STEPS = 50

        /** 最小步间隔 200 ms（300 SPM，物理不可能更快的保护下限）。 */
        private const val MIN_STEP_INTERVAL_MS = 200L

        /** 暂停期间推算恢复间隔用的参考步频。 */
        private const val RUNNING_CADENCE_LOW = 150

        const val MIN_CADENCE_SPM = 90
        const val MAX_CADENCE_SPM = 195
        const val MIN_STRIDE_M = 0.60
        const val MAX_STRIDE_M = 1.50

        /** cadence OU 游走参数：σ=0.3/步、回归系数 0.02，稳态标准差约 ±2 SPM，钳位 ±6。 */
        private const val CADENCE_DRIFT_SIGMA = 0.3
        private const val CADENCE_DRIFT_ALPHA = 0.02
        private const val CADENCE_DRIFT_MAX = 6.0
    }
}
