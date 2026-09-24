package com.suseoaa.locationspoofer.xposed.hooks.gait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 步时钟核心单测 —— 覆盖修复计划中的不变量 #1/#2/#7 与基线三级回退。
 */
class StepClockCoreTest {

    /** 200 ms 节拍驱动（与 StepEventScheduler.TICK_MS 一致），返回累计 detector 计数与最后 counter 值 */
    private fun drive(
        core: StepClockCore,
        durationMs: Long,
        speedMs: Double,
        startNowMs: Long = 0L,
        tickMs: Long = 200L
        ): Pair<Long, Long> {
        var now = startNowMs
        val end = startNowMs + durationMs
        var lastTotal = core.totalSteps
        while (now < end) {
            now += tickMs
            lastTotal = core.onTick(now, speedMs, 0.85, jitter = true).totalSteps
        }
        return Pair(core.dispatchedSteps, lastTotal)
    }

    @Test
    fun `counter 与 detector 计数节奏一致且步数符合 cadence 预期`() {
        val core = StepClockCore()
        core.bindSession(startTimestamp = 1_000_000L, baselineOverride = 100L)

        val elapsedMs = 600_000L          // 10 分钟
        val speed = 3.0                    // m/s
        val (detected, counter) = drive(core, elapsedMs, speed)

        // 不变量 #1：counter == baseline + dispatchedSteps（同源，这里验证取值路径）
        assertEquals(100L + detected, counter)

        // 不变量 #2：步幅 = 距离 / 步数 落在生理区间
        // （cadence = clamp(3*60/0.85, 90, 195) = 195 → 步幅 ≈ 0.92m，自洽）
        val stride = elapsedMs / 1000.0 * speed / detected
        assertTrue("stride=$stride", stride in 0.70..1.10)

        // 瞬时 cadence 合理
        assertTrue(core.currentCadence in 90..205)
    }

    @Test
    fun `暂停后恢复不补步且无突跳`() {
        val core = StepClockCore()
        core.bindSession(1_000_000L, 0L)

        // 跑 5 分钟
        drive(core, 300_000L, 3.0)
        val stepsBeforePause = core.dispatchedSteps
        assertTrue(stepsBeforePause > 0)

        // 暂停 5 分钟（speed 0），时间轴必须连续
        drive(core, 300_000L, 0.0, startNowMs = 300_000L)
        assertEquals("暂停期间步数必须冻结", stepsBeforePause, core.dispatchedSteps)

        // 恢复：恢复后的第一个 tick 内步数增量不得超过 2（无突跳）
        val before = core.dispatchedSteps
        var now = 600_000L
        core.onTick(now + 200, 3.0, 0.85, true)
        val jump = core.dispatchedSteps - before
        assertTrue("恢复首 tick 步数跳变 $jump", jump <= 2)

        // 继续跑 5 分钟，总步数必须小于"一直没停"的参考场景
        drive(core, 300_000L, 3.0, startNowMs = 600_000L)
        val reference = StepClockCore()
        reference.bindSession(1_000_000L, 0L)
        drive(reference, 900_000L, 3.0)
        assertTrue(
            "暂停场景 ${core.dispatchedSteps} 应少于连续场景 ${reference.dispatchedSteps}",
            core.dispatchedSteps < reference.dispatchedSteps * 0.75
        )
    }

    @Test
    fun `同会话重复 bindSession 保留状态 跨进程重启步数连续`() {
        val core = StepClockCore()
        core.bindSession(1_000_000L, 500L)
        drive(core, 120_000L, 3.0)
        val before = core.totalSteps

        // App 进程重启：hook 重新注入，config 的 start_timestamp 未变 → 状态保留
        core.bindSession(1_000_000L, 500L)
        assertEquals(before, core.totalSteps)

        // 新一次运动：start_timestamp 变化 → 会话内计数归零，但 counter 必须保持单调。
        // 语义依据：真实 TYPE_STEP_COUNTER 是"开机累计、永不回退"；宿主按 counter 差值累计步数，
        // 一旦回退就会把跳变记成新增步数（微信步数暴增 bug 的根因）。
        core.bindSession(9_000_000L, 500L)
        assertEquals("新会话 counter 必须持平而非回落到基线", before, core.totalSteps)
        assertEquals(0L, core.dispatchedSteps)
    }

