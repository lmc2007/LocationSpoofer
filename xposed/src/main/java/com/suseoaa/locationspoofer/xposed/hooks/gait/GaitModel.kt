package com.suseoaa.locationspoofer.xposed.hooks.gait

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow
import java.util.Random

/**
 * 步态波形引擎（纯 JVM，无 Android 依赖，可单测）
 *
 * 解决的三个问题（见 reports/校园跑适配-P0-3至5复查与修复计划.md §2.2）：
 * 1. 纯正弦单频峰：改为"触地冲击窄脉冲 + 宽带噪声基底 + OU 慢漂"，
 *    冲击形状为非对称指数（上升 ~20 ms / 衰减 ~70 ms），频谱无离散峰（不变量 #9）。
 * 2. 幅值不足：线性加速度模量峰值 18-40 m/s²（约 2-4 g，随速度插值），
 *    保证目标 App 的模量阈值判峰（sqrtAcc vs limitAcc）可达（不变量 #4 ——
 *    冲击时刻由 StepClockCore 的步事件驱动，判峰计数天然等于步数）。
 * 3. 相位回跳：一律使用调用方传入的单调毫秒（elapsedRealtime）与步事件相位，
 *    绝不使用墙钟取模；x 轴半频取消，三轴同频不同相。
 *
 * 自洽约束（不变量 #3）：[linearAcceleration] 与 [gravityVector] 是仅有的两个
 * 独立输出，[withGravity] = linear + gravity 逐采样成立；目标 App 若订阅
 * TYPE_LINEAR_ACCELERATION（去重力判峰）或 TYPE_ACCELEROMETER 均拿到同源的波形。
 */
object GaitModel {

    private val rng = Random(System.nanoTime())

    /** 步相位：最近一步发生时刻与左右脚奇偶，由步时钟在每次步事件时回调 [onStep]。 */
    @Volatile
    var lastStepAtMs: Long = 0L
        private set

    @Volatile
    private var stepParity = 0

    fun onStep(stepAtMs: Long) {
        lastStepAtMs = stepAtMs
        stepParity = 1 - stepParity
    }

    // ---- OU 慢漂状态（各物理量独立） ----
    private var driftLinX = 0.0
    private var driftLinY = 0.0
    private var driftLinZ = 0.0
    private var driftHeading = 0.0
    private var driftAltitude = 0.0
    private var driftMagX = 0.0
    private var driftMagY = 0.0
    private var driftMagZ = 0.0
    private var gyroBiasX = 0.0
    private var gyroBiasY = 0.0
    private var gyroBiasZ = 0.0

    /** 线性加速度（去重力），单位 m/s²。静止时仅剩微噪（真实手持特征，绝不全零）。
     *  同一毫秒重复调用返回缓存值 —— 保证 accel/linear/gravity 三口径在同刻严格自洽。 */
    fun linearAcceleration(nowMs: Long, speedMs: Double, cadenceSpm: Int): FloatArray {
        if (nowMs == cachedLinearMs) return cachedLinear.copyOf()
        val result = computeLinear(nowMs, speedMs, cadenceSpm)
        cachedLinearMs = nowMs
        cachedLinear = result
        return result.copyOf()
    }

