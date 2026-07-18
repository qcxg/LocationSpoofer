package com.shiraka.locatiobprovid.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiraka.locatiobprovid.data.model.RoutePoint
import com.shiraka.locatiobprovid.provider.SpooferProvider
import com.shiraka.locatiobprovid.service.SpoofingService
import com.shiraka.locatiobprovid.utils.ConfigManager
import com.shiraka.locatiobprovid.utils.EnvironmentRfResolver
import com.shiraka.locatiobprovid.utils.LSPosedManager
import com.shiraka.locatiobprovid.utils.RootManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: com.shiraka.locatiobprovid.utils.SettingsManager,
    private val savedRouteDao: com.shiraka.locatiobprovid.data.db.SavedRouteDao
) {
    private val lifecycleGeneration = AtomicLong(0L)
    private val lifecycleMutex = Mutex()

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
        wifiJson: String = EnvironmentRfResolver.EMPTY_WIFI_JSON,
        cellJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        enableJitter: Boolean = true,
        jitterRadiusMeters: Int = SpooferProvider.jitterRadiusMeters,
        jitterSpeed: String = SpooferProvider.jitterSpeed,
        signalJitterEnabled: Boolean = SpooferProvider.signalJitterEnabled,
        signalJitterLevel: Int = SpooferProvider.signalJitterLevel,
        wifiConnectionMode: String = SpooferProvider.wifiConnectionMode
    ) {
        if (settingsManager.moduleRestartRequired) {
            throw IllegalStateException("模組已更新，請在方便解鎖時重啟設備後再啟用模擬")
        }
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        val generation = lifecycleMutex.withLock {
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.isActive = true
            SpooferProvider.publishAnchorPosition(lat, lng)
            SpooferProvider.publishCurrentPosition(lat, lng)
            SpooferProvider.publishRuntimeHeartbeat(startTime)
            SpooferProvider.startTimestamp = startTime
            SpooferProvider.simMode = simMode
            SpooferProvider.simBearing = simBearing
            SpooferProvider.routeJson = routePointsToJson(routePoints)
            SpooferProvider.isRouteMode = isRouteMode
            SpooferProvider.enableJitter = enableJitter
            SpooferProvider.jitterRadiusMeters = jitterRadiusMeters.coerceIn(1, 80)
            SpooferProvider.jitterSpeed = jitterSpeed
            SpooferProvider.signalJitterEnabled = signalJitterEnabled
            SpooferProvider.signalJitterLevel = signalJitterLevel.coerceIn(0, 100)
            SpooferProvider.wifiConnectionMode = if (wifiConnectionMode == "RANDOM") "RANDOM" else "FIXED"
            SpooferProvider.appCoordinateSystemsJson = JSONObject().apply {
                appCoordinateSystems.forEach { (pkg, sys) -> put(pkg, sys) }
            }.toString()
            SpooferProvider.publishRfSnapshot(
                lat,
                lng,
                wifiJson,
                cellJson,
                mockWifi,
                mockCell
            )
            SpooferProvider.altitude = alt
            SpooferProvider.satelliteCount = satCount
            nextGeneration
        }
        try {
            context.startForegroundService(
                Intent(context, SpoofingService::class.java).apply {
                    action = SpoofingService.ACTION_START
                    putExtra(SpoofingService.EXTRA_LAT, lat)
                    putExtra(SpoofingService.EXTRA_LNG, lng)
                    putExtra(SpoofingService.EXTRA_LIFECYCLE_TOKEN, generation)
                }
            )
        } catch (error: Throwable) {
            rollbackFailedStart(context, generation)
            throw error
        }

        // SpoofingService is the sole active writer. Waiting for its first committed
        // full snapshot avoids two active writers racing through the generation gate.
        val ready = withTimeoutOrNull(18_000L) {
            while (lifecycleGeneration.get() == generation && SpooferProvider.isActive) {
                if (SpoofingService.lastReadyLifecycleToken == generation) {
                    return@withTimeoutOrNull true
                }
                delay(50L)
            }
            false
        } ?: false
        if (!ready && lifecycleGeneration.get() == generation) {
            rollbackFailedStart(context, generation)
            throw IllegalStateException("Unable to commit active runtime configuration")
        }
        if (lifecycleGeneration.get() == generation) settingsManager.isSpoofingActive = true
    }

    suspend fun stopSpoofing(context: Context) {
        val generation = lifecycleMutex.withLock {
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.isActive = false
            SpooferProvider.publishCurrentPosition(0.0, 0.0)
            SpooferProvider.publishRuntimeHeartbeat(0L, "")
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.routeJson = "[]"
            SpooferProvider.isRouteMode = false
            SpooferProvider.appCoordinateSystemsJson = "{}"
            settingsManager.isSpoofingActive = false
            nextGeneration
        }
        sendStopCommand(context, generation)
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() == generation) {
                    if (!persistInactiveConfig()) {
                        Log.e("LocationRepository", "inactive runtime tombstone failed after retry")
                    }
                }
            }
        }
    }

    suspend fun cleanupRuntimeEnvironment(context: Context): Boolean {
        val generation = lifecycleMutex.withLock {
            val nextGeneration = lifecycleGeneration.incrementAndGet()
            SpooferProvider.runtimeLifecycleToken = nextGeneration
            SpooferProvider.publishRuntimeConfigGeneration(0L)
            SpooferProvider.isActive = false
            SpooferProvider.publishAnchorPosition(0.0, 0.0)
            SpooferProvider.publishCurrentPosition(0.0, 0.0)
            SpooferProvider.publishRuntimeHeartbeat(0L, "")
            SpooferProvider.clearRfForCoverageTransition()
            SpooferProvider.routeJson = "[]"
            SpooferProvider.isRouteMode = false
            SpooferProvider.appCoordinateSystemsJson = "{}"
            settingsManager.isSpoofingActive = false
            nextGeneration
        }
        sendStopCommand(context, generation)
        return withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() != generation) return@withLock false
                val tombstoneSaved = persistInactiveConfig()
                rootManager.cleanupRuntimeEnvironment() && tombstoneSaved
            }
        }
    }

    private fun sendStopCommand(context: Context, lifecycleToken: Long) {
        runCatching {
            context.startService(Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_STOP
                putExtra(SpoofingService.EXTRA_LIFECYCLE_TOKEN, lifecycleToken)
            })
        }.onFailure {
            context.stopService(Intent(context, SpoofingService::class.java))
        }
    }

    /**
     * Rolls back a start only while it still owns the published lifecycle token.
     * The STOP is sent before the root tombstone so an existing foreground-service
     * instance cannot be left behind if startForegroundService() itself failed.
     */
    private suspend fun rollbackFailedStart(context: Context, generation: Long) {
        withContext(NonCancellable) {
            val ownsGeneration = lifecycleMutex.withLock {
                if (lifecycleGeneration.get() != generation ||
                    SpooferProvider.runtimeLifecycleToken != generation
                ) {
                    false
                } else {
                    SpooferProvider.isActive = false
                    SpooferProvider.publishRuntimeConfigGeneration(0L)
                    settingsManager.isSpoofingActive = false
                    true
                }
            }
            if (!ownsGeneration) return@withContext

            sendStopCommand(context, generation)
            lifecycleMutex.withLock {
                if (lifecycleGeneration.get() == generation &&
                    SpooferProvider.runtimeLifecycleToken == generation &&
                    !SpooferProvider.isActive
                ) {
                    if (!persistInactiveConfig()) {
                        Log.e("LocationRepository", "start rollback tombstone failed after retry")
                    }
                }
            }
        }
    }

    private suspend fun persistInactiveConfig(): Boolean {
        repeat(2) { attempt ->
            if (configManager.saveConfig(0.0, 0.0, false)) return true
            if (attempt == 0) delay(250L)
        }
        return false
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
