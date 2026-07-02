package com.shiraka.locatiobprovid.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SpooferProvider : ContentProvider() {

    companion object {
        var isActive = false
        var latitude = 0.0      // GCJ-02（高德坐标系，存入即为GCJ-02）
        var longitude = 0.0     // GCJ-02
        var currentLatitude = 0.0
        var currentLongitude = 0.0
        var wifiJson = "[]"
        var cellJson = "[]"
        var bluetoothJson = "[]"
        var simMode = "STILL"
        var simBearing = 0f
        var startTimestamp = 0L
        var routeJson = "[]"
        var isRouteMode = false
        var enableJitter = true
        var jitterRadiusMeters = 30
        var jitterSpeed = "MEDIUM"
        var appCoordinateSystemsJson = "{}"
        var mockWifi = true
        var mockCell = true
        var mockBluetooth = true
        var altitude = 0.0
        var satelliteCount = 20
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "active", "lat", "lng", "wifi_json", "cell_json", "bluetooth_json",
                "sim_mode", "sim_bearing", "start_timestamp",
                "route_json", "is_route_mode", "altitude", "satellite_count",
                "enable_jitter", "jitter_radius_meters", "jitter_speed"
            )
        )
        cursor.addRow(
            arrayOf(
                if (isActive) 1 else 0,
                if (isActive) currentLatitude else latitude,
                if (isActive) currentLongitude else longitude,
                wifiJson,
                cellJson,
                bluetoothJson,
                simMode,
                simBearing,
                startTimestamp,
                routeJson,
                if (isRouteMode) 1 else 0,
                altitude,
                satelliteCount,
                if (enableJitter) 1 else 0,
                jitterRadiusMeters,
                jitterSpeed
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
