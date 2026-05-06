package com.gpsemulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.*

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_emulator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.gpsemulator.STOP"

        const val EXTRA_MODE = "mode"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_WAYPOINTS = "waypoints"
        const val EXTRA_SPEED_KMH = "speed_kmh"

        const val MODE_STATIC = "static"
        const val MODE_ROUTE = "route"

        // All providers to mock
        private val MOCK_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused"
        )
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private var serviceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var currentLat = 25.0330
    var currentLon = 121.5654
    var isRunning = false
    var statusCallback: ((String) -> Unit)? = null
    private var pendingError: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("GPS 模擬器運行中"))

        // Setup mock providers AFTER startForeground
        val setupOk = setupMockProviders()
        if (!setupOk) return START_NOT_STICKY

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_STATIC
        when (mode) {
            MODE_STATIC -> {
                val lat = intent?.getDoubleExtra(EXTRA_LAT, currentLat) ?: currentLat
                val lon = intent?.getDoubleExtra(EXTRA_LON, currentLon) ?: currentLon
                startStaticMode(lat, lon)
            }
            MODE_ROUTE -> {
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val waypoints: ArrayList<RoutePoint>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getSerializableExtra(EXTRA_WAYPOINTS, ArrayList::class.java) as? ArrayList<RoutePoint>
                    } else {
                        intent?.getSerializableExtra(EXTRA_WAYPOINTS) as? ArrayList<RoutePoint>
                    }
                val speedKmh = intent?.getFloatExtra(EXTRA_SPEED_KMH, 30f) ?: 30f
                if (waypoints != null && waypoints.size >= 2) {
                    startRouteMode(waypoints, speedKmh)
                }
            }
        }

        return START_STICKY
    }

    private fun setupMockProviders(): Boolean {
        var anySuccess = false
        val errors = mutableListOf<String>()

        for (providerName in MOCK_PROVIDERS) {
            try {
                try { locationManager.removeTestProvider(providerName) } catch (_: Exception) {}

                locationManager.addTestProvider(
                    providerName,
                    false, false, false, false, false,
                    true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(providerName, true)
                anySuccess = true
            } catch (e: Exception) {
                errors.add("$providerName: ${e.message}")
            }
        }

        if (!anySuccess) {
            val msg = "無法設定模擬位置，請確認：\n1. 已在「開發人員選項」→「選取模擬位置應用程式」選此 APP\n2. 開發人員選項已啟用\n錯誤：${errors.firstOrNull()}"
            postStatus(msg)
            stopSelf()
            return false
        }

        return true
    }

    fun registerStatusCallback(cb: (String) -> Unit) {
        statusCallback = cb
        pendingError?.let { cb(it); pendingError = null }
    }

    private fun postStatus(msg: String) {
        if (statusCallback != null) statusCallback?.invoke(msg)
        else pendingError = msg
    }

    fun pushLocation(lat: Double, lon: Double, bearing: Float = 0f, speed: Float = 0f) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = 10.0
            accuracy = 3.0f
            this.bearing = bearing
            this.speed = speed
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        for (providerName in MOCK_PROVIDERS) {
            try {
                val loc = Location(providerName).apply {
                    latitude = lat
                    longitude = lon
                    altitude = 10.0
                    accuracy = 3.0f
                    this.bearing = bearing
                    this.speed = speed
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                locationManager.setTestProviderLocation(providerName, loc)
            } catch (_: Exception) {}
        }

        currentLat = lat
        currentLon = lon
    }

    fun startStaticMode(lat: Double, lon: Double) {
        serviceJob?.cancel()
        isRunning = true
        serviceJob = scope.launch {
            while (isActive) {
                pushLocation(lat, lon)
                postStatus("📍 靜態模式：%.6f, %.6f".format(lat, lon))
                delay(1000)
            }
        }
    }

    fun startRouteMode(waypoints: List<RoutePoint>, speedKmh: Float) {
        serviceJob?.cancel()
        isRunning = true
        val speedMs = speedKmh / 3.6f

        serviceJob = scope.launch {
            var waypointIndex = 0
            postStatus("🗺 路徑模式開始，共 ${waypoints.size} 個路徑點，時速 ${speedKmh.toInt()} km/h")

            while (isActive) {
                val from = waypoints[waypointIndex]
                val to = waypoints[(waypointIndex + 1) % waypoints.size]

                val distanceM = haversineDistance(from.latitude, from.longitude, to.latitude, to.longitude)
                val travelMs = (distanceM / speedMs * 1000).toLong().coerceAtLeast(500L)
                val bearing = calculateBearing(from.latitude, from.longitude, to.latitude, to.longitude)
                val updateIntervalMs = 500L
                val steps = (travelMs / updateIntervalMs).coerceAtLeast(1)

                for (step in 0..steps) {
                    if (!isActive) return@launch
                    val fraction = step.toDouble() / steps.toDouble()
                    val lat = from.latitude + (to.latitude - from.latitude) * fraction
                    val lon = from.longitude + (to.longitude - from.longitude) * fraction
                    pushLocation(lat, lon, bearing, speedMs)
                    val nextName = if (to.name.isNotBlank()) to.name else "路徑點 ${waypointIndex + 2}"
                    val distLeft = (distanceM * (1 - fraction)).toInt()
                    postStatus("🚗 前往 $nextName | ${speedKmh.toInt()} km/h | 剩 ${distLeft}m")
                    delay(updateIntervalMs)
                }

                if (to.dwellSeconds > 0) {
                    val pointName = if (to.name.isNotBlank()) to.name else "路徑點 ${waypointIndex + 2}"
                    repeat(to.dwellSeconds) { s ->
                        if (!isActive) return@launch
                        pushLocation(to.latitude, to.longitude)
                        postStatus("⏸ 停留於 $pointName (${to.dwellSeconds - s}s)")
                        delay(1000)
                    }
                }

                waypointIndex = (waypointIndex + 1) % waypoints.size
                if (waypointIndex == 0) {
                    postStatus("🔄 路徑完成，重新開始...")
                    delay(1000)
                }
            }
        }
    }

    fun stopSimulation() {
        serviceJob?.cancel()
        serviceJob = null
        isRunning = false
        postStatus("⏹ 已停止模擬")
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GPS 模擬器", NotificationManager.IMPORTANCE_LOW).apply {
            description = "GPS 位置模擬服務"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MockLocationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS 模擬器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        for (p in MOCK_PROVIDERS) {
            try {
                locationManager.setTestProviderEnabled(p, false)
                locationManager.removeTestProvider(p)
            } catch (_: Exception) {}
        }
        isRunning = false
        super.onDestroy()
    }
}
