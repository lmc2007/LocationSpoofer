package com.suseoaa.locationspoofer.xposed.utils

import com.suseoaa.locationspoofer.xposed.hooks.gait.PaceProfile
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class SpoofedMotion(
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val speed: Float
)

/**
 * 高性能路线轨迹连续插值引擎 (High-Performance Smooth Route Interpolation Engine)
 *
 * 解决路线模拟跳跃/不连续问题:
 * - 纯内存微秒级数学插值，基于当前系统时钟毫秒戳连续推导沿线坐标与实时航向角。
 * - 彻底告别对磁盘文件定时刷新的依赖，在任意高频调用下实现绝对平滑连续运动。
 * - 自动计算瞬时物理速度 (m/s) 与航向角度 (Bearing)，全面适配运动/跑步/骑行软件判定。
 */
object RouteEngine {

    data class Point(val lat: Double, val lng: Double)

    private var cachedRouteSignature: String = ""
    private var cachedPoints: List<Point> = emptyList()
    private var cachedCumulativeDistances: DoubleArray = DoubleArray(0)
    private var cachedTotalDistance: Double = 0.0

    // ---- 配速曲线距离积分状态（PaceProfile，P1-1） ----
    private var speedIntgKey = ""
    private var speedIntgLastNow = 0L
    private var speedIntgDist = 0.0

    /** 最近一次 calculateCurrentPosition 输出的瞬时速度（m/s）。0 = 非运动状态。
     *  SensorStepHooker / StepEventScheduler 读取它保持步频-加速度与 GPS 速度同源。 */
    @Volatile
    var lastInstantSpeed: Float = 0f
        private set

    /** 仅供单测：当前积分里程（米） */
    internal fun currentIntegratedDistance(): Double = speedIntgDist

    /**
     * 对 PaceProfile.instantSpeed 数值积分求里程。
     * - 参数变化（新会话/改速度）→ 重置重算
     * - 1 秒步进梯形近似，调用频率无关
     * - 挂起超过 300 秒：该段不积分（距离冻结，进程挂起期 App 已死，保守处理）
     * - 时钟回拨：不积分，距离冻结
     */
    @Synchronized
    private fun integratedDistance(
        now: Long,
        startMs: Long,
        baseSpeedMs: Double,
        minPace: Double,
        maxPace: Double,
        slowPatches: Boolean
    ): Double {
        val key = "$startMs|$baseSpeedMs|$minPace|$maxPace|$slowPatches"
        if (key != speedIntgKey) {
            // 状态重置（新会话/单次冷调用）：用解析近似补齐 startMs→now 的历史里程，
            // 保证 calculateCurrentPosition 对任意 now 的单次调用契约成立
            speedIntgKey = key
            val elapsed = (now - startMs) / 1000.0
            speedIntgDist = PaceProfile.approximateDistance(elapsed, baseSpeedMs)
            speedIntgLastNow = now
            return speedIntgDist
        }
        if (now <= speedIntgLastNow) return speedIntgDist          // 时钟回拨/同刻：冻结
        if (now - speedIntgLastNow > 300_000L) {                   // 长挂起：跳过该段
            speedIntgLastNow = now
            return speedIntgDist
        }
        var t = speedIntgLastNow
        while (t < now) {
            val step = minOf(1000L, now - t)
            speedIntgDist += PaceProfile.instantSpeed(t, startMs, baseSpeedMs, minPace, maxPace, slowPatches) * step / 1000.0
            t += step
        }
        speedIntgLastNow = now
        return speedIntgDist
    }

    private fun haversine(p1: Point, p2: Point): Double {
        val r = 6378137.0
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(from: Point, to: Point): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return bearing.toFloat()
    }

    @Synchronized
    private fun updateRouteCache(routeArray: JSONArray) {
        val count = routeArray.length()
        val signature = "$count#${routeArray.optJSONObject(0)?.optDouble("lat", 0.0)}#${routeArray.optJSONObject(count - 1)?.optDouble("lat", 0.0)}"
        if (signature == cachedRouteSignature && cachedPoints.size == count && cachedPoints.isNotEmpty()) {
            return
        }
        val list = ArrayList<Point>(count)
        for (i in 0 until count) {
            val obj = routeArray.optJSONObject(i) ?: continue
            list.add(Point(obj.optDouble("lat", 0.0), obj.optDouble("lng", 0.0)))
        }
        cachedPoints = list
        if (list.size >= 2) {
            val cumDist = DoubleArray(list.size)
            var total = 0.0
            cumDist[0] = 0.0
            for (i in 0 until list.size - 1) {
                val d = haversine(list[i], list[i + 1])
                total += d
                cumDist[i + 1] = total
            }
            cachedCumulativeDistances = cumDist
            cachedTotalDistance = total
        } else {
            cachedCumulativeDistances = DoubleArray(0)
            cachedTotalDistance = 0.0
        }
        cachedRouteSignature = signature
    }

