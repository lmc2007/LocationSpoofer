package com.suseoaa.locationspoofer.data.route

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 实地路线采集的点位过滤链(纯 Kotlin,无 Android 依赖,可单测)。
 *
 * 策略(见 reports/LocationSpoofer-路线记录功能开发计划.md D4):
 * 1. accuracy 超过 [MAX_ACCURACY_M] 的样本直接丢弃
 * 2. 距上一已记录点小于 [MIN_STEP_M] 不记(站立/等红灯不堆点)
 * 3. 拐角保留:与上两点夹角超过 [CORNER_ANGLE_DEG] 时放宽到 [MIN_STEP_M]/3 也记录(保拐角形态)
 * 4. 点数达到 [MAX_POINTS] 后进入降频采样(每 [THROTTLED_INTERVAL_MS] 一个),记录不中断
 *
 * 输入坐标约定:调用方负责先把 WGS-84 转成 GCJ-02,过滤器不做坐标系语义。
 */
object RoutePointFilter {
    const val MAX_ACCURACY_M = 20.0
    const val MIN_STEP_M = 3.0
    const val MIN_STEP_CORNER_M = 1.0
    const val CORNER_ANGLE_DEG = 25.0
    const val MAX_POINTS = 600
    const val THROTTLED_INTERVAL_MS = 2000L

    /** 两点球面距离(米),简化 haversine */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6378137.0
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val dLa = Math.toRadians(lat2 - lat1)
        val dLo = Math.toRadians(lng2 - lng1)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 顶点 b 处 a-b-c 的夹角(度),0=回头,180=直线 */
    fun angleAtDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double, cLat: Double, cLng: Double): Double {
        val mx = 111320.0
        val my = 111320.0 * cos(Math.toRadians(bLat))
        val ax = (aLng - bLng) * my; val ay = (aLat - bLat) * mx
        val cx = (cLng - bLng) * my; val cy = (cLat - bLat) * mx
        val dot = ax * cx + ay * cy
        val ma = sqrt(ax * ax + ay * ay); val mc = sqrt(cx * cx + cy * cy)
        if (ma == 0.0 || mc == 0.0) return 180.0
        val c = (dot / (ma * mc)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acosCompat(c))
    }

    private fun acosCompat(v: Double): Double = kotlin.math.acos(v)

    /**
     * 有状态采集会话。[offer] 逐样本喂入,返回 true 表示该点被采纳。
     * 非线程安全:调用方保证单线程(前台服务回调)。
     */
    class Session {
        val accepted = ArrayList<AcceptedPoint>(256)
        var throttling = false
            private set

        fun offer(lat: Double, lng: Double, accuracyM: Double, timeMs: Long): Boolean {
            if (accuracyM > MAX_ACCURACY_M) return false

            val last = accepted.lastOrNull()
            if (last == null) {
                accepted.add(AcceptedPoint(lat, lng, timeMs))
                return true
            }

            val d = distanceM(last.lat, last.lng, lat, lng)

            // 点数上限:降频采样(时间阈值),距离规则保留
            if (accepted.size >= MAX_POINTS) {
                throttling = true
                if (timeMs - last.timeMs < THROTTLED_INTERVAL_MS) return false
                accepted.add(AcceptedPoint(lat, lng, timeMs))
                return true
            }

            if (d < MIN_STEP_M) {
                // 拐角保留:夹角大时放宽距离门槛
                if (d < MIN_STEP_CORNER_M) return false
                if (accepted.size < 2) return false
                val p2 = accepted[accepted.size - 2]
                val angle = angleAtDeg(p2.lat, p2.lng, last.lat, last.lng, lat, lng)
                if (angle > CORNER_ANGLE_DEG) {
                    accepted.add(AcceptedPoint(lat, lng, timeMs))
                    return true
                }
                return false
            }

            accepted.add(AcceptedPoint(lat, lng, timeMs))
            return true
        }

        fun cumulativeDistanceM(): Double {
            var sum = 0.0
            for (i in 1 until accepted.size) {
                sum += distanceM(accepted[i - 1].lat, accepted[i - 1].lng, accepted[i].lat, accepted[i].lng)
            }
            return sum
        }

        fun reset() {
            accepted.clear()
            throttling = false
        }
    }

    data class AcceptedPoint(val lat: Double, val lng: Double, val timeMs: Long)
}
