package com.suseoaa.locationspoofer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.repository.RouteRecordController
import com.suseoaa.locationspoofer.data.repository.RouteRecordSnapshot
import com.suseoaa.locationspoofer.data.route.RoutePointFilter
import com.suseoaa.locationspoofer.utils.CoordinateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * 「记录路线」前台服务:实地走一圈,1s GPS 采样 → RoutePointFilter 过滤
 * (精度/距离/拐角/上限)→ GCJ-02 转换 → 内存点位 + 定期 cache 落盘。
 * 停止后点位保留在 [pendingPoints],由 ViewModel 经 controller 消费保存。
 *
 * 采集源为真实 GPS:LocationSpoofer 自身不在 hook scope 内,不受模拟影响。
 */
class RouteRecordService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var session = RoutePointFilter.Session()
    private var startTimeMs = 0L
    private var lastCacheAt = 0L

    companion object {
        const val ACTION_START = "com.suseoaa.locationspoofer.route.RECORD_START"
        const val ACTION_STOP = "com.suseoaa.locationspoofer.route.RECORD_STOP"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "RouteRecordServiceChannel"
        private const val CACHE_FILE = "route_record_cache.json"

        var isRunning = false
            private set

        /** 对外状态快照(UI collectAsState);停止后保留 finished=true 快照 */
        private val _recordState = MutableStateFlow<RouteRecordSnapshot?>(null)
        val recordState: StateFlow<RouteRecordSnapshot?> = _recordState

        /** 停止后待保存的点位(GCJ-02),由 controller.consumeRecordedPoints() 取走 */
        var pendingPoints: List<RoutePoint> = emptyList()
            private set

        internal fun publishSnapshot(startTimeMs: Long, session: RoutePointFilter.Session, finished: Boolean) {
            _recordState.value = RouteRecordSnapshot(
                startTimeMs = startTimeMs,
                elapsedMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L,
                pointCount = session.accepted.size,
                distanceM = session.cumulativeDistanceM(),
                finished = finished
            )
        }

        internal fun stashPending(points: List<RoutePoint>) {
            pendingPoints = points
        }

        internal fun clearPending() {
            pendingPoints = emptyList()
            _recordState.value = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRunning) return
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            stopSelf()
            return
        }

        session.reset()
        pendingPoints = emptyList()
        startTimeMs = System.currentTimeMillis()
        publishSnapshot(startTimeMs, session, finished = false)

        val providers = if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        locationManager.requestLocationUpdates(providers, 1000L, 0f, this, mainLooper)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationSpoofer:RouteRecord")
            .also { it.acquire(2 * 60 * 60 * 1000L) }

        isRunning = true
    }

    private fun stopRecording() {
        try { locationManager.removeUpdates(this) } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null

        // 交付点位:GCJ-02 RoutePoint 列表
        pendingPoints = session.accepted.map { RoutePoint(lat = it.lat, lng = it.lng, waitSec = 0.0) }
        publishSnapshot(startTimeMs, session, finished = true)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        // WGS-84 → GCJ-02(境内),境外原样
        val gcj = CoordinateUtils.wgs84ToGcj02(location.latitude, location.longitude)
        val accepted = session.offer(gcj.lat, gcj.lng, location.accuracy.toDouble(), System.currentTimeMillis())
        if (accepted) publishSnapshot(startTimeMs, session, finished = false)

        // 每 30s 落一份 cache,防进程被杀丢全程
        val now = System.currentTimeMillis()
        if (now - lastCacheAt > 30_000L) {
            lastCacheAt = now
            runCatching {
                val arr = JSONArray()
                for (p in session.accepted) {
                    arr.put(JSONArray().put(p.lat).put(p.lng).put(p.timeMs))
                }
                File(cacheDir, CACHE_FILE).writeText(arr.toString())
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderDisabled(provider: String) {}

    override fun onProviderEnabled(provider: String) {}

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getStringInternal("record_route_notification_title", "正在记录路线"))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    /** service 模块资源有限,标题走 strings.xml,兜底硬编码 */
    private fun getStringInternal(key: String, fallback: String): String = try {
        val id = resources.getIdentifier(key, "string", packageName)
        if (id != 0) getString(id) else fallback
    } catch (_: Throwable) {
        fallback
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Route Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

/**
 * [RouteRecordController] 的 service 侧实现,委托本服务 companion 状态。
 */
class RouteRecordControllerImpl : RouteRecordController {
    override val isRunning: Boolean
        get() = RouteRecordService.isRunning

    override val recordState: StateFlow<RouteRecordSnapshot?>
        get() = RouteRecordService.recordState

    override fun start(context: Context) {
        context.startForegroundService(
            Intent(context, RouteRecordService::class.java).apply { action = RouteRecordService.ACTION_START }
        )
    }

    override fun stop(context: Context) {
        context.startService(
            Intent(context, RouteRecordService::class.java).apply { action = RouteRecordService.ACTION_STOP }
        )
    }

    override fun consumeRecordedPoints(): List<RoutePoint> {
        val pts = RouteRecordService.pendingPoints
        RouteRecordService.clearPending()
        return pts
    }
}
