package com.suseoaa.locationspoofer.xposed.hooks.gait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 配速曲线引擎单测。
 * 校验：确定性（多调用点一致）、起步平滑、巡航区间钳位、微停顿形态、无恒速。
 */
class PaceProfileTest {

    companion object {
        const val START = 1_000_000L
        const val BASE = 3.0
    }

    private fun sampleSpeeds(durationSec: Double, stepMs: Long = 500, minPace: Double = 0.0, maxPace: Double = 0.0, patches: Boolean = true): List<Double> {
        val out = ArrayList<Double>()
        var t = START
        val end = START + (durationSec * 1000).toLong()
        while (t < end) {
            out.add(PaceProfile.instantSpeed(t, START, BASE, minPace, maxPace, patches))
            t += stepMs
        }
        return out
    }

    @Test
    fun `确定性 同参数同时刻结果一致`() {
        // 距离积分与步时钟在不同调用点重复计算,必须逐位一致
        repeat(50) {
            val t = START + (it * 977L)
            val a = PaceProfile.instantSpeed(t, START, BASE, 300.0, 360.0, true)
            val b = PaceProfile.instantSpeed(t, START, BASE, 300.0, 360.0, true)
            assertEquals(a, b, 0.0)
        }
    }

    @Test
    fun `起步平滑爬升且低于巡航`() {
        val speeds = sampleSpeeds(60.0, stepMs = 1000)
        // 前 2s 接近 0
        assertTrue("起步首秒速度 ${speeds[1]} 应接近 0", speeds[1] < 0.5)
        // 起步段单调不减
        var prev = -1.0
        for (v in speeds.take(34)) {
            assertTrue("起步爬升被破坏 $prev -> $v", v >= prev - 1e-9)
            prev = v
        }
        // 35s 达到巡航水平
        assertTrue("35s 速度 ${speeds[34]} 应接近巡航", speeds[34] > BASE * 0.85)
    }

    @Test
    fun `巡航段配速区间钳位`() {
        // pace [300, 360] s/km → 速度 [1000/360, 1000/300] = [2.778, 3.333]
        val lo = 1000.0 / 360.0
        val hi = 1000.0 / 300.0
        val speeds = sampleSpeeds(600.0, stepMs = 500, minPace = 300.0, maxPace = 360.0)
        val cruise = speeds.drop(71)   // 500ms 步长:跳过起步 35s(70 个采样)
        for (v in cruise) {
            assertTrue("瞬时速度 $v 越界 [$lo, $hi]", v in lo - 1e-9..hi + 1e-9)
        }
    }

    @Test
    fun `巡航速度非恒定`() {
        val speeds = sampleSpeeds(600.0, stepMs = 500)
        val cruise = speeds.drop(36)
        val mean = cruise.average()
        val std = kotlin.math.sqrt(cruise.map { (it - mean) * (it - mean) }.average())
        assertTrue("巡航波动标准差 $std 过小（恒速是机器特征）", std > 0.02)
        assertTrue("巡航波动标准差 $std 过大（失控）", std < 1.0)
    }

    @Test
    fun `速度曲线平滑无跳变`() {
        val speeds = sampleSpeeds(600.0, stepMs = 500)
        for (i in 1 until speeds.size) {
            val dv = abs(speeds[i] - speeds[i - 1])
            assertTrue("相邻 500ms 速度跳变 $dv @i=$i", dv < 0.5)
        }
    }

    @Test
    fun `积分与瞬时速度自洽`() {
        // 数值积分 600s 的结果应接近 瞬时速度采样和（同一确定性曲线）
        val stepMs = 1000L
        var dist = 0.0
        var t = START
        val end = START + 600_000L
        while (t < end) {
            dist += PaceProfile.instantSpeed(t, START, BASE, 300.0, 360.0, true) * stepMs / 1000.0
            t += stepMs
        }
        // 恒速基线 3.0 × 600 = 1800m;曲线含起步与波动,应在 1500-1850 之间
        assertTrue("积分里程 $dist 偏离预期", dist in 1500.0..1850.0)
    }
}
