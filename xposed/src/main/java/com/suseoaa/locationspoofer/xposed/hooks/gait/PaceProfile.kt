package com.suseoaa.locationspoofer.xposed.hooks.gait

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 配速曲线引擎（纯 JVM，无状态纯函数，可单测）
 *
 * 消灭恒定速度（修复计划 P1-1）：真实跑步的配速 = 起步平滑加速 + 巡航随机波动
 * + 偶发微降速（等红灯/避开行人），全程方差显著非零。恒定速度（配速方差≈0）
 * 是统计上最廉价的机器特征。
 *
 * 设计要点：
 * - **确定性**：所有"随机"都来自 splitmix64 哈希 (seed=startMs, index)，
 *   任意调用点、任意时刻重复计算得到同一值 —— RouteEngine 的距离积分与
 *   步时钟的 cadence 引用同一曲线，不会互相打架（不变量的前提）。
 * - **无周期**：波动用 5 秒时间片 + 片间 smoothstep 插值，片值相互独立；
 *   不使用任何 sin/cos 叠加（那会在速度域留下离散谱线）。
 * - **配速区间 clamp 只作用于巡航段**：真实跑步起步阶段的瞬时配速超出
 *   "有效配速范围"是正常生理形态（App 判的是时段配速），起步段钳位反而假。
 */
object PaceProfile {

    /** 起步加速时长（秒），smoothstep 从 0 爬升到目标速度 */
    const val RAMP_SEC = 35.0

    /** 巡航波动强度（±6% 1σ，叠加时间片噪声后瞬时约 ±12% 峰峰） */
    const val WAVE_SIGMA = 0.06

    /** 波动时间片（毫秒）：片值独立，片间平滑插值 */
    const val WAVE_SLICE_MS = 5000.0

    /** 微停顿：每片时长（秒）与出现概率 —— 平均每 ~14 分钟一次 */
    const val PATCH_SLICE_SEC = 150.0
    const val PATCH_PROBABILITY = 0.18

    /** 微停顿窗：3-5 秒，降速到目标的 55-70%（等红灯/避让的形态） */
    const val PATCH_MIN_SEC = 3.0
    const val PATCH_MAX_SEC = 5.0
    // 深度调浅:配速染色下 0.55-0.70 深度(≈8-10 min/km)会触发轨迹红段;
    // 0.85-0.92 ≈ 6 min/km,仍在多数 App 绿色区间内,保留"节奏波动"形态
    const val PATCH_DEPTH_MIN = 0.85
    const val PATCH_DEPTH_MAX = 0.92

    /** 边沿平滑宽度（秒）：窗边缘 1 秒内平滑过渡，杜绝 GPS speed 字段突跳 */
    const val PATCH_EDGE_SEC = 1.0

    /** 起步曲线前移量（秒）：让 t=0 时刻速度非零（RouteEngine 对上游的行为契约） */
    const val RAMP_LEAD_SEC = 1.75

    /**
     * 瞬时速度（m/s）。
     *
     * @param nowMs       当前单调毫秒
     * @param startMs     运动开始时刻（config.start_timestamp）
     * @param baseSpeedMs 目标巡航速度
     * @param minPaceSecPerKm 最快配速（秒/公里，即速度上限）；0 = 不限制
     * @param maxPaceSecPerKm 最慢配速（秒/公里，即速度下限）；0 = 不限制
     * @param slowPatches 是否启用微停顿
     */
    fun instantSpeed(
        nowMs: Long,
        startMs: Long,
        baseSpeedMs: Double,
        minPaceSecPerKm: Double,
        maxPaceSecPerKm: Double,
        slowPatches: Boolean
    ): Double {
        val elapsedSec = (nowMs - startMs) / 1000.0
        // 仅负时间早退；elapsed==0 必须走到 ramp（前移后速度非零，上游行为契约）
        if (elapsedSec < 0.0 || baseSpeedMs <= 0.0) return 0.0

        var factor = 1.0

        // 起步：smoothstep 0→1（曲线整体前移 RAMP_LEAD_SEC，保证 t=0 速度非零；
        // 不 clamp 配速区间 —— 起步慢是正常生理形态）
        if (elapsedSec < RAMP_SEC) {
            factor *= smoothstep((elapsedSec + RAMP_LEAD_SEC) / RAMP_SEC)
        } else {
            // 巡航波动（仅巡航段；起步段叠加会破坏单调爬升）
            factor *= 1.0 + waveAt(startMs, elapsedSec)
            if (slowPatches) factor *= patchFactor(startMs, elapsedSec)
        }

        var v = baseSpeedMs * factor

        // 配速区间（仅巡航段生效）。pace 大 = 慢：
        // minPace（最快）→ 速度上限 1000/minPace；maxPace（最慢）→ 速度下限 1000/maxPace
        if (elapsedSec >= RAMP_SEC) {
            if (minPaceSecPerKm > 0.0) v = min(v, 1000.0 / minPaceSecPerKm)
            if (maxPaceSecPerKm > 0.0) v = max(v, 1000.0 / maxPaceSecPerKm)
        }
        return v.coerceAtLeast(0.0)
    }

