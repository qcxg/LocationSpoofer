package com.shiraka.locatiobprovid.utils

import com.shiraka.locatiobprovid.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.shiraka.locatiobprovid.LocationApp

class ConfigManager(private val rootManager: RootManager) {

    suspend fun saveConfig(
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false,
        wifiJson: String = "[]",
        appCoordinateSystems: Map<String, String> = emptyMap(),
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = 30,
        jitterSpeed: String = "MEDIUM",
        altitude: Double = 0.0,
        satelliteCount: Int = 20
    ) = withContext(Dispatchers.IO) {
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }

        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("active", active)
            put("config_updated_at", now)
            put("heartbeat_at", if (active) now else 0L)
            put("boot_id", readBootId())
            put("fail_closed", false)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            val wifiObj = try {
                JSONObject(wifiJson)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("isConnected", false)
                    put("connectedWifi", JSONObject.NULL)
                    put("nearbyWifi", JSONArray())
                }
            }
            put("wifi_json", wifiObj)
            put("cell_json", JSONArray(cellJson))
            put("bluetooth_json", JSONArray(bluetoothJson))
            put("mock_wifi", mockWifi)
            put("mock_cell", mockCell)
            put("mock_bluetooth", mockBluetooth)
            put("enable_jitter", enableJitter)
            put("jitter_radius_meters", jitterRadiusMeters.coerceIn(1, 80))
            put("jitter_speed", jitterSpeed)
            put("altitude", altitude)
            put("satellite_count", satelliteCount)
            
            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)
        }
        val cellCount = json.optJSONArray("cell_json")?.length() ?: 0
        android.util.Log.d(
            "OpenCellID",
            "saveConfig: active=$active mockCell=$mockCell lat=$lat lng=$lng cellJsonCount=$cellCount"
        )

        // 使用 quoted heredoc 写入，避免 JSON 中的引号、美元符号等被 shell 解析。
        val cellArray = try { JSONArray(cellJson) } catch (_: Exception) { JSONArray() }
        val mcc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mcc", "460") else "460"
        val mnc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mnc", "00") else "00"

        try {
            val context = LocationApp.instance
            val prefs = context.getSharedPreferences("locationspoofer_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("active", active)
                putFloat("lat", lat.toFloat())
                putFloat("lng", lng.toFloat())
                putFloat("alt", altitude.toFloat())
                putFloat("bearing", simBearing)
                putInt("sat_count", satelliteCount)
                putString("mcc", mcc)
                putString("mnc", mnc)
                putLong("heartbeat", now)
                putString("boot_id", readBootId())
                putBoolean("enable_jitter", enableJitter)
                putInt("jitter_radius_meters", jitterRadiusMeters.coerceIn(1, 80))
                putString("jitter_speed", jitterSpeed)
                apply()
            }
        } catch (e: Throwable) {
            android.util.Log.e("OpenCellID", "Failed to write SharedPreferences in saveConfig", e)
        }

        val jsonText = json.toString()
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

            setprop gsm.locsp.active $active
            setprop gsm.locsp.lat $lat
            setprop gsm.locsp.lng $lng
            setprop gsm.locsp.alt $altitude
            setprop gsm.locsp.bearing $simBearing
            setprop gsm.locsp.sat_count $satelliteCount
            setprop gsm.locsp.enable_jitter $enableJitter
            setprop gsm.locsp.jitter_radius ${jitterRadiusMeters.coerceIn(1, 80)}
            setprop gsm.locsp.jitter_speed "$jitterSpeed"
            setprop gsm.locsp.mcc $mcc
            setprop gsm.locsp.mnc $mnc
            setprop gsm.locsp.heartbeat $now
            setprop gsm.locsp.boot_id "${readBootId()}"
        """.trimIndent()

        val result = rootManager.executeCommand(command)
        android.util.Log.d(
            "OpenCellID",
            "saveConfig: wrote config copies to /data/local/tmp and /data/system, result=${result.take(200)}"
        )
    }

    private fun readBootId(): String {
        return try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Throwable) {
            ""
        }
    }
}
