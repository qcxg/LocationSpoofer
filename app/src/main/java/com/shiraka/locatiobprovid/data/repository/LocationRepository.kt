package com.shiraka.locatiobprovid.data.repository

import android.content.Context
import android.content.Intent
import com.shiraka.locatiobprovid.data.model.RoutePoint
import com.shiraka.locatiobprovid.provider.SpooferProvider
import com.shiraka.locatiobprovid.service.SpoofingService
import com.shiraka.locatiobprovid.utils.ConfigManager
import com.shiraka.locatiobprovid.utils.LSPosedManager
import com.shiraka.locatiobprovid.utils.RootManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: com.shiraka.locatiobprovid.utils.SettingsManager,
    private val savedRouteDao: com.shiraka.locatiobprovid.data.db.SavedRouteDao
) {
    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess()

    fun isModuleActive(): Boolean = lsposedManager.isModuleActive()

    suspend fun startSpoofing(
        context: Context,
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = "[]",
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = SpooferProvider.jitterRadiusMeters,
        jitterSpeed: String = SpooferProvider.jitterSpeed
    ) {
        SpooferProvider.isActive = true
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.currentLatitude = lat
        SpooferProvider.currentLongitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.wifiJson = wifiJson
        SpooferProvider.cellJson = cellJson
        SpooferProvider.bluetoothJson = bluetoothJson
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        SpooferProvider.enableJitter = enableJitter
        SpooferProvider.jitterRadiusMeters = jitterRadiusMeters.coerceIn(1, 80)
        SpooferProvider.jitterSpeed = jitterSpeed
        SpooferProvider.appCoordinateSystemsJson = JSONObject().apply {
            appCoordinateSystems.forEach { (pkg, sys) -> put(pkg, sys) }
        }.toString()
        SpooferProvider.mockWifi = mockWifi
        SpooferProvider.mockCell = mockCell
        SpooferProvider.mockBluetooth = mockBluetooth

        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        SpooferProvider.altitude = alt
        SpooferProvider.satelliteCount = satCount
        configManager.saveConfig(lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode, SpooferProvider.wifiJson, appCoordinateSystems, SpooferProvider.cellJson, SpooferProvider.bluetoothJson, mockWifi, mockCell, mockBluetooth, enableJitter, SpooferProvider.jitterRadiusMeters, SpooferProvider.jitterSpeed, alt, satCount)
        context.startForegroundService(
            Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_START
                putExtra(SpoofingService.EXTRA_LAT, lat)
                putExtra(SpoofingService.EXTRA_LNG, lng)
            }
        )
    }

    suspend fun stopSpoofing(context: Context) {
        SpooferProvider.isActive = false
        SpooferProvider.currentLatitude = 0.0
        SpooferProvider.currentLongitude = 0.0
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.cellJson = "[]"
        SpooferProvider.bluetoothJson = "[]"
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        SpooferProvider.appCoordinateSystemsJson = "{}"
        configManager.saveConfig(0.0, 0.0, false)
        context.startService(Intent(context, SpoofingService::class.java).apply {
            action = SpoofingService.ACTION_STOP
        })
    }

    suspend fun cleanupRuntimeEnvironment(context: Context): Boolean {
        SpooferProvider.isActive = false
        SpooferProvider.latitude = 0.0
        SpooferProvider.longitude = 0.0
        SpooferProvider.currentLatitude = 0.0
        SpooferProvider.currentLongitude = 0.0
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.cellJson = "[]"
        SpooferProvider.bluetoothJson = "[]"
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        SpooferProvider.appCoordinateSystemsJson = "{}"
        configManager.saveConfig(0.0, 0.0, false)
        context.startService(Intent(context, SpoofingService::class.java).apply {
            action = SpoofingService.ACTION_STOP
        })
        return rootManager.cleanupRuntimeEnvironment()
    }

    suspend fun updateConfig(
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = SpooferProvider.wifiJson,
        cellJson: String = SpooferProvider.cellJson,
        bluetoothJson: String = SpooferProvider.bluetoothJson,
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = SpooferProvider.jitterRadiusMeters,
        jitterSpeed: String = SpooferProvider.jitterSpeed
    ) {
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.currentLatitude = lat
        SpooferProvider.currentLongitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        SpooferProvider.wifiJson = wifiJson
        SpooferProvider.cellJson = cellJson
        SpooferProvider.bluetoothJson = bluetoothJson
        SpooferProvider.enableJitter = enableJitter
        SpooferProvider.jitterRadiusMeters = jitterRadiusMeters.coerceIn(1, 80)
        SpooferProvider.jitterSpeed = jitterSpeed
        SpooferProvider.appCoordinateSystemsJson = JSONObject().apply {
            appCoordinateSystems.forEach { (pkg, sys) -> put(pkg, sys) }
        }.toString()
        SpooferProvider.mockWifi = mockWifi
        SpooferProvider.mockCell = mockCell
        SpooferProvider.mockBluetooth = mockBluetooth
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        SpooferProvider.altitude = alt
        SpooferProvider.satelliteCount = satCount
        configManager.saveConfig(lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode, SpooferProvider.wifiJson, appCoordinateSystems, SpooferProvider.cellJson, SpooferProvider.bluetoothJson, mockWifi, mockCell, mockBluetooth, enableJitter, SpooferProvider.jitterRadiusMeters, SpooferProvider.jitterSpeed, alt, satCount)
    }

    suspend fun updateWifiJson(wifiJson: String, appCoordinateSystems: Map<String, String>) {
        SpooferProvider.wifiJson = wifiJson
        // 同步写入配置文件,确保Xposed端能读取到WiFi数据
        configManager.saveConfig(
            SpooferProvider.latitude,
            SpooferProvider.longitude,
            SpooferProvider.isActive,
            SpooferProvider.simMode,
            SpooferProvider.simBearing,
            startTimestamp = SpooferProvider.startTimestamp,
            wifiJson = wifiJson,
            appCoordinateSystems = appCoordinateSystems,
            cellJson = SpooferProvider.cellJson,
            bluetoothJson = SpooferProvider.bluetoothJson,
            jitterRadiusMeters = SpooferProvider.jitterRadiusMeters,
            jitterSpeed = SpooferProvider.jitterSpeed,
            altitude = settingsManager.altitude.toDoubleOrNull() ?: 0.0,
            satelliteCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        )
    }

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        return arr.toString()
    }

    fun getSavedRoutes(): kotlinx.coroutines.flow.Flow<List<com.shiraka.locatiobprovid.data.db.SavedRouteEntity>> {
        return savedRouteDao.getAllSavedRoutes()
    }

    suspend fun insertSavedRoute(name: String, points: List<RoutePoint>) {
        val pointsJson = routePointsToJson(points)
        savedRouteDao.insertSavedRoute(
            com.shiraka.locatiobprovid.data.db.SavedRouteEntity(
                name = name,
                pointsJson = pointsJson
            )
        )
    }

    suspend fun deleteSavedRoute(route: com.shiraka.locatiobprovid.data.db.SavedRouteEntity) {
        savedRouteDao.deleteSavedRoute(route)
    }
}
