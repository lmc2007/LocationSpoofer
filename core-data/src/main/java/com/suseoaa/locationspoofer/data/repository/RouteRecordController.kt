package com.suseoaa.locationspoofer.data.repository

import android.content.Context
import com.suseoaa.locationspoofer.data.model.RoutePoint
import kotlinx.coroutines.flow.StateFlow

/**
 * 「记录路线」前台服务的抽象。接口在 :core-data,实现在 :service,
 * 通过 Koin 注入(与 [SpoofingServiceController] 同模式,避免模块循环依赖)。
 *
 * 采集到的点位已转换为 GCJ-02 并经过 RoutePointFilter 过滤;
 * 状态经 [recordState] 暴露,停止后快照保留(finished=true)等待保存或丢弃。
 */
interface RouteRecordController {
    val isRunning: Boolean
    val recordState: StateFlow<RouteRecordSnapshot?>

    fun start(context: Context)
    fun stop(context: Context)

    /** 取走已记录点位并清空快照(保存成功后调用) */
    fun consumeRecordedPoints(): List<RoutePoint>
}

/** 记录路线的对外状态快照 */
data class RouteRecordSnapshot(
    val startTimeMs: Long,
    val elapsedMs: Long,
    val pointCount: Int,
    val distanceM: Double,
    /** 停止采集后为 true,等待保存或丢弃 */
    val finished: Boolean
)
