package com.suseoaa.locationspoofer.data.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路线采集过滤链单测:精度过滤/距离阈值/拐角保留/上限降频。
 */
class RoutePointFilterTest {

    // 采样起点(坐标任意,过滤逻辑只关心相对距离)
    private val lat0 = 40.45
    private val lng0 = 115.52

    /** 生成距 (lat0,lng0) 东向 dM 米的点(纬度不变,经度平移) */
    private fun east(dM: Double, lngBase: Double = lng0): Double =
        lngBase + dM / (111320.0 * kotlin.math.cos(Math.toRadians(lat0)))

    @Test
    fun `精度超限直接丢弃`() {
        val s = RoutePointFilter.Session()
        assertFalse(s.offer(lat0, lng0, 25.0, 1000L))
        assertTrue(s.offer(lat0, lng0, 10.0, 1000L))
    }

    @Test
    fun `距离阈值内不记 3米外记录`() {
        val s = RoutePointFilter.Session()
        assertTrue(s.offer(lat0, lng0, 5.0, 1000L))
        // 1.5m:小于 3m 且无拐角 → 不记
        assertFalse(s.offer(lat0, east(1.5), 5.0, 2000L))
        // 4m:记录
        assertTrue(s.offer(lat0, east(4.0), 5.0, 3000L))
        assertEquals(2, s.accepted.size)
    }

    @Test
    fun `拐角处放宽距离门槛`() {
        val s = RoutePointFilter.Session()
        // p0 → p1(东 10m)→ p2(北偏 1.5m 处,与前进方向夹角大)
        assertTrue(s.offer(lat0, lng0, 5.0, 1000L))
        assertTrue(s.offer(lat0, east(10.0), 5.0, 2000L))
        // 下一真点在北向 1.5m(相对 p1 前进方向是 ~85° 转角)
        val north1_5 = 40.45 + 1.5 / 111320.0
        assertTrue("拐角点应被保留", s.offer(north1_5, east(10.0), 5.0, 3000L))
        assertEquals(3, s.accepted.size)
    }

    @Test
    fun `点数上限后进入降频采样`() {
        val s = RoutePointFilter.Session()
        var t = 1000L
        var d = 0.0
        // 灌满 600 点(每点 5m)
        repeat(RoutePointFilter.MAX_POINTS) {
            d += 5.0
            assertTrue(s.offer(lat0, east(d), 5.0, t))
            t += 1000L
        }
        // 降频期:1s 内的新点被拒(循环结束时 t 已是最后一次 offer +1000)
        d += 5.0
        assertFalse("降频期 1s 间隔应被拒", s.offer(lat0, east(d), 5.0, t))
        // 2s 后的新点被收
        d += 5.0; t += 2000L
        assertTrue(s.offer(lat0, east(d), 5.0, t))
        assertEquals(RoutePointFilter.MAX_POINTS + 1, s.accepted.size)
        assertTrue(s.throttling)
    }

    @Test
    fun `累计距离与夹角计算合理`() {
        val s = RoutePointFilter.Session()
        assertTrue(s.offer(lat0, lng0, 5.0, 1000L))
        assertTrue(s.offer(lat0, east(10.0), 5.0, 2000L))
        assertTrue(s.offer(lat0, east(20.0), 5.0, 3000L))
        // 直线上累计距离 ≈ 20m
        val cd = s.cumulativeDistanceM()
        assertTrue("累计距离 $cd 应接近 20m", cd in 19.0..21.0)
        // 直线上中间点夹角 ≈ 180°(不判拐角)
        val angle = RoutePointFilter.angleAtDeg(lat0, lng0, lat0, east(10.0), lat0, east(20.0))
        assertTrue("直线夹角 $angle 应接近 180°", angle > 170.0)
    }
}