    fun calculateCurrentPosition(config: JSONObject, now: Long = System.currentTimeMillis()): SpoofedMotion {
        val isRouteMode = config.optBoolean("is_route_mode", false)
        val routeArray = config.optJSONArray("route_points")
        val baseLat = config.optDouble("lat", 0.0)
        val baseLng = config.optDouble("lng", 0.0)
        val baseBearing = config.optDouble("sim_bearing", 0.0).toFloat()

        if (!isRouteMode || routeArray == null || routeArray.length() < 2) {
            lastInstantSpeed = 0f
            return SpoofedMotion(baseLat, baseLng, baseBearing, 0f)
        }

        updateRouteCache(routeArray)
        val points = cachedPoints
        val totalDist = cachedTotalDistance
        if (points.size < 2 || totalDist <= 0.0) {
            lastInstantSpeed = 0f
            return SpoofedMotion(baseLat, baseLng, baseBearing, 0f)
        }

        val stopAtDestination = config.optBoolean("stop_at_destination", false)
        val isClosedLoop = if (points.size >= 2) {
            haversine(points.first(), points.last()) <= 5.0
        } else false

        val baseSpeed = config.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        val minPace = config.optDouble("min_pace_sec_per_km", 0.0).let { if (it.isNaN()) 0.0 else it }
        val maxPace = config.optDouble("max_pace_sec_per_km", 0.0).let { if (it.isNaN()) 0.0 else it }
        val slowPatches = config.optBoolean("slow_patches", true)
        val rawStartTime = config.optLong("start_timestamp", 0L)
        val startTime = if (rawStartTime > 0L) rawStartTime else now
        val distTraveled = integratedDistance(now, startTime, baseSpeed, minPace, maxPace, slowPatches)
        var vNow = PaceProfile.instantSpeed(now, startTime, baseSpeed, minPace, maxPace, slowPatches)

        if (stopAtDestination && distTraveled >= totalDist) {
            lastInstantSpeed = 0f
            val lastPt = points.last()
            val prevPt = points[points.size - 2]
            val lastBearing = calculateBearing(prevPt, lastPt)
            return SpoofedMotion(lastPt.lat, lastPt.lng, lastBearing, 0f)
        }

        val forward: Boolean
        val targetDist: Double

        if (isClosedLoop) {
            val cycleDist = totalDist
            val distInCycle = if (cycleDist > 0.0) distTraveled % cycleDist else 0.0
            forward = true
            targetDist = distInCycle
        } else {
            val cycleDist = totalDist * 2.0
            val distInCycle = if (cycleDist > 0.0) distTraveled % cycleDist else 0.0
            forward = distInCycle <= totalDist
            targetDist = if (forward) distInCycle else cycleDist - distInCycle
        }

        val cum = cachedCumulativeDistances
        var segIndex = 0
        while (segIndex < points.size - 2 && cum[segIndex + 1] < targetDist) {
            segIndex++
        }

        val fromPt = points[segIndex]
        val toPt = points[segIndex + 1]
        val segStartDist = cum[segIndex]
        val segEndDist = cum[segIndex + 1]
        val segLen = (segEndDist - segStartDist).coerceAtLeast(0.0001)
        val ratio = ((targetDist - segStartDist) / segLen).coerceIn(0.0, 1.0)

        // 顶点转弯平滑（P1）：折线拐角的"位置硬折 + 航向硬切"都是合成轨迹特征，
        // 运动世界按点位有效性渲染轨迹颜色（绿=有效/红=异常），高德轨迹去噪会把
        // 位移方向突变点判为漂移标红。真实转弯是圆弧：前方顶点 TURN_BLEND_M 内
        // 位置沿二次贝塞尔切角、航向渐变、并做转弯减速（人过弯必减速）。
        // 前方顶点：去程看 to 端，返程看 from 端（返程沿段反向行走）。
        val distIntoSeg = targetDist - cum[segIndex]
        val curBearing = if (forward) calculateBearing(fromPt, toPt) else calculateBearing(toPt, fromPt)

        var curLat = fromPt.lat + (toPt.lat - fromPt.lat) * ratio
        var curLng = fromPt.lng + (toPt.lng - fromPt.lng) * ratio
        var bearingDeg: Double = curBearing.toDouble()
        var turnSlowDown = 1.0

        run {
            val distToVertex = if (forward) segLen - distIntoSeg else distIntoSeg
            if (distToVertex >= TURN_BLEND_M) return@run

            // 顶点与两侧切向单位向量(米空间)
            val vertex: Point
            val curDirM: Pair<Double, Double>
            val nextDirM: Pair<Double, Double>
            var sideSign = 0.0     // 掉头时的侧向偏移(米空间垂直方向 × 符号)
            when {
                forward -> {
                    vertex = toPt
                    curDirM = unitDirM(fromPt, toPt)
                    when {
                        isClosedLoop -> nextDirM = unitDirM(toPt, points[(segIndex + 1) % points.size])
                        segIndex + 2 <= points.size - 1 -> nextDirM = unitDirM(toPt, points[segIndex + 2])
                        else -> {
                            // 终点掉头:下一段 = 反向
                            nextDirM = unitDirM(toPt, fromPt)
                            sideSign = if (segIndex % 2 == 0) 1.0 else -1.0
                        }
                    }
                }
                else -> {
                    vertex = fromPt
                    curDirM = unitDirM(toPt, fromPt)
                    when {
                        segIndex > 0 -> nextDirM = unitDirM(points[segIndex], points[segIndex - 1])
                        else -> {
                            // 起点掉头:再正向
                            nextDirM = unitDirM(points[segIndex], points[segIndex + 1])
                            sideSign = if (segIndex % 2 == 0) -1.0 else 1.0
                        }
                    }
                }
            }

            // 圆角半径:不越过任一相邻段的一半
            val r = minOf(TURN_BLEND_M, segLen * 0.45).coerceAtLeast(0.5)
            val t = (1.0 - distToVertex / r).coerceIn(0.0, 1.0)
            if (t <= 0.0) return@run

            // 二次贝塞尔(米空间)。掉头(180°)时控制点必须带侧向偏移:
            // 三点共线的"直线往返"会让相邻采样点 bearing 直接反转 180°,
            // 侧偏 4m 让轨迹绕一个真实的小 U 弧(人折返就是绕小弯转身)。
            val sideN = curDirM.second * sideSign * 4.0
            val sideE = -curDirM.first * sideSign * 4.0
            val u = 1.0 - t
            val aOff = metersToDeg(-curDirM.first * r, -curDirM.second * r, vertex.lat)
            val p1Off = metersToDeg(sideN, sideE, vertex.lat)
            val p2Off = metersToDeg(nextDirM.first * r + sideN * 0.5, nextDirM.second * r + sideE * 0.5, vertex.lat)

            curLat = u * u * (vertex.lat + aOff.first) + 2 * u * t * (vertex.lat + p1Off.first) + t * t * (vertex.lat + p2Off.first)
            curLng = u * u * (vertex.lng + aOff.second) + 2 * u * t * (vertex.lng + p1Off.second) + t * t * (vertex.lng + p2Off.second)

            // 航向 = 贝塞尔切线方向(北/东分量),与位置严格一致
            val north = 2 * (u * (curDirM.first * r + sideN) + t * (nextDirM.first * r + sideN * 0.5)) / r
            val east = 2 * (u * (curDirM.second * r + sideE) + t * (nextDirM.second * r + sideE * 0.5)) / r
            if (north != 0.0 || east != 0.0) {
                bearingDeg = ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0)
            }

            // 过弯减速:弯心最低降到 70%(掉头 U 弧更慢),与位移/航向变化率同向收敛
            // 过弯减速 8%(原 30% 会把顶点配速推到染色红区 —— 轨迹标红的元凶)
            turnSlowDown = 1.0 - 0.08 * sin(Math.PI * t)
        }

