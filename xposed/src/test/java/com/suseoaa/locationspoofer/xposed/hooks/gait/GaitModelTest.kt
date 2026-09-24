package com.suseoaa.locationspoofer.xposed.hooks.gait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 步态波形引擎单测 —— 覆盖不变量 #3/#4/#9 与静止/压力/陀螺约束。
 */
class GaitModelTest {

    companion object {
        const val SAMPLE_HZ = 200.0
        const val DURATION_S = 60.0
        const val SPEED = 3.0
        const val CADENCE = 180          // SPM（仅作为波形输入；步相位由 StepClockCore 回放）
    }

    private data class Sample(val stepTimes: List<Long>, val linear: List<FloatArray>, val times: List<Long>)

    /** 采样步态序列：先跑 StepClockCore 产生步相位，再逐点采样 linearAcceleration */
    private fun sample(durationS: Double = DURATION_S, speed: Double = SPEED): Sample {
        val core = StepClockCore()
        core.bindSession(1_000L, 0L)
        val stepTimes = ArrayList<Long>()
        var now = 1_000L
        val end = (durationS * 1000).toLong() + 1_000L
        while (now < end) {
            now += 20L  // 50Hz 步时钟 tick，保证步相位精度
            val r = core.onTick(now, speed, 0.85, jitter = true)
            if (r.stepsEmitted > 0) stepTimes.add(core.lastStepAtMs)
        }

        val times = ArrayList<Long>()
        val linear = ArrayList<FloatArray>()
        val dt = (1000 / SAMPLE_HZ).toLong()
        var t = 1_000L
        while (t < end) {
            val stepAt = stepTimes.lastOrNull { it <= t }
            if (stepAt != null && stepAt != GaitModel.lastStepAtMs) GaitModel.onStep(stepAt)
            times.add(t)
            linear.add(GaitModel.linearAcceleration(t, speed, core.currentCadence))
            t += dt
        }
        return Sample(stepTimes, linear, times)
    }

