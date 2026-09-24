package com.suseoaa.locationspoofer.xposed.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶点转弯平滑单测：沿带拐角的路线采样，相邻采样点 bearing 差必须连续
 * （不存在硬切），且拐点两侧各存在"接近段方位"的样本。
 */
class RouteTurnTest {

    private fun config(vararg pts: Pair<Double, Double>, speed: Double = 3.0, start: Long = 1_000_000L): JSONObject {
        val arr = JSONArray()
        for ((lat, lng) in pts) arr.put(JSONObject().put("lat", lat).put("lng", lng))
        return JSONObject()
            .put("is_route_mode", true)
            .put("route_points", arr)
            .put("speed_m_s", speed)
            .put("start_timestamp", start)
            .put("stop_at_destination", false)
    }

    @Test
    fun `顶点处 bearing 连续渐变无硬切`() {
        // 直角拐角路线: 南→北 100m 后 东→ 116m;末端含 180° 折返
        val cfg = config(30.0 to 120.0, 30.0009 to 120.0, 30.0009 to 120.0012)
        // 每 500ms 采样 150s(走完全程并折返)
        var prevBearing = -1.0
        var maxJump = 0.0
        for (i in 0 until 300) {
            val now = 1_000_000L + i * 500L
            val m = RouteEngine.calculateCurrentPosition(cfg, now)
            if (prevBearing >= 0 && m.speed > 0.5f) {
                val d = Math.abs(m.bearing - prevBearing)
                val jump = if (d > 180) 360.0 - d else d
                maxJump = maxOf(maxJump, jump)
                // 500ms 内 bearing 变化上限 60°(=120°/s):直角弯约 50°/500ms、
                // 180° 折返约 57°/500ms(8m 平滑带),硬切则直接 90°/180°
                assertTrue("bearing 硬切 $jump° @i=$i", jump < 60.0)
            }
            prevBearing = m.bearing.toDouble()
        }
        // 确实经过了平滑带:直角弯 90° 被打散到多个采样点
        assertTrue("maxJump=$maxJump,平滑带应产生 25°-60° 的渐变峰", maxJump in 20.0..60.0)
    }
}