        vNow = (vNow * turnSlowDown).coerceAtLeast(0.0)
        lastInstantSpeed = vNow.toFloat()

        return SpoofedMotion(curLat, curLng, bearingDeg.toFloat(), vNow.toFloat())
    }

    /** 米空间单位方向:(北向分量, 东向分量),模长 1。转弯圆角几何必须在米空间做。 */
    private fun unitDirM(a: Point, b: Point): Pair<Double, Double> {
        val my = 111320.0
        val mx = 111320.0 * cos(Math.toRadians(a.lat))
        val dLat = (b.lat - a.lat) * my
        val dLng = (b.lng - a.lng) * mx
        val len = sqrt(dLat * dLat + dLng * dLng)
        if (len == 0.0) return 0.0 to 0.0
        return dLat / len to dLng / len
    }

    /** 米偏移 → 度偏移 */
    private fun metersToDeg(mNorth: Double, mEast: Double, atLat: Double): Pair<Double, Double> {
        return (mNorth / 111320.0) to (mEast / (111320.0 * cos(Math.toRadians(atLat))))
    }

    private fun smoothstep(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3.0 - 2.0 * c)
    }

    /** 角度插值（处理 0/360 环绕）：t=0 → a，t=1 → b，走最短弧 */
    private fun lerpAngleDeg(a: Double, b: Double, t: Double): Double {
        val d = ((b - a + 540.0) % 360.0) - 180.0
        return (a + d * t + 360.0) % 360.0
    }

    /** 顶点转弯平滑带宽度（米）：进入该范围开始渐变航向 */
    const val TURN_BLEND_M = 8.0
}