    private fun computeLinear(nowMs: Long, speedMs: Double, cadenceSpm: Int): FloatArray {
        val moving = speedMs > MOVING_SPEED_EPS
        val tSec = (nowMs - lastStepAtMs) / 1000.0

        val lx: Double
        val ly: Double
        val lz: Double
        if (moving && cadenceSpm > 0) {
            // 真实跑步(对照传感器记录 App 实测截图,2026-09-22):
            // x 是主冲击轴,单步"触地冲击 + 回摆"脉冲对,峰值 4-8g,脉冲间基线安静;
            // y 双极交叉摆动 ±40 量级;z 围绕重力双向摆动 ±20(设备竖握,z 含重力)。
            val amp = (X_PEAK_BASE + speedMs * X_PEAK_PER_SPEED)
                .coerceIn(X_PEAK_MIN, X_PEAK_MAX)
            val s1 = impactShape(tSec)
            val s2 = impactShape(tSec - SWING_DELAY_S)
            lx = amp * (s1 - X_SWING_RATIO * s2)
            ly = Y_RATIO * amp * (impactShape(tSec - Y_DELAY_S) - Y_SWING_RATIO * impactShape(tSec - Y_SWING_DELAY_S))
            lz = Z_RATIO * amp * (impactShape(tSec - Z_DELAY_S) - Z_SWING_RATIO * impactShape(tSec - Z_SWING_DELAY_S))
        } else {
            lx = 0.0; ly = 0.0; lz = 0.0
        }

        // 噪声基底:运动 σ0.5(脉冲清晰稀疏的关键 —— 大噪声会把脉冲淹没成毛刺海),
        // 静止 σ0.15(真实静置微噪,绝不全零)
        val noiseSigma = if (moving) NOISE_SIGMA else IDLE_NOISE_SIGMA
        val driftAlpha = if (moving) DRIFT_ALPHA else IDLE_DRIFT_ALPHA
        val driftSigma = if (moving) DRIFT_SIGMA else IDLE_DRIFT_SIGMA
        driftLinX += driftSigma * rng.nextGaussian() - driftAlpha * driftLinX
        driftLinY += driftSigma * rng.nextGaussian() - driftAlpha * driftLinY
        driftLinZ += driftSigma * rng.nextGaussian() - driftAlpha * driftLinZ

        return floatArrayOf(
            (lx + driftLinX + noiseSigma * rng.nextGaussian()).toFloat(),
            (ly + driftLinY + noiseSigma * rng.nextGaussian()).toFloat(),
            (lz + driftLinZ + noiseSigma * rng.nextGaussian()).toFloat()
        )
    }

    private var cachedLinearMs = -1L
    private var cachedLinear = floatArrayOf(0f, 0f, 0f)

    /** 重力分量（简化：口袋竖持姿态，重力沿 -Z 输出 +9.8，含极微姿态晃动）。 */
    fun gravityVector(nowMs: Long): FloatArray {
        val wobble = 0.02 * sin(nowMs / 4000.0) + 0.02 * cos(nowMs / 7300.0)
        return floatArrayOf(0f, 0f, (9.80665 + wobble).toFloat())
    }

    /** TYPE_ACCELEROMETER 口径 = linear + gravity（不变量 #3，逐采样成立）。 */
    fun withGravity(nowMs: Long, speedMs: Double, cadenceSpm: Int): FloatArray {
        val lin = linearAcceleration(nowMs, speedMs, cadenceSpm)
        val g = gravityVector(nowMs)
        return floatArrayOf(lin[0] + g[0], lin[1] + g[1], lin[2] + g[2])
    }

    /**
     * 陀螺仪（rad/s）：摆臂/躯干旋转，与步频同频同形（冲击形状），相位滞后；
     * 叠加慢变零偏与白噪声。剧烈旋转会被判代步工具，幅度克制。
     */
    fun gyroscope(nowMs: Long, speedMs: Double, cadenceSpm: Int): FloatArray =
        gyroCore(nowMs, speedMs, cadenceSpm).first

    /** TYPE_GYROSCOPE_UNCALIBRATED 口径：6 元 = [x,y,z, biasX,biasY,biasZ]。 */
    fun gyroscopeUncalibrated(nowMs: Long, speedMs: Double, cadenceSpm: Int): FloatArray {
        val (values, bias) = gyroCore(nowMs, speedMs, cadenceSpm)
        return floatArrayOf(values[0], values[1], values[2], bias[0], bias[1], bias[2])
    }

    private fun gyroCore(nowMs: Long, speedMs: Double, cadenceSpm: Int): Pair<FloatArray, FloatArray> {
        val moving = speedMs > MOVING_SPEED_EPS
        val tSec = (nowMs - lastStepAtMs) / 1000.0
        val sign = if (stepParity == 0) 1.0 else -1.0

        val swing = if (moving) (GYRO_SWING_BASE + speedMs * GYRO_SWING_PER_SPEED)
            .coerceIn(GYRO_SWING_MIN, GYRO_SWING_MAX) else 0.0

        // 慢变零偏（真实 MEMS 器件都有 bias，uncal 口径必须携带）
        gyroBiasX += 0.002 * rng.nextGaussian() - 0.01 * gyroBiasX
        gyroBiasY += 0.002 * rng.nextGaussian() - 0.01 * gyroBiasY
        gyroBiasZ += 0.002 * rng.nextGaussian() - 0.01 * gyroBiasZ

        driftHeading += 0.02 * rng.nextGaussian() - 0.02 * driftHeading
        driftHeading = driftHeading.coerceIn(-0.3, 0.3)

        val x = swing * sign * impactShape(tSec)
        val y = swing * 0.4 * impactShape(tSec - 0.03)
        val z = swing * 0.3 * sign * impactShape(tSec - 0.05) + driftHeading * 0.1
        val n = if (moving) GYRO_NOISE_SIGMA else GYRO_NOISE_SIGMA * 0.4

        val values = floatArrayOf(
            (x + n * rng.nextGaussian()).toFloat(),
            (y + n * rng.nextGaussian()).toFloat(),
            (z + n * rng.nextGaussian()).toFloat()
        )
        val bias = floatArrayOf(gyroBiasX.toFloat(), gyroBiasY.toFloat(), gyroBiasZ.toFloat())
        return Pair(values, bias)
    }