    private fun modulus(v: FloatArray): Double = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])

    @Test
    fun `accel 恒等于 linear 加 gravity 不变量3`() {
        // 同一时刻三种口径必须严格自洽（GaitModel 对同毫秒线性加速度有缓存）
        repeat(100) { i ->
            val t = 1_000L + i * 37L
            val lin = GaitModel.linearAcceleration(t, SPEED, CADENCE)
            val g = GaitModel.gravityVector(t)
            val acc = GaitModel.withGravity(t, SPEED, CADENCE)
            assertTrue(abs((lin[0] + g[0] - acc[0]).toDouble()) < 1e-3)
            assertTrue(abs((lin[1] + g[1] - acc[1]).toDouble()) < 1e-3)
            assertTrue(abs((lin[2] + g[2] - acc[2]).toDouble()) < 1e-3)
        }
    }

    @Test
    fun `冲击模量峰值对齐真机记录`() {
        // 真机跑步记录(传感器 App 实测):x 主冲击 4-8g,合成模量峰值 ~70-80 m/s²
        val s = sample()
        val peak = s.linear.maxOf { modulus(it) }
        assertTrue("峰值 $peak m/s² 应在 45-90 区间", peak in 45.0..90.0)
    }

    @Test
    fun `x 主导且脉冲双极`() {
        // 真机形态:x 正主脉冲 + 负回摆(双极);z 围绕重力双向
        val s = sample()
        val xs = s.linear.map { it[0].toDouble() }
        val zs = s.linear.map { it[2].toDouble() }
        assertTrue("x 正峰 ${xs.max()} 应 >40", xs.max() > 40.0)
        assertTrue("x 负回摆 ${xs.min()} 应 <-5", xs.min() < -5.0)
        assertTrue("z 双极:负向 ${zs.min()} 应 <0(围绕重力摆动)", zs.min() < -2.0)
        assertTrue("z 正向 ${zs.max()} 应 >17", zs.max() > 17.0)
    }

    @Test
    fun `脉冲间基线安静`() {
        // 取脉冲间隔中点附近(t+0.25s,两步之间)的模量,应远小于峰值
        val s = sample()
        val mids = s.times.indices.filter { i ->
            val dt = (s.times[i] - (s.stepTimes.lastOrNull { it <= s.times[i] } ?: 0L))
            dt in 220..300   // 步间隔 ~308ms,此处为两步之间
        }
        assertTrue(mids.isNotEmpty())
        val quiet = mids.maxOf { modulus(s.linear[it]) }
        val peak = s.linear.maxOf { modulus(it) }
        assertTrue("脉冲间 $quiet 应显著小于峰值 $peak", quiet < peak * 0.35)
    }

    @Test
    fun `判峰计数等于步数 不变量4`() {
        // 模拟报告 §5.4 判峰：模量超阈值 + 250ms 去抖，计数应与步事件数一致
        val s = sample()
        val limitAcc = 30.0   // ~3g 判峰阈值:主冲击 70+ 可达,回摆谷 ~28 被滤
        var lastPeakAt = -1000L
        var peaks = 0
        for ((i, v) in s.linear.withIndex()) {
            val t = s.times[i]
            if (modulus(v) > limitAcc && t - lastPeakAt > 250) {
                peaks++
                lastPeakAt = t
            }
        }
        val stepCount = s.stepTimes.size.toDouble()
        assertTrue("判峰 $peaks vs 步事件 $stepCount", abs(peaks - stepCount) / stepCount < 0.10)
    }

    @Test
    fun `FFT 无单频峰 不变量9`() {
        val s = sample()
        val n = s.linear.size
        // 去均值
        val mean = DoubleArray(3)
        for (v in s.linear) { mean[0] += v[0].toDouble(); mean[1] += v[1].toDouble(); mean[2] += v[2].toDouble() }
        for (k in 0..2) mean[k] /= n

        // DFT 扫频 0.8-5 Hz，步长 0.05 Hz。
        // 刻意避开 <0.8Hz 的低频段：OU 慢漂（身体姿态/多径漂移的物理特征）能量集中在那里。
        val freqs = ArrayList<Double>()
        var f = 0.8
        while (f <= 8.0) { freqs.add(f); f += 0.05 }
        val powers = DoubleArray(freqs.size)
        for ((fi, freq) in freqs.withIndex()) {
            var re = 0.0; var im = 0.0
            for ((i, v) in s.linear.withIndex()) {
                val ang = 2.0 * PI * freq * (i / SAMPLE_HZ)
                val proj = (v[0] - mean[0].toFloat()) + (v[1] - mean[1].toFloat()) + (v[2] - mean[2].toFloat())
                re += proj * cos(ang)
                im -= proj * sin(ang)
            }
            powers[fi] = (re * re + im * im) / n
        }

        // 判据（对照实验校准：纯正弦基线 peakShare=0.997、2f 谐波=噪底；
        // 本实现 peakShare≈0.87、2f 谐波高出噪底 3 个数量级）：
        // 1) 峰带占比 < 0.95 —— 合成正弦把全部带内能量集中在单个 bin；
        //    注意基频峰本身（步频 ~3Hz）是生理特征（真实跑步就是近周期脉冲串），
        //    占比高不是机器特征，"只有基频、无谐波、无噪底"才是。
        // 2) 2f 谐波显著 —— exp 冲击脉冲必然产生谐波族；纯正弦没有。
        val total = powers.sum()
        val peakIdx = powers.indices.maxBy { powers[it] }
        val exclFrom = (peakIdx - 3).coerceAtLeast(0)          // 峰 ±0.15Hz
        val exclTo = (peakIdx + 3).coerceAtLeast(powers.size - 1)
        var peakBand = 0.0
        for (i in exclFrom..exclTo) peakBand += powers[i]
        val peakShare = peakBand / total
        assertTrue("峰带能量占比 $peakShare，信号形态过于接近单一正弦", peakShare < 0.95)

        val peakFreq = freqs[peakIdx]
        val harmonicIdx = freqs.indices.filter { abs(freqs[it] - 2 * peakFreq) < 0.075 }
        assertTrue("2f 超出扫描范围", harmonicIdx.isNotEmpty())
        val harmonicPower = harmonicIdx.maxOf { powers[it] }
        val noiseFloor = powers.filterIndexed { i, _ -> abs(i - peakIdx) > 10 }.sorted()[powers.size / 8]
        assertTrue("2f 谐波缺失（脉冲形状过于纯化，harmonic=$harmonicPower floor=$noiseFloor）", harmonicPower > noiseFloor * 3.0)
    }

    @Test
    fun `静止时线性加速度呈微噪 绝不全零`() {
        val s = sample(durationS = 10.0, speed = 0.0)
        var maxMod = 0.0
        var nonZero = 0
        for (v in s.linear) {
            val m = modulus(v)
            if (m > 1e-4) nonZero++
            if (m > maxMod) maxMod = m
        }
        assertTrue("静止时应存在微噪（all_sensor_zero_while_valid 风控）", nonZero > s.linear.size * 0.99)
        assertTrue("静止微噪峰值 $maxMod 过大", maxMod < 2.0)
    }

    @Test
    fun `气压与海拔经大气公式自洽 不变量6`() {
        // 同一时刻（同漂移值）查询不同海拔：差值必须纯由高度贡献（25m ≈ 3 hPa）
        val p0 = GaitModel.pressure(1000L, 0.0)
        val p25 = GaitModel.pressure(1000L, 25.0)
        val diff = p0 - p25
        assertTrue("25m 高度差压差 $diff hPa", diff in 2.5..3.5)
        // 绝对值在合理量级（含 ±15m 慢漂）
        assertTrue("p0=$p25", p0 in 1008.0..1018.0)
    }

    @Test
    fun `陀螺仪幅度克制且旋转矢量模为1`() {
        val gyro = GaitModel.gyroscope(2000L, SPEED, CADENCE)
        val gyroMod = sqrt(gyro[0].toDouble() * gyro[0] + gyro[1].toDouble() * gyro[1] + gyro[2].toDouble() * gyro[2])
        assertTrue("陀螺模量 $gyroMod 过大（剧烈旋转=代步工具嫌疑）", gyroMod < 3.0)

        val uncal = GaitModel.gyroscopeUncalibrated(2000L, SPEED, CADENCE)
        assertEquals(6, uncal.size)

        val rv = GaitModel.rotationVector(2000L, SPEED, CADENCE, useGeographic = true)
        assertEquals(5, rv.size)
        val q = rv[0].toDouble() * rv[0] + rv[1].toDouble() * rv[1] + rv[2].toDouble() * rv[2] + rv[3].toDouble() * rv[3]
        assertTrue("四元数模 $q 偏离 1", abs(q - 1.0) < 0.01)

        val game = GaitModel.rotationVector(2000L, SPEED, CADENCE, useGeographic = false)
        assertEquals(-1f, game[4])
    }
}
