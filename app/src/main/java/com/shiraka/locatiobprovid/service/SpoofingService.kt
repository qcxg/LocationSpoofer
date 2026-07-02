package com.shiraka.locatiobprovid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shiraka.locatiobprovid.data.model.RoutePoint
import com.shiraka.locatiobprovid.data.model.SimulatedLocation
import com.shiraka.locatiobprovid.provider.SpooferProvider
import com.shiraka.locatiobprovid.utils.RootManager
import com.shiraka.locatiobprovid.utils.TrajectorySimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SpoofingService : Service() {

    private var spoofingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var pushCount = 0L
    private val rootManager = RootManager()

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_LAT = "EXTRA_LAT"
        const val EXTRA_LNG = "EXTRA_LNG"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SpoofingServiceChannel"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startSpoofing(lat, lng)
            }
            ACTION_STOP -> stopSpoofing()
        }
        return START_NOT_STICKY
    }

    private fun startSpoofing(lat: Double, lng: Double) {
        ensureServiceScope()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationSpoofer:WakeLock").apply {
                acquire()
            }
            Log.d("SpoofingService", "WakeLock acquired")
        } catch (e: Throwable) {
            Log.e("SpoofingService", "Failed to acquire WakeLock", e)
        }
        spoofingJob?.cancel()
        spoofingJob = null
        pushCount = 0L

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.shiraka.locatiobprovid.R.string.spoofing_service_title))
            .setContentText(getString(com.shiraka.locatiobprovid.R.string.spoofing_service_content, lat.toString(), lng.toString()))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        spoofingJob = serviceScope.launch {
            while (isActive) {
                val currentLoc = computeCurrentLocation()
                writeRuntimeConfig(currentLoc)
                pushCount++
                if (pushCount == 1L || pushCount % 5L == 0L) {
                    Log.d(
                        "SpoofingService",
                        "配置刷新 heartbeat #$pushCount lat=${currentLoc.lat}, lng=${currentLoc.lng}"
                    )
                }
                delay(1000)
            }
        }
    }

    private fun ensureServiceScope() {
        if (serviceJob.isCancelled || serviceJob.isCompleted) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
        }
    }

    private fun computeCurrentLocation(): SimulatedLocation {
        val routePoints = parseRoutePoints(SpooferProvider.routeJson)
        return if (SpooferProvider.isRouteMode && routePoints.size >= 2) {
            TrajectorySimulator.calculateRoutePosition(
                routePoints,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                enableJitter = false,
                jitterRadiusMeters = SpooferProvider.jitterRadiusMeters.toDouble()
            )
        } else {
            TrajectorySimulator.calculateSimulatedLocation(
                SpooferProvider.latitude,
                SpooferProvider.longitude,
                SpooferProvider.startTimestamp,
                SpooferProvider.simMode,
                SpooferProvider.simBearing,
                enableJitter = false,
                jitterRadiusMeters = SpooferProvider.jitterRadiusMeters.toDouble()
            )
        }
    }

    private fun parseRoutePoints(json: String): List<RoutePoint> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun stopSpoofing() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Throwable) {
            Log.e("SpoofingService", "Failed to release WakeLock", e)
        }
        wakeLock = null
        spoofingJob?.cancel()
        spoofingJob = null
        isRunning = false
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun writeRuntimeConfig(loc: SimulatedLocation) {
        val now = System.currentTimeMillis()
        
        // Keep current output separate from the stable target anchor. Feeding jittered
        // output back into the anchor makes the random walk drift farther every cycle.
        com.shiraka.locatiobprovid.provider.SpooferProvider.currentLatitude = loc.lat
        com.shiraka.locatiobprovid.provider.SpooferProvider.currentLongitude = loc.lng
        
        val config = JSONObject().apply {
            put("lat", loc.lat)
            put("lng", loc.lng)
            put("active", true)
            put("config_updated_at", now)
            put("heartbeat_at", now)
            put("boot_id", readBootId())
            put("fail_closed", false)
            put("sim_mode", SpooferProvider.simMode)
            put("sim_bearing", SpooferProvider.simBearing.toDouble())
            put("start_timestamp", SpooferProvider.startTimestamp)
            put("route_points", safeJsonArray(SpooferProvider.routeJson))
            put("is_route_mode", SpooferProvider.isRouteMode)
            put("wifi_json", safeWifiJson(SpooferProvider.wifiJson))
            put("cell_json", safeJsonArray(SpooferProvider.cellJson))
            put("bluetooth_json", safeJsonArray(SpooferProvider.bluetoothJson))
            put("mock_wifi", SpooferProvider.mockWifi)
            put("mock_cell", SpooferProvider.mockCell)
            put("mock_bluetooth", SpooferProvider.mockBluetooth)
            put("enable_jitter", SpooferProvider.enableJitter)
            put("jitter_radius_meters", SpooferProvider.jitterRadiusMeters.coerceIn(1, 80))
            put("jitter_speed", SpooferProvider.jitterSpeed)
            put("altitude", SpooferProvider.altitude)
            put("satellite_count", SpooferProvider.satelliteCount)
            put("app_coordinate_systems", safeJsonObject(SpooferProvider.appCoordinateSystemsJson))
        }
        val cellArray = safeJsonArray(SpooferProvider.cellJson)
        val mcc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mcc", "460") else "460"
        val mnc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mnc", "00") else "00"

        try {
            val prefs = getSharedPreferences("locationspoofer_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("active", true)
                putFloat("lat", loc.lat.toFloat())
                putFloat("lng", loc.lng.toFloat())
                putFloat("alt", loc.altitude.toFloat())
                putFloat("bearing", loc.bearing)
                putInt("sat_count", SpooferProvider.satelliteCount)
                putString("mcc", mcc)
                putString("mnc", mnc)
                putLong("heartbeat", now)
                putString("boot_id", readBootId())
                putBoolean("enable_jitter", SpooferProvider.enableJitter)
                putInt("jitter_radius_meters", SpooferProvider.jitterRadiusMeters.coerceIn(1, 80))
                putString("jitter_speed", SpooferProvider.jitterSpeed)
                apply()
            }
        } catch (e: Throwable) {
            Log.e("SpoofingService", "Failed to write SharedPreferences in writeRuntimeConfig", e)
        }

        val jsonText = config.toString()
        val command = """
            cat > /data/local/tmp/locationspoofer_config.json.tmp <<'LOCATIONSPOOFER_JSON'
            $jsonText
            LOCATIONSPOOFER_JSON
            mv /data/local/tmp/locationspoofer_config.json.tmp /data/local/tmp/locationspoofer_config.json
            chmod 666 /data/local/tmp/locationspoofer_config.json
            chcon u:object_r:shell_data_file:s0 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true

            cat > /data/system/locationspoofer_config.json.tmp <<'LOCATIONSPOOFER_JSON_SYSTEM'
            $jsonText
            LOCATIONSPOOFER_JSON_SYSTEM
            mv /data/system/locationspoofer_config.json.tmp /data/system/locationspoofer_config.json
            chown system:system /data/system/locationspoofer_config.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config.json
            chcon u:object_r:system_data_file:s0 /data/system/locationspoofer_config.json 2>/dev/null || true

            setprop gsm.locsp.active true
            setprop gsm.locsp.lat ${loc.lat}
            setprop gsm.locsp.lng ${loc.lng}
            setprop gsm.locsp.alt ${loc.altitude}
            setprop gsm.locsp.bearing ${loc.bearing}
            setprop gsm.locsp.sat_count ${SpooferProvider.satelliteCount}
            setprop gsm.locsp.enable_jitter ${SpooferProvider.enableJitter}
            setprop gsm.locsp.jitter_radius ${SpooferProvider.jitterRadiusMeters.coerceIn(1, 80)}
            setprop gsm.locsp.jitter_speed "${SpooferProvider.jitterSpeed}"
            setprop gsm.locsp.mcc $mcc
            setprop gsm.locsp.mnc $mnc
            setprop gsm.locsp.heartbeat $now
            setprop gsm.locsp.boot_id "${readBootId()}"
        """.trimIndent()
        val result = rootManager.executeCommand(command)
        if (result == "ERROR") {
            Log.e("SpoofingService", "写入运行时配置失败")
        }
    }

    private fun readBootId(): String {
        return try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Throwable) {
            ""
        }
    }

    private fun safeJsonArray(text: String): JSONArray {
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun safeJsonObject(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun safeWifiJson(text: String): Any {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", JSONObject.NULL)
                put("nearbyWifi", JSONArray())
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(com.shiraka.locatiobprovid.R.string.spoofing_service_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Throwable) {
            Log.e("SpoofingService", "Failed to release WakeLock in onDestroy", e)
        }
        wakeLock = null
        spoofingJob?.cancel()
        spoofingJob = null
        isRunning = false
        serviceJob.cancel()
        super.onDestroy()
    }
}