    @Test
    fun `真实 counter 基线只在首次且未推步时采纳`() {
        val core = StepClockCore()
        core.bindSession(1_000_000L, -1L)   // 无显式基线 → 派生
        val derived = core.totalSteps

        assertTrue(core.adoptRealCounterBaseline(4321L))
        assertEquals(4321L, core.stepBaseline)

        drive(core, 10_000L, 3.0)
        val mid = core.dispatchedSteps
        assertTrue(mid > 0)

        // 已推步：再次采纳必须被拒（防止中途基线切换造成总步跳变）
        assertFalse(core.adoptRealCounterBaseline(9999L))
        assertEquals(4321L + mid, core.totalSteps)

        // config 显式基线优先：bindSession 时即锁定
        val core2 = StepClockCore()
        core2.bindSession(2_000_000L, 777L)
        assertFalse(core2.adoptRealCounterBaseline(4321L))
        assertEquals(777L, core2.stepBaseline)
    }

    // ---------- 微信步数暴增 bug 的回归防线:counter 必须永远单调 ----------

    @Test
    fun `会话频繁切换 counter 永不回退`() {
        val core = StepClockCore()
        core.bindSession(1_000L, -1L)
        drive(core, 60_000L, 3.0)
        val before = core.totalSteps
        assertTrue("应有步数产出", before > 0)

        // 模拟摇杆/UI 每秒刷新配置文件导致的会话反复重绑(不同 startTimestamp)
        var last = before
        var t = 2_000_000L
        repeat(20) { i ->
            t += 1_000L
            core.bindSession(t, -1L)
            // 重绑瞬间 counter 不得回落、也不得跳升(只能持平)
            assertEquals("第 $i 次重绑 counter 跳变", last, core.totalSteps)
            drive(core, 2_000L, 3.0)
            assertTrue("第 $i 次重绑后 counter 回落", core.totalSteps >= last)
            last = core.totalSteps
        }
    }

    @Test
    fun `采纳真实基线后 会话切换不得改写基线`() {
        val core = StepClockCore()
        core.bindSession(1_000L, -1L)
        assertTrue(core.adoptRealCounterBaseline(8_000L))
        drive(core, 30_000L, 3.0)
        val after = core.totalSteps
        assertTrue("真实基线应生效", after >= 8_000L)

        // 会话切换:基线已锁定,新会话不得回落到派生值(2000+)
        core.bindSession(9_999_999L, -1L)
        assertEquals("会话切换后 counter 必须持平", after, core.totalSteps)
        assertTrue(core.stepBaseline >= 8_000L)
    }

    @Test
    fun `真实基线低于已发出值时不采纳`() {
        val core = StepClockCore()
        core.bindSession(1_000L, -1L)
        drive(core, 120_000L, 3.0)   // 已发出若干步
        val before = core.totalSteps
        // 真实 counter 值低于已经发给宿主的步数 → 必须拒绝,否则造成回退跳变
        assertFalse(core.adoptRealCounterBaseline(1L))
        assertEquals(before, core.totalSteps)
    }

    @Test
    fun `反复重绑并采纳真实基线 全程单调`() {
        val core = StepClockCore()
        core.bindSession(1_000L, -1L)
        var last = 0L
        var t = 1_000L
        repeat(30) {
            t += 500L
            core.bindSession(t, -1L)                   // 会话切换
            core.adoptRealCounterBaseline(5_000L)      // 真实基线(可能被拒)
            drive(core, 1_000L, 3.0)
            val now = core.totalSteps
            assertTrue("counter 回退: $last -> $now", now >= last)
            last = now
        }
        assertEquals("无回退场景下 counter 应等于终值", last, core.totalSteps)
    }

    @Test
    fun `cadence 极端速度被钳位在生理区间`() {
        val core = StepClockCore()
        core.bindSession(1L, 0L)
        core.onTick(1000, 20.0, 0.85, false)     // 冲刺/乘车
        assertTrue("高速 cadence=${core.currentCadence}", core.currentCadence <= 205)

        core.onTick(2000, 0.5, 0.85, false)      // 极慢
        assertTrue("低速 cadence=${core.currentCadence}", core.currentCadence >= 90)
    }
}