    /**
     * 旋转矢量（四元数 x,y,z,w[,headingAcc]）：由小幅俯仰/滚转脉冲 + 慢漂航向合成。
     * 幅度刻意压小（<0.05 rad 姿态角）—— 手机在手里跑步的姿态变化本来就轻微。
     * 返回 5 元：ROTATION_VECTOR 用全部 5 元（第 5 元为航向精度估计，
     * 真实设备典型 0.01-0.9，-1 表示不可用）；GAME_ROTATION_VECTOR 只取前 4 元。
     */
    fun rotationVector(nowMs: Long, speedMs: Double, cadenceSpm: Int, useGeographic: Boolean): FloatArray {
        val tSec = (nowMs - lastStepAtMs) / 1000.0
        val sign = if (stepParity == 0) 1.0 else -1.0
        val moving = speedMs > MOVING_SPEED_EPS

        val roll = if (moving) 0.04 * sign * impactShape(tSec) else 0.0
        val pitch = if (moving) 0.03 * impactShape(tSec - 0.03) else 0.0
        // GAME_ROTATION_VECTOR 不含航向参考，yaw 恒 0；ROTATION_VECTOR 带慢漂航向
        val yaw = if (useGeographic) driftHeading else 0.0

        val q = eulerToQuaternion(roll, pitch, yaw)
        val headingAccuracy = if (useGeographic) 0.02f else -1f
        return floatArrayOf(q[0], q[1], q[2], q[3], headingAccuracy)
    }

    /**
     * 气压（hPa）：由有效海拔经国际标准大气公式反算（不变量 #6 —— pressure ↔ altitude 互推），
     * 海拔本体叠加 OU 慢漂模拟真实气压计的低频漂移。同一时刻重复查询返回同一漂移值，
     * 保证 pressure ↔ altitude 互推在同刻严格成立。
     */
    fun pressure(nowMs: Long, baseAltitudeM: Double): Double {
        val drift = currentAltitudeDrift(nowMs)
        val h = (baseAltitudeM + drift).coerceAtLeast(0.0)
        return SEA_LEVEL_PRESSURE_HPA * (1.0 - 2.25577e-5 * h).pow(5.25588)
    }

    private var driftEpochMs = -1L
    private fun currentAltitudeDrift(nowMs: Long): Double {
        if (nowMs != driftEpochMs) {
            driftAltitude += 0.5 * rng.nextGaussian() - 0.01 * driftAltitude
            driftAltitude = driftAltitude.coerceIn(-15.0, 15.0)
            driftEpochMs = nowMs
        }
        return driftAltitude
    }

    /** 磁场（μT）：地磁量级 35-48 μT + OU 慢漂。 */
    fun magneticField(nowMs: Long): FloatArray {
        driftMagX += 0.2 * rng.nextGaussian() - 0.02 * driftMagX
        driftMagY += 0.2 * rng.nextGaussian() - 0.02 * driftMagY
        driftMagZ += 0.2 * rng.nextGaussian() - 0.02 * driftMagZ
        return floatArrayOf(
            (EARTH_MAG_X + driftMagX).toFloat(),
            (EARTH_MAG_Y + driftMagY).toFloat(),
            (EARTH_MAG_Z + driftMagZ).toFloat()
        )
    }

