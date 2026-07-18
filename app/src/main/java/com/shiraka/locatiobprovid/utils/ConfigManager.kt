package com.shiraka.locatiobprovid.utils

import com.shiraka.locatiobprovid.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.shiraka.locatiobprovid.LocationApp
import com.shiraka.locatiobprovid.provider.SpooferProvider
import java.util.concurrent.ConcurrentHashMap

class ConfigManager(private val rootManager: RootManager) {

    private fun logErrorEvery(key: String, message: String, error: Throwable? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = errorLogLastTimes.putIfAbsent(key, now)
        val shouldLog = previous == null ||
            (now - previous >= 60_000L && errorLogLastTimes.replace(key, previous, now))
        if (!shouldLog) return
        if (error == null) {
            android.util.Log.e("OpenCellID", message)
        } else {
            android.util.Log.e("OpenCellID", message, error)
        }
    }

    suspend fun saveConfig(
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false,
        wifiJson: String = EnvironmentRfResolver.EMPTY_WIFI_JSON,
        appCoordinateSystems: Map<String, String> = emptyMap(),
        cellJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = 30,
        jitterSpeed: String = "MEDIUM",
        signalJitterEnabled: Boolean = true,
        signalJitterLevel: Int = 40,
        wifiConnectionMode: String = "FIXED",
        altitude: Double = 0.0,
        satelliteCount: Int = 20
    ): Boolean = withContext(Dispatchers.IO) {
        val writeGeneration = RuntimeConfigWriteCoordinator.newGeneration()
        val bootId = readBootId()
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }

        val now = System.currentTimeMillis()
        val heartbeatUptimeMs = if (active) android.os.SystemClock.uptimeMillis() else 0L
        val runtimeFrameUptimeMs = android.os.SystemClock.uptimeMillis()
        val runtimeFrame = listOf(
            "1",
            if (active) "1" else "0",
            writeGeneration.toString(),
            runtimeFrameUptimeMs.toString(),
            ((if (active) lat else 0.0).coerceIn(-90.0, 90.0) * 10_000_000.0).toInt().toString(),
            ((if (active) lng else 0.0).coerceIn(-180.0, 180.0) * 10_000_000.0).toInt().toString(),
            ((((if (active) simBearing else 0f) % 360f + 360f) % 360f) * 100f).toInt().toString(),
            "0"
        ).joinToString("|")
        val json = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("active", active)
            put("config_updated_at", now)
            put("config_generation", writeGeneration)
            put("heartbeat_at", if (active) now else 0L)
            put("heartbeat_uptime_ms", heartbeatUptimeMs)
            put("boot_id", bootId)
            put("fail_closed", false)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("sim_speed", 0.0)
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
            put("cell_json", try { JSONArray(cellJson) } catch (_: Exception) { JSONArray() })
            put("bluetooth_json", JSONArray())
            put("mock_wifi", active && mockWifi)
            put("mock_cell", active && mockCell)
            put("mock_bluetooth", false)
            put("enable_jitter", enableJitter)
            put("jitter_radius_meters", jitterRadiusMeters.coerceIn(1, 80))
            put("jitter_speed", jitterSpeed)
            put("signal_jitter_enabled", signalJitterEnabled)
            put("signal_jitter_level", signalJitterLevel.coerceIn(0, 100))
            put("wifi_connection_mode", if (wifiConnectionMode == "RANDOM") "RANDOM" else "FIXED")
            put("altitude", altitude)
            put("satellite_count", satelliteCount)
            
            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)
        }
        // 使用 quoted heredoc 写入，避免 JSON 中的引号、美元符号等被 shell 解析。
        val cellArray = try { JSONArray(cellJson) } catch (_: Exception) { JSONArray() }
        val mcc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mcc", "460") else "460"
        val mnc = if (cellArray.length() > 0) cellArray.getJSONObject(0).optString("mnc", "00") else "00"

        val jsonText = json.toString()
        val generationCompareFunction = """
            locationspoofer_generation_gt() {
                left="${'$'}1"
                right="${'$'}2"
                [ "${'$'}{#left}" -gt "${'$'}{#right}" ] && return 0
                [ "${'$'}{#left}" -lt "${'$'}{#right}" ] && return 1
                [ "${'$'}left" = "${'$'}right" ] && return 1
                [ "${'$'}left" \> "${'$'}right" ]
            }
        """.trimIndent()
        val lifecycleTombstoneCommand = if (active) {
            ":"
        } else {
            // Publish the cheap pass-through gate before any filesystem work. Even
            // if a later chmod/write fails, hooked processes stop interfering at once.
            """
            setprop gsm.locsp.frame "$runtimeFrame"
            setprop gsm.locsp.active false
            """.trimIndent()
        }
        val stopFenceCommand = if (active) {
            ":"
        } else {
            // This short lock is deliberately independent from the runtime writer lock.
            // Publish a monotonic stop fence before waiting behind a possibly orphaned
            // writer, then wake hooked processes with the inactive runtime frame.
            """
            publish_stop_tombstone=0
            locationspoofer_publish_stop_fence() {
                flock -x 8 || return 75
                current_stop_generation="${'$'}(getprop gsm.locsp.stop_generation)"
                case "${'$'}current_stop_generation" in
                    ''|*[!0-9]*) current_stop_generation=0 ;;
                esac
                if locationspoofer_generation_gt "$writeGeneration" "${'$'}current_stop_generation"; then
                    setprop gsm.locsp.stop_generation $writeGeneration
                    publish_stop_tombstone=1
                fi
                flock -u 8
            }
            locationspoofer_publish_stop_fence 8>/data/local/tmp/locationspoofer_stop.lock || exit ${'$'}?
            if [ "${'$'}publish_stop_tombstone" -ne 1 ]; then
                exit 0
            fi
            setprop gsm.locsp.frame "$runtimeFrame"
            setprop gsm.locsp.active false
            """.trimIndent()
        }
        val lockedTombstoneGuardCommand = if (active) {
            ":"
        } else {
            // This command may have waited behind a newer active writer after it
            // published the stop fence. Re-check under the runtime lock so an old
            // tombstone cannot overwrite that newer snapshot on a delayed replay.
            """
            locked_snapshot_generation="${'$'}(getprop gsm.locsp.snapshot_generation)"
            locked_stop_generation="${'$'}(getprop gsm.locsp.stop_generation)"
            case "${'$'}locked_snapshot_generation" in
                ''|*[!0-9]*) locked_snapshot_generation=0 ;;
            esac
            case "${'$'}locked_stop_generation" in
                ''|*[!0-9]*) locked_stop_generation=0 ;;
            esac
            if locationspoofer_generation_gt "${'$'}locked_snapshot_generation" "$writeGeneration" ||
                locationspoofer_generation_gt "${'$'}locked_stop_generation" "$writeGeneration"; then
                flock -u 9
                return 0
            fi
            """.trimIndent()
        }
        val runtimePropertiesCommand = if (active) {
            """
            setprop gsm.locsp.active true
            setprop gsm.locsp.lat $lat
            setprop gsm.locsp.lng $lng
            setprop gsm.locsp.alt $altitude
            setprop gsm.locsp.bearing $simBearing
            setprop gsm.locsp.speed 0.0
            setprop gsm.locsp.sat_count $satelliteCount
            setprop gsm.locsp.mock_wifi $mockWifi
            setprop gsm.locsp.mock_cell $mockCell
            setprop gsm.locsp.mock_bluetooth false
            setprop gsm.locsp.enable_jitter $enableJitter
            setprop gsm.locsp.jitter_radius ${jitterRadiusMeters.coerceIn(1, 80)}
            setprop gsm.locsp.jitter_speed "$jitterSpeed"
            setprop gsm.locsp.signal_jitter ${signalJitterEnabled}
            setprop gsm.locsp.signal_jitter_level ${signalJitterLevel.coerceIn(0, 100)}
            setprop gsm.locsp.wifi_mode "${if (wifiConnectionMode == "RANDOM") "RANDOM" else "FIXED"}"
            setprop gsm.locsp.mcc $mcc
            setprop gsm.locsp.mnc $mnc
            setprop gsm.locsp.snapshot_generation $writeGeneration
            setprop gsm.locsp.boot_id "$bootId"
            setprop gsm.locsp.heartbeat_uptime $heartbeatUptimeMs
            setprop gsm.locsp.heartbeat $now
            setprop gsm.locsp.frame "$runtimeFrame"
            """.trimIndent()
        } else {
            // Static properties may remain stale because active=false is authoritative.
            // Avoid twenty unnecessary property mutations on every stop/cleanup.
            """
            setprop gsm.locsp.snapshot_generation $writeGeneration
            setprop gsm.locsp.heartbeat_uptime 0
            setprop gsm.locsp.heartbeat 0
            """.trimIndent()
        }
        val command = """
            |set -e
            |$generationCompareFunction
            |$stopFenceCommand
            |
            |locationspoofer_commit_runtime() {
            |flock -x 9 || return 73
            |
            |$lockedTombstoneGuardCommand
            |
            |$lifecycleTombstoneCommand
            |
            |cat > /data/local/tmp/locationspoofer_config.json.tmp.$writeGeneration <<'LOCATIONSPOOFER_JSON'
            |$jsonText
            |LOCATIONSPOOFER_JSON
            |mv /data/local/tmp/locationspoofer_config.json.tmp.$writeGeneration /data/local/tmp/locationspoofer_config.json
            |chmod 666 /data/local/tmp/locationspoofer_config.json
            |chcon u:object_r:shell_data_file:s0 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true
            |
            |cat > /data/system/locationspoofer_config.json.tmp.$writeGeneration <<'LOCATIONSPOOFER_JSON_SYSTEM'
            |$jsonText
            |LOCATIONSPOOFER_JSON_SYSTEM
            |mv /data/system/locationspoofer_config.json.tmp.$writeGeneration /data/system/locationspoofer_config.json
            |chown system:system /data/system/locationspoofer_config.json 2>/dev/null || true
            |chmod 644 /data/system/locationspoofer_config.json
            |chcon u:object_r:system_data_file:s0 /data/system/locationspoofer_config.json 2>/dev/null || true
            |
            |$runtimePropertiesCommand
            |
            |flock -u 9
            |}
            |locationspoofer_commit_runtime 9>/data/local/tmp/locationspoofer_runtime.lock
        """.trimMargin()

        RuntimeConfigWriteCoordinator.commitIfLatest(writeGeneration) {
            try {
                val context = LocationApp.instance
                val committed = context
                    .getSharedPreferences("locationspoofer_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("active", active)
                    .putFloat("lat", lat.toFloat())
                    .putFloat("lng", lng.toFloat())
                    .putFloat("alt", altitude.toFloat())
                    .putFloat("bearing", simBearing)
                    .putFloat("speed", 0f)
                    .putInt("sat_count", satelliteCount)
                    .putString("mcc", mcc)
                    .putString("mnc", mnc)
                    .putLong("heartbeat", now)
                    .putString("boot_id", bootId)
                    .putBoolean("mock_wifi", active && mockWifi)
                    .putBoolean("mock_cell", active && mockCell)
                    .putBoolean("mock_bluetooth", false)
                    .putBoolean("enable_jitter", enableJitter)
                    .putInt("jitter_radius_meters", jitterRadiusMeters.coerceIn(1, 80))
                    .putString("jitter_speed", jitterSpeed)
                    .putBoolean("signal_jitter_enabled", signalJitterEnabled)
                    .putInt("signal_jitter_level", signalJitterLevel.coerceIn(0, 100))
                    .putString("wifi_connection_mode", if (wifiConnectionMode == "RANDOM") "RANDOM" else "FIXED")
                    .commit()
                if (!committed) {
                    logErrorEvery("prefs-commit", "Failed to commit runtime preferences")
                }
            } catch (e: Throwable) {
                logErrorEvery(
                    "prefs-write",
                    "Failed to write SharedPreferences in saveConfig",
                    e
                )
            }

            val result = rootManager.executeCommand(command)
            if (result == "ERROR") {
                logErrorEvery(
                    "root-commit",
                    "saveConfig root commit failed generation=$writeGeneration"
                )
            } else {
                // Notify only after the generation-fenced root commit is authoritative.
                // Xposed-side observers merely wake their existing background reader.
                SpooferProvider.notifyRuntimeConfigChanged(LocationApp.instance)
            }
            result != "ERROR"
        }
    }

    private fun readBootId(): String {
        return try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Throwable) {
            ""
        }
    }

    private companion object {
        val errorLogLastTimes = ConcurrentHashMap<String, Long>()
    }
}
