package com.shiraka.locatiobprovid.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.shiraka.locatiobprovid.utils.EnvironmentCoveragePolicy
import com.shiraka.locatiobprovid.utils.EnvironmentRfResolver
import java.util.concurrent.atomic.AtomicLong

class SpooferProvider : ContentProvider() {

    companion object {
        val RUNTIME_CONFIG_URI: Uri =
            Uri.parse("content://com.suseoaa.locationspoofer.provider/runtime-config")

        fun notifyRuntimeConfigChanged(context: Context) {
            runCatching {
                context.contentResolver.notifyChange(RUNTIME_CONFIG_URI, null)
            }
        }

        data class RuntimeRfSnapshot(
            val coverageCell: EnvironmentCoveragePolicy.Cell?,
            val wifiJson: String,
            val cellJson: String,
            val bluetoothJson: String,
            val mockWifi: Boolean,
            val mockCell: Boolean,
            val mockBluetooth: Boolean
        )

        data class RuntimePosition(
            val latitude: Double,
            val longitude: Double,
            val bearing: Float = 0f,
            val speed: Float = 0f
        )
        data class ConnectedWifiOverride(
            val coverageCell: EnvironmentCoveragePolicy.Cell,
            val bssid: String
        )

        private val EMPTY_RF_SNAPSHOT = RuntimeRfSnapshot(
            coverageCell = null,
            wifiJson = EnvironmentRfResolver.EMPTY_WIFI_JSON,
            cellJson = "[]",
            bluetoothJson = "[]",
            mockWifi = false,
            mockCell = false,
            mockBluetooth = false
        )

        @Volatile
        private var runtimeRfSnapshot = EMPTY_RF_SNAPSHOT
        @Volatile
        private var runtimePosition = RuntimePosition(0.0, 0.0)
        @Volatile
        private var anchorPosition = RuntimePosition(0.0, 0.0)
        @Volatile
        private var connectedWifiOverride: ConnectedWifiOverride? = null
        @Volatile
        private var runtimeHeartbeatAt = 0L
        @Volatile
        private var runtimeHeartbeatUptimeMs = 0L
        @Volatile
        private var runtimeBootId = ""
        @Volatile
        private var runtimeConfigGeneration = 0L
        private val rfRefreshRevision = AtomicLong(0L)

        @Volatile var isActive = false
        @Volatile var runtimeLifecycleToken = 0L
        @Volatile var latitude = 0.0      // GCJ-02（高德坐标系，存入即为GCJ-02）
        @Volatile var longitude = 0.0     // GCJ-02
        @Volatile var currentLatitude = 0.0
        @Volatile var currentLongitude = 0.0
        @Volatile var wifiJson = EnvironmentRfResolver.EMPTY_WIFI_JSON
        @Volatile var cellJson = "[]"
        @Volatile var bluetoothJson = "[]"
        @Volatile var simMode = "STILL"
        @Volatile var simBearing = 0f
        @Volatile var simSpeed = 0f
        @Volatile var startTimestamp = 0L
        @Volatile var routeJson = "[]"
        @Volatile var isRouteMode = false
        @Volatile var enableJitter = true
        @Volatile var jitterRadiusMeters = 30
        @Volatile var jitterSpeed = "MEDIUM"
        @Volatile var signalJitterEnabled = true
        @Volatile var signalJitterLevel = 40
        @Volatile var wifiConnectionMode = "FIXED"
        @Volatile var appCoordinateSystemsJson = "{}"
        @Volatile var mockWifi = true
        @Volatile var mockCell = true
        @Volatile var mockBluetooth = false
        @Volatile var altitude = 0.0
        @Volatile var satelliteCount = 20

        fun publishRfSnapshot(
            latitude: Double,
            longitude: Double,
            wifiJson: String,
            cellJson: String,
            mockWifi: Boolean,
            mockCell: Boolean
        ) {
            // Legacy fields remain for existing callers; readers that need a
            // coherent coordinate/payload pairing use runtimeRfSnapshot.
            this.wifiJson = wifiJson
            this.cellJson = cellJson
            // Bluetooth is intentionally a compatibility tombstone only. Keeping
            // the fields prevents older readers/schema consumers from falling
            // back to unsafe defaults, but no runtime Bluetooth data is exposed.
            this.bluetoothJson = "[]"
            this.mockWifi = mockWifi
            this.mockCell = mockCell
            this.mockBluetooth = false
            runtimeRfSnapshot = RuntimeRfSnapshot(
                coverageCell = EnvironmentCoveragePolicy.cellFor(latitude, longitude),
                wifiJson = wifiJson,
                cellJson = cellJson,
                bluetoothJson = "[]",
                mockWifi = mockWifi,
                mockCell = mockCell,
                mockBluetooth = false
            )
        }

        fun clearRfForCoverageTransition() {
            runtimeRfSnapshot = EMPTY_RF_SNAPSHOT
            wifiJson = EnvironmentRfResolver.EMPTY_WIFI_JSON
            cellJson = "[]"
            bluetoothJson = "[]"
            mockWifi = false
            mockCell = false
            mockBluetooth = false
        }

        fun rfSnapshotFor(latitude: Double, longitude: Double): RuntimeRfSnapshot {
            val snapshot = runtimeRfSnapshot
            val outputCell = EnvironmentCoveragePolicy.cellFor(latitude, longitude)
            return if (snapshot.coverageCell != null && snapshot.coverageCell == outputCell) {
                snapshot
            } else {
                EMPTY_RF_SNAPSHOT
            }
        }

        fun hasResolvedRfCell(latitude: Double, longitude: Double): Boolean {
            val snapshot = runtimeRfSnapshot
            return snapshot.coverageCell != null &&
                snapshot.coverageCell == EnvironmentCoveragePolicy.cellFor(latitude, longitude)
        }

        fun requestRfRefresh(): Long = rfRefreshRevision.incrementAndGet()

        fun currentRfRefreshRevision(): Long = rfRefreshRevision.get()

        fun publishRuntimeHeartbeat(timestamp: Long, bootId: String = runtimeBootId) {
            runtimeHeartbeatAt = timestamp.coerceAtLeast(0L)
            runtimeHeartbeatUptimeMs = if (timestamp > 0L) {
                android.os.SystemClock.uptimeMillis()
            } else {
                0L
            }
            runtimeBootId = bootId
        }

        fun publishRuntimeConfigGeneration(generation: Long) {
            runtimeConfigGeneration = generation.coerceAtLeast(0L)
        }

        fun publishCurrentPosition(
            latitude: Double,
            longitude: Double,
            bearing: Float = simBearing,
            speed: Float = simSpeed
        ) {
            currentLatitude = latitude
            currentLongitude = longitude
            simBearing = bearing
            simSpeed = speed
            runtimePosition = RuntimePosition(latitude, longitude, bearing, speed)
        }

        fun currentPosition(): RuntimePosition = runtimePosition

        fun publishAnchorPosition(latitude: Double, longitude: Double) {
            this.latitude = latitude
            this.longitude = longitude
            anchorPosition = RuntimePosition(latitude, longitude)
        }

        fun anchorPosition(): RuntimePosition = anchorPosition

        fun setConnectedWifiOverride(latitude: Double, longitude: Double, bssid: String) {
            val cell = EnvironmentCoveragePolicy.cellFor(latitude, longitude) ?: return
            val normalized = bssid.trim()
            if (normalized.isBlank()) return
            connectedWifiOverride = ConnectedWifiOverride(cell, normalized)
            requestRfRefresh()
        }

        fun connectedWifiOverrideFor(latitude: Double, longitude: Double): String? {
            val override = connectedWifiOverride ?: return null
            return override.bssid.takeIf {
                override.coverageCell == EnvironmentCoveragePolicy.cellFor(latitude, longitude)
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val position = if (isActive) currentPosition() else anchorPosition()
        val outputLatitude = position.latitude
        val outputLongitude = position.longitude
        val rfSnapshot = rfSnapshotFor(outputLatitude, outputLongitude)
        val cursor = MatrixCursor(
            arrayOf(
                "active", "lat", "lng", "wifi_json", "cell_json", "bluetooth_json",
                "sim_mode", "sim_bearing", "sim_speed", "start_timestamp",
                "route_json", "is_route_mode", "altitude", "satellite_count",
                "enable_jitter", "jitter_radius_meters", "jitter_speed",
                "signal_jitter_enabled", "signal_jitter_level", "wifi_connection_mode",
                "mock_wifi", "mock_cell", "mock_bluetooth",
                "heartbeat_at", "heartbeat_uptime_ms", "config_updated_at",
                "config_generation", "runtime_lifecycle_token", "boot_id"
            )
        )
        cursor.addRow(
            arrayOf(
                if (isActive) 1 else 0,
                outputLatitude,
                outputLongitude,
                rfSnapshot.wifiJson,
                rfSnapshot.cellJson,
                rfSnapshot.bluetoothJson,
                simMode,
                position.bearing,
                position.speed,
                startTimestamp,
                routeJson,
                if (isRouteMode) 1 else 0,
                altitude,
                satelliteCount,
                if (enableJitter) 1 else 0,
                jitterRadiusMeters,
                jitterSpeed,
                if (signalJitterEnabled) 1 else 0,
                signalJitterLevel,
                wifiConnectionMode,
                if (rfSnapshot.mockWifi) 1 else 0,
                if (rfSnapshot.mockCell) 1 else 0,
                if (rfSnapshot.mockBluetooth) 1 else 0,
                runtimeHeartbeatAt,
                runtimeHeartbeatUptimeMs,
                runtimeHeartbeatAt,
                runtimeConfigGeneration,
                runtimeLifecycleToken,
                runtimeBootId
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