    /**
     * 触地冲击形状：非对称指数脉冲，归一化到峰值 1.0。
     * f(t) = exp(-t/τ_decay) × (1 - exp(-t/τ_rise)) / RAW_PEAK。
     * 原始函数极值点 t* ≈ 30 ms 处 f_raw ≈ 0.506（不是直觉上的 ~0.84 ——
     * 衰减项在上升完成前已吃掉近半幅度），因此必须显式归一化，
     * 否则 IMPACT_PEAK_* 直接作为"模量峰值"会系统性偏低近一半。
     * 指数衰减的频谱为洛伦兹型（无离散峰），且与步事件严格同源（不变量 #4）。
     */
    fun impactShape(tSec: Double): Double {
        if (tSec < 0.0 || tSec > 0.5) return 0.0
        return exp(-tSec / IMPACT_DECAY_S) * (1.0 - exp(-tSec / IMPACT_RISE_S)) / IMPACT_RAW_PEAK
    }

    /** τ_rise=0.02, τ_decay=0.07 时原始形状的极值 ≈ 0.5061（解析解，见 KDoc）。 */
    const val IMPACT_RAW_PEAK = 0.5061

    /** 欧拉角(roll,pitch,yaw 弧度) → 四元数 (x,y,z,w)。 */
    private fun eulerToQuaternion(roll: Double, pitch: Double, yaw: Double): FloatArray {
        val cr = cos(roll / 2); val sr = sin(roll / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        return floatArrayOf(
            (sr * cp * cy - cr * sp * sy).toFloat(),
            (cr * sp * cy + sr * cp * sy).toFloat(),
            (cr * cp * sy - sr * sp * cy).toFloat(),
            (cr * cp * cy + sr * sp * sy).toFloat()
        )
    }

    private const val MOVING_SPEED_EPS = 0.05

    // 冲击脉冲形状参数
    const val IMPACT_RISE_S = 0.02
    const val IMPACT_DECAY_S = 0.07
    // ---- 步态脉冲形态(对照真机传感器记录校准,2026-09-22) ----
    // x 主冲击轴:峰值 4-8g;每步一对脉冲(触地 s1 + 回摆 s2 反向);
    // y 交叉双极摆动;z 围绕重力双向。脉冲间基线安静(低噪声)。
    const val X_PEAK_BASE = 40.0        // m/s² @ speed 0
    const val X_PEAK_PER_SPEED = 10.0   // m/s² per (m/s):speed 3 → 70 ≈ 7g
    const val X_PEAK_MIN = 35.0
    const val X_PEAK_MAX = 85.0
    const val X_SWING_RATIO = 0.18      // x 回摆(负)幅度
    const val SWING_DELAY_S = 0.16      // 回摆滞后

    const val Y_RATIO = 0.50            // y 峰值 ≈ 0.5×x
    const val Y_DELAY_S = 0.04
    const val Y_SWING_RATIO = 0.60      // y 双极(负半明显)
    const val Y_SWING_DELAY_S = 0.20

    const val Z_RATIO = 0.28            // z 摆幅 ≈ 0.28×x(围绕重力双向)
    const val Z_DELAY_S = 0.02
    const val Z_SWING_RATIO = 0.75
    const val Z_SWING_DELAY_S = 0.18

    const val NOISE_SIGMA = 0.5         // m/s²,运动白噪(脉冲间基线安静)
    const val IDLE_NOISE_SIGMA = 0.15   // m/s²,静止微噪(真实静置特征)

    // OU 漂移参数：运动时慢回归（身体姿态变化），静止时快回归（静置读数稳定）
    const val DRIFT_SIGMA = 0.3
    const val DRIFT_ALPHA = 0.05
    const val IDLE_DRIFT_SIGMA = 0.05
    const val IDLE_DRIFT_ALPHA = 0.5

    // 陀螺参数（rad/s）
    const val GYRO_SWING_BASE = 1.2
    const val GYRO_SWING_PER_SPEED = 0.2
    const val GYRO_SWING_MIN = 1.2
    const val GYRO_SWING_MAX = 2.0
    const val GYRO_NOISE_SIGMA = 0.05

    // 大气与地磁
    const val SEA_LEVEL_PRESSURE_HPA = 1013.25
    const val EARTH_MAG_X = 21.5   // μT（北向分量，量级参考中纬度）
    const val EARTH_MAG_Y = 4.5    // μT（东向分量）
    const val EARTH_MAG_Z = 38.0   // μT（垂直分量）
}