    /** 巡航波动：5 秒片独立值，smoothstep 插值 → 连续、非周期、确定性 */
    internal fun waveAt(startMs: Long, elapsedSec: Double): Double {
        val t = elapsedSec * 1000.0 / WAVE_SLICE_MS
        val i = floor(t).toLong()
        val frac = t - floor(t)
        val a = unitNoise(startMs, i)
        val b = unitNoise(startMs, i + 1)
        return (a + (b - a) * smoothstep(frac)) * WAVE_SIGMA * 2.0 - WAVE_SIGMA
    }

    /**
     * 解析近似里程（米）：∫instantSpeed dt 的闭式近似 —— 起步段对 smoothstep
     * 精确积分（∫x²(3-2x)dx = x³ - x⁴/2），巡航段按基速 × 1.0（波动均值≈0）。
     * 供 RouteEngine 在积分状态重置（会话切换/单次冷调用）时补齐历史，
     * 保证 calculateCurrentPosition 对任意 now 的单次调用契约成立。
     */
    fun approximateDistance(elapsedSec: Double, baseSpeedMs: Double): Double {
        if (elapsedSec <= 0.0 || baseSpeedMs <= 0.0) return 0.0
        return if (elapsedSec <= RAMP_SEC) {
            val x = (elapsedSec + RAMP_LEAD_SEC) / RAMP_SEC
            val integral = (x * x * x - x * x * x * x / 2.0).coerceAtLeast(0.0) * RAMP_SEC
            baseSpeedMs * integral
        } else {
            baseSpeedMs * RAMP_SEC * 0.5 + baseSpeedMs * (elapsedSec - RAMP_SEC)
        }
    }

    /**
     * 微停顿：每 150 秒片以 18% 概率出现一个 3-5 秒、55-70% 深度的降速窗，
     * 窗边缘 1 秒 smooth 过渡。全确定性 —— 同参数重复调用结果一致。
     */
    internal fun patchFactor(startMs: Long, elapsedSec: Double): Double {
        val patch = floor(elapsedSec / PATCH_SLICE_SEC).toLong()
        if (unitNoise(startMs, patch * 7919L) > PATCH_PROBABILITY) return 1.0
        val offset = unitNoise(startMs, patch * 104729L) * (PATCH_SLICE_SEC - PATCH_MAX_SEC - PATCH_EDGE_SEC)
        val dur = PATCH_MIN_SEC + unitNoise(startMs, patch * 15485863L) * (PATCH_MAX_SEC - PATCH_MIN_SEC)
        val depth = PATCH_DEPTH_MIN + unitNoise(startMs, patch * 32452843L) * (PATCH_DEPTH_MAX - PATCH_DEPTH_MIN)
        val local = elapsedSec - patch * PATCH_SLICE_SEC
        val into = local - offset
        if (into < 0.0 || into > dur) return 1.0
        // 距最近窗沿的距离 → 0..1 → smoothstep
        val e = minOf(into, dur - into, PATCH_EDGE_SEC).coerceAtLeast(0.0) / PATCH_EDGE_SEC
        return depth + (1.0 - depth) * smoothstep(e)
    }

    private fun smoothstep(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    /** 确定性 [0,1) 噪声：splitmix64 终混，与 Random 状态无关 */
    private fun unitNoise(seed: Long, index: Long): Double {
        var z = seed xor (index * 0x9E3779B97F4A7C15UL.toLong())
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        z = z xor (z ushr 31)
        return (z and 0x1FFFFFFFFFFFFFL) / 9007199254740992.0
    }
}
