package com.shiraka.locatiobprovid.viewmodel

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.shiraka.locatiobprovid.data.model.AppState
import com.shiraka.locatiobprovid.data.model.RoutePoint
import com.shiraka.locatiobprovid.data.model.RoutePlanStage
import com.shiraka.locatiobprovid.data.model.RouteRunMode
import com.shiraka.locatiobprovid.data.model.SavedLocation
import com.shiraka.locatiobprovid.data.model.SimMode
import com.shiraka.locatiobprovid.data.model.AppMapType
import com.shiraka.locatiobprovid.data.model.JitterSpeed
import com.shiraka.locatiobprovid.data.model.StartSpoofingPhase
import com.shiraka.locatiobprovid.data.model.StartSpoofingProgress
import com.shiraka.locatiobprovid.data.repository.LocationRepository
import com.shiraka.locatiobprovid.data.repository.SettingsRepository
import com.shiraka.locatiobprovid.provider.SpooferProvider
import com.shiraka.locatiobprovid.service.SpoofingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainViewModel(
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val lsposedManager: com.shiraka.locatiobprovid.utils.LSPosedManager,
    private val environmentScanner: com.shiraka.locatiobprovid.utils.EnvironmentScanner,
    private val environmentDao: com.shiraka.locatiobprovid.data.db.EnvironmentDao,
    private val wifiRepository: com.shiraka.locatiobprovid.data.repository.WifiRepository,
    private val opencellidClient: com.shiraka.locatiobprovid.utils.OpenCellIdClient,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppState(
            mapType = try {
                AppMapType.valueOf(settingsRepository.getMapType())
            } catch (e: Exception) {
                AppMapType.NORMAL
            },
            mapEngine = try {
                com.shiraka.locatiobprovid.data.model.MapEngine.valueOf(settingsRepository.getMapEngine())
            } catch (e: Exception) {
                com.shiraka.locatiobprovid.data.model.MapEngine.AUTO
            },
            savedLocations = settingsRepository.getSavedLocations(),
            recentLocations = settingsRepository.getRecentLocations(),
            savedRoutes = emptyList(), // Will be populated by Room Flow
            currentLanguage = normalizeLanguageTag(settingsRepository.getLanguage()),
            isLanguageSet = settingsRepository.isLanguageSet(),
            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
            mockWifi = settingsRepository.mockWifi,
            mockCell = settingsRepository.mockCell,
            mockBluetooth = settingsRepository.mockBluetooth,
            enableJitter = settingsRepository.enableJitter,
            jitterRadiusMeters = settingsRepository.jitterRadiusMeters,
            jitterSpeed = parseJitterSpeed(settingsRepository.jitterSpeed),
            altitudeInput = settingsRepository.altitude,
            satelliteCountInput = settingsRepository.satelliteCount,
            wigleToken = settingsRepository.getWigleApiToken(),
            opencellidToken = settingsRepository.getOpencellidApiToken()
        )
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private var locationSyncJob: Job? = null
    private var autoRouteJob: Job? = null
    private var continuousScanJob: Job? = null
    private val geocodeClient = OkHttpClient()
    private val nearbyNameMemory = mutableMapOf<String, String>()

    init {
        initialize()
    }

    // 初始化

    private fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = locationRepository.checkRootAccess()

            if (settingsRepository.isSpoofingActive) {
                val lastLat = settingsRepository.lastSpoofedLat.toDoubleOrNull() ?: 0.0
                val lastLng = settingsRepository.lastSpoofedLng.toDoubleOrNull() ?: 0.0
                if (lastLat != 0.0 && lastLng != 0.0) {
                    _uiState.update {
                        it.copy(
                            latitudeInput = lastLat.toString(),
                            longitudeInput = lastLng.toString(),
                            mapConfirmedPoint = Pair(lastLat, lastLng)
                        )
                    }
                    if (SpoofingService.isRunning) {
                        evaluateMockCapabilitiesSuspend(lastLat, lastLng)
                    }
                }
                if (!SpoofingService.isRunning) {
                    settingsRepository.isSpoofingActive = false
                    locationRepository.stopSpoofing(context)
                }
            } else if (SpoofingService.isRunning) {
                locationRepository.stopSpoofing(context)
            }

            val lastLat = settingsRepository.lastSpoofedLat
            val lastLng = settingsRepository.lastSpoofedLng
            val hasLastLocation = !lastLat.isNullOrEmpty() && lastLat != "0" && lastLat != "0.0" &&
                                  !lastLng.isNullOrEmpty() && lastLng != "0" && lastLng != "0.0"
            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = root,
                    isSpoofingActive = settingsRepository.isSpoofingActive,
                    latitudeInput = if (hasLastLocation) lastLat else it.latitudeInput,
                    longitudeInput = if (hasLastLocation) lastLng else it.longitudeInput,
                    mapConfirmedPoint = if (hasLastLocation) {
                        val latD = lastLat.toDoubleOrNull()
                        val lngD = lastLng.toDoubleOrNull()
                        if (latD != null && lngD != null) Pair(latD, lngD) else null
                    } else null,
                    routePlanStage = RoutePlanStage.IDLE,
                    googleApiKey = settingsRepository.getGoogleApiKey(),
                    appSha1 = getAppSignatureSHA1()
                )
            }
            if (!settingsRepository.isSpoofingActive && !hasLastLocation) {
                fetchCurrentLocation(context)
            }
            refreshRecordCount()
            loadManageData()
        }

        viewModelScope.launch {
            com.shiraka.locatiobprovid.LocationApp.isModuleActive.collect { active ->
                _uiState.update { 
                    it.copy(
                        isLSPosedActive = active,
                        hookedApps = if (active) lsposedManager.getHookedApps(context) else emptyList()
                    )
                }
            }
        }

        viewModelScope.launch {
            locationRepository.getSavedRoutes().collect { entities ->
                val routes = entities.map { entity ->
                    val points = mutableListOf<RoutePoint>()
                    try {
                        val arr = org.json.JSONArray(entity.pointsJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            points.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lng")))
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    com.shiraka.locatiobprovid.data.model.SavedRoute(entity.name, points).apply {
                        // attach ID dynamically if needed or just use name for deletion
                        // Currently MapScreen's showSavedRoutesDialog uses route.name
                    }
                }
                _uiState.update { it.copy(savedRoutes = routes) }
            }
        }
    }

    fun updateLanguage(langCode: String) {
        val normalized = normalizeLanguageTag(langCode)
        settingsRepository.setLanguage(normalized)
        _uiState.update { it.copy(currentLanguage = normalized) }
    }

    fun setMapType(type: AppMapType) {
        settingsRepository.setMapType(type.name)
        _uiState.update { it.copy(mapType = type) }
    }

    fun setMapEngine(engine: com.shiraka.locatiobprovid.data.model.MapEngine) {
        settingsRepository.setMapEngine(engine.name)
        _uiState.update { it.copy(mapEngine = engine) }
    }

    fun setSearchMode(mode: com.shiraka.locatiobprovid.data.model.SearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
    }

    data class ClusterData(
        val center: com.shiraka.locatiobprovid.data.db.LocationRecord,
        var count: Int,
        var hasWifi: Boolean,
        var hasBluetooth: Boolean,
        var hasCell: Boolean
    )

    suspend fun performLocalSearch(): List<com.shiraka.locatiobprovid.ui.screen.AppPoiItem> {
        val allRecords = environmentDao.getAllCompleteLocations()
        if (allRecords.isEmpty()) {
            return emptyList()
        }

        // Simple clustering logic: group by ~150m distance
        val clusters = mutableListOf<ClusterData>()

        for (record in allRecords) {
            val loc = record.location
            val hasW = record.wifis.isNotEmpty()
            val hasB = record.bluetooths.isNotEmpty()
            val hasC = record.cells.isNotEmpty()

            var foundCluster = false
            for (i in clusters.indices) {
                val cluster = clusters[i]
                val dLat = Math.toRadians(cluster.center.lat - loc.lat)
                val dLng = Math.toRadians(cluster.center.lng - loc.lng)
                val a = kotlin.math.sin(dLat / 2).let { it * it } + 
                        kotlin.math.cos(Math.toRadians(loc.lat)) * 
                        kotlin.math.cos(Math.toRadians(cluster.center.lat)) * 
                        kotlin.math.sin(dLng / 2).let { it * it }
                val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

                if (distance <= 150.0) { // 150 meters radius
                    cluster.count += 1
                    cluster.hasWifi = cluster.hasWifi || hasW
                    cluster.hasBluetooth = cluster.hasBluetooth || hasB
                    cluster.hasCell = cluster.hasCell || hasC
                    foundCluster = true
                    break
                }
            }
            if (!foundCluster) {
                clusters.add(ClusterData(loc, 1, hasW, hasB, hasC))
            }
        }

        clusters.sortByDescending { it.count }

        return clusters.map { cluster ->
            val tags = mutableListOf<String>()
            if (cluster.hasWifi) tags.add("Wi-Fi")
            if (cluster.hasBluetooth) tags.add("蓝牙")
            if (cluster.hasCell) tags.add("基站")
            
            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
            val nearbyName = resolveNearbyPlaceName(cluster.center.lat, cluster.center.lng)
                .ifBlank { "本地采集热点" }
            val title = if (nearbyName == "本地采集热点") nearbyName else "${nearbyName}附近"

            com.shiraka.locatiobprovid.ui.screen.AppPoiItem(
                title = "$title$tagStr",
                snippet = "包含 ${cluster.count} 条记录 (${String.format("%.4f", cluster.center.lat)}, ${String.format("%.4f", cluster.center.lng)})",
                lat = cluster.center.lat,
                lng = cluster.center.lng
            )
        }
    }

    fun selectLanguage(languageCode: String) {
        val normalized = normalizeLanguageTag(languageCode)
        settingsRepository.setLanguage(normalized)
        settingsRepository.setLanguageSet(true)
        _uiState.update { it.copy(isLanguageSet = true, currentLanguage = normalized) }
    }

    fun getSavedLanguage(): String = normalizeLanguageTag(settingsRepository.getLanguage())

    private fun normalizeLanguageTag(language: String): String {
        return when (language) {
            "en", "zh", "zh-TW" -> language
            else -> ""
        }
    }

    private fun parseJitterSpeed(value: String): JitterSpeed {
        return runCatching { JitterSpeed.valueOf(value) }.getOrDefault(JitterSpeed.MEDIUM)
    }

    fun setAltitude(altitude: String) {
        settingsRepository.altitude = altitude
        _uiState.update { it.copy(altitudeInput = altitude) }
    }

    fun setSatelliteCount(count: String) {
        settingsRepository.satelliteCount = count
        _uiState.update { it.copy(satelliteCountInput = count) }
    }

    // 当前位置获取

    fun isDomesticEnvironment(): Boolean {
        val lang = getSavedLanguage()
        return lang == "zh" || (lang.isEmpty() && java.util.Locale.getDefault().language == "zh")
    }

    fun fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            fallbackToNativeLocation(ctx, forceCallback, false)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun fallbackToNativeLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)?, convertToGcj: Boolean) {
        try {
            if (forceCallback != null && convertToGcj) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.shiraka.locatiobprovid.R.string.amap_restricted_fallback), android.widget.Toast.LENGTH_SHORT).show()
            }
            val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                android.location.LocationManager.NETWORK_PROVIDER
            } else {
                android.location.LocationManager.GPS_PROVIDER
            }

            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                applyNativeLocation(lastLoc, forceCallback)
            } else if (forceCallback != null) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.shiraka.locatiobprovid.R.string.waiting_gps_signal), android.widget.Toast.LENGTH_LONG).show()
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    if (forceCallback != null) {
                        android.widget.Toast.makeText(ctx, ctx.getString(com.shiraka.locatiobprovid.R.string.native_location_success), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    applyNativeLocation(location, forceCallback)
                    locationManager.removeUpdates(this)
                }
                @Deprecated("Deprecated in Android framework.")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        } catch (e: SecurityException) {
            if (forceCallback != null) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.shiraka.locatiobprovid.R.string.location_permission_denied), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyNativeLocation(location: android.location.Location, forceCallback: ((Double, Double) -> Unit)?) {
        val finalLat = location.latitude
        val finalLng = location.longitude

        if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty() || forceCallback != null) {
            _uiState.update {
                it.copy(
                    latitudeInput = String.format("%.6f", finalLat),
                    longitudeInput = String.format("%.6f", finalLng),
                    showCoordinateError = false
                )
            }
            forceCallback?.invoke(finalLat, finalLng)
        }
    }

    private suspend fun fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? = suspendCoroutine { cont ->
        fallbackToNativeLocationSilent(ctx, cont)
    }

    @android.annotation.SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun fallbackToNativeLocationSilent(ctx: Context, cont: kotlin.coroutines.Continuation<Pair<Double, Double>?>) {
        try {
            val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                android.location.LocationManager.NETWORK_PROVIDER
            } else if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                android.location.LocationManager.GPS_PROVIDER
            } else {
                cont.resume(null)
                return
            }

            // try last known first
            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                val res = getNativeConverted(lastLoc)
                cont.resume(res)
                return
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    val res = getNativeConverted(location)
                    cont.resume(res)
                    locationManager.removeUpdates(this)
                }
                @Deprecated("Deprecated in Android framework.")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            // Use main looper for listener
            locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            
            // Timeout after 5 seconds to avoid suspending forever
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                delay(5000)
                locationManager.removeUpdates(listener)
                if (cont.context.isActive) {
                    try { cont.resume(null) } catch(e: Exception) {}
                }
            }
        } catch (e: Exception) {
            try { cont.resume(null) } catch(e: Exception) {}
        }
    }
    
    private fun getNativeConverted(location: android.location.Location): Pair<Double, Double> {
        val finalLat = location.latitude
        val finalLng = location.longitude
        return Pair(finalLat, finalLng)
    }

    // 坐标输入

    fun updateLongitude(value: String) {
        if (isValidCoord(value)) {
            _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
            val d = value.toDoubleOrNull()
            if (d != null) {
                settingsRepository.lastSpoofedLng = value
                val lat = _uiState.value.latitudeInput.toDoubleOrNull()
                if (lat != null) {
                    _uiState.update { it.copy(mapConfirmedPoint = Pair(lat, d)) }
                }
            }
            evaluateMockCapabilities()
        }
    }

    fun updateLatitude(value: String) {
        if (isValidCoord(value)) {
            _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
            val d = value.toDoubleOrNull()
            if (d != null) {
                settingsRepository.lastSpoofedLat = value
                val lng = _uiState.value.longitudeInput.toDoubleOrNull()
                if (lng != null) {
                    _uiState.update { it.copy(mapConfirmedPoint = Pair(d, lng)) }
                }
            }
            evaluateMockCapabilities()
        }
    }

    private fun isValidCoord(value: String): Boolean {
        if (value.isEmpty() || value == "-") return true
        return value.toDoubleOrNull() != null
    }
    
    private fun evaluateMockCapabilities() {
        val state = _uiState.value
        val lat = state.latitudeInput.toDoubleOrNull()
        val lng = state.longitudeInput.toDoubleOrNull()
        
        if (lat == null || lng == null) {
            _uiState.update { 
                it.copy(canMockWifi = false, canMockCell = false, canMockBluetooth = false, 
                        collectedWifiJson = "[]", collectedCellJson = "[]", collectedBluetoothJson = "[]",
                        wifiApCount = 0, wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE) 
            }
            return
        }
        
        viewModelScope.launch {
            evaluateMockCapabilitiesSuspend(lat, lng)
        }
    }

    private suspend fun evaluateMockCapabilitiesSuspend(lat: Double, lng: Double) {
        val allRecords = withContext(Dispatchers.IO) { environmentDao.getAllCompleteLocations() }
        val validRecords = mutableListOf<com.shiraka.locatiobprovid.data.db.CompleteLocation>()

        for (record in allRecords) {
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                    kotlin.math.cos(Math.toRadians(loc.lat)) *
                    kotlin.math.cos(Math.toRadians(lat)) *
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

            if (distance <= 50.0) { // Increased radius to 50m to match visually forgiving areas
                validRecords.add(record)
            }
        }

        withContext(Dispatchers.Main) {
            if (validRecords.isEmpty()) {
                _uiState.update {
                    it.copy(
                        canMockWifi = false, canMockCell = false, canMockBluetooth = false,
                        collectedWifiJson = "[]", collectedCellJson = "[]", collectedBluetoothJson = "[]",
                        wifiApCount = 0,
                        wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE
                    )
                }
            } else {
                val (wifiJson, cellJson, btJson) = locationToJson(validRecords, lat, lng)
                val hasW = try {
                    val obj = org.json.JSONObject(wifiJson)
                    val nearby = obj.optJSONArray("nearbyWifi")
                    val connected = obj.opt("connectedWifi")
                    (nearby != null && nearby.length() > 0) || (connected != null && !obj.isNull("connectedWifi"))
                } catch (e: Exception) {
                    false
                }
                val hasC = try {
                    val arr = org.json.JSONArray(cellJson)
                    arr.length() > 0
                } catch (e: Exception) {
                    false
                }
                val hasB = try {
                    val arr = org.json.JSONArray(btJson)
                    arr.length() > 0
                } catch (e: Exception) {
                    false
                }

                val wifiCount = try {
                    val obj = org.json.JSONObject(wifiJson)
                    val nearby = obj.optJSONArray("nearbyWifi")
                    nearby?.length() ?: 0
                } catch (e: Exception) { 0 }

                _uiState.update {
                    it.copy(
                        canMockWifi = hasW, canMockCell = hasC, canMockBluetooth = hasB,
                        collectedWifiJson = wifiJson, collectedCellJson = cellJson, collectedBluetoothJson = btJson,
                        wifiApCount = wifiCount,
                        wifiLoadStatus = if (hasW) com.shiraka.locatiobprovid.data.model.WifiLoadStatus.DONE else com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE
                    )
                }
            }
        }
    }

    private suspend fun hasLocalWifiWithin50m(lat: Double, lng: Double): Boolean {
        val bounds = nearbyBounds(lat, lng, 50.0)
        val nearbyRecords = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationsInBounds(bounds.minLat, bounds.maxLat, bounds.minLng, bounds.maxLng)
        }
        for (record in nearbyRecords) {
            if (record.wifis.isEmpty()) continue
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                    kotlin.math.cos(Math.toRadians(loc.lat)) *
                    kotlin.math.cos(Math.toRadians(lat)) *
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            if (distance <= 50.0) {
                return true
            }
        }
        return false
    }

    private suspend fun hasLocalCellsWithin50m(lat: Double, lng: Double): Boolean {
        val bounds = nearbyBounds(lat, lng, 50.0)
        val nearbyRecords = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationsInBounds(bounds.minLat, bounds.maxLat, bounds.minLng, bounds.maxLng)
        }
        for (record in nearbyRecords) {
            if (record.cells.isEmpty()) continue
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } + 
                    kotlin.math.cos(Math.toRadians(loc.lat)) * 
                    kotlin.math.cos(Math.toRadians(lat)) * 
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            if (distance <= 50.0) {
                return true
            }
        }
        return false
    }

    private data class GeoBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )

    private fun nearbyBounds(lat: Double, lng: Double, radiusMeters: Double): GeoBounds {
        val latDelta = radiusMeters / 111_320.0
        val lngDelta = radiusMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return GeoBounds(
            minLat = (lat - latDelta).coerceAtLeast(-90.0),
            maxLat = (lat + latDelta).coerceAtMost(90.0),
            minLng = (lng - lngDelta).coerceAtLeast(-180.0),
            maxLng = (lng + lngDelta).coerceAtMost(180.0)
        )
    }

    private suspend fun fetchWifiFromWigleSync(lat: Double, lng: Double): Boolean {
        val settingsToken = settingsRepository.getWigleApiToken()
        if (settingsToken.isBlank()) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE,
                        wifiApCount = 0,
                        canMockWifi = false
                    )
                }
            }
            return false
        }

        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.LOADING) }
        }
        // Align coordinate conversion to WGS-84 standard for WiGLE API
        val wgs84 = com.shiraka.locatiobprovid.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
        val wgsLat = wgs84.lat
        val wgsLng = wgs84.lng

        try {
            val rawJsonArrayString = wifiRepository.fetchWifiData(wgsLat, wgsLng, settingsToken)
            val nearbyArr = org.json.JSONArray(rawJsonArrayString)
            if (nearbyArr.length() > 0) {
                val wifiObj = org.json.JSONObject()
                wifiObj.put("isConnected", true)
                
                val firstAp = nearbyArr.getJSONObject(0)
                val firstBssid = firstAp.optString("bssid")
                val firstSsid = firstAp.optString("ssid")
                
                val connObj = org.json.JSONObject().apply {
                    put("bssid", firstBssid)
                    put("ssid", firstSsid)
                    put("vendor", com.shiraka.locatiobprovid.utils.MacVendorHelper.getVendor(firstBssid))
                    put("level", -45)
                    put("frequency", 2412)
                    put("channel", 1)
                    put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                    put("macAddress", "02:00:00:00:00:00")
                    put("linkSpeed", 150)
                    put("networkId", 1)
                    put("wifiStandard", 4)
                }
                wifiObj.put("connectedWifi", connObj)
                
                val formattedNearby = org.json.JSONArray()
                for (i in 0 until nearbyArr.length()) {
                    val ap = nearbyArr.getJSONObject(i)
                    val bssid = ap.optString("bssid")
                    val ssid = ap.optString("ssid")
                    val level = -50 - (i * 2)
                    val freq = if (i % 2 == 0) 2412 else 5180
                    
                    val itemObj = org.json.JSONObject().apply {
                        put("bssid", bssid)
                        put("ssid", ssid)
                        put("vendor", com.shiraka.locatiobprovid.utils.MacVendorHelper.getVendor(bssid))
                        put("level", level)
                        put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                        put("frequency", freq)
                        put("channel", com.shiraka.locatiobprovid.utils.MacVendorHelper.frequencyToChannel(freq))
                    }
                    formattedNearby.put(itemObj)
                }
                wifiObj.put("nearbyWifi", formattedNearby)
                
                val formattedWifiJson = wifiObj.toString()
                
                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, formattedWifiJson, "[]", "[]")
                    val newestLocation = environmentDao.getAllLocations().firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        environmentDao.updateMetadata(
                            newestLocation.id,
                            resolveNearbyPlaceName(lat, lng),
                            "由 WiGLE 導入 · 經緯度: (${String.format("%.6f", lat)}, ${String.format("%.6f", lng)})"
                        )
                    }
                }
                
                withContext(Dispatchers.Main) {
                    evaluateMockCapabilities()
                    // Refresh count
                    viewModelScope.launch(Dispatchers.IO) {
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                }
                return true
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE,
                            wifiApCount = 0,
                            canMockWifi = false
                        )
                    }
                }
                return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        wifiLoadStatus = com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE,
                        wifiApCount = 0,
                        canMockWifi = false
                    )
                }
            }
            return false
        }
    }

    private suspend fun fetchCellFromOpenCellIdSync(lat: Double, lng: Double): Boolean {
        val tokenToUse = settingsRepository.getOpencellidApiToken()
        if (tokenToUse.isBlank()) {
            android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Token is blank. Skipping request and database insertion.")
            return false
        }
        android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Starting request for coordinates ($lat, $lng)")
        val wgs84 = com.shiraka.locatiobprovid.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
        val wgsLat = wgs84.lat
        val wgsLng = wgs84.lng
        android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Converted GCJ-02 ($lat, $lng) -> WGS-84 ($wgsLat, $wgsLng) for OpenCellID query")

        try {
            val rawJsonArrayString = opencellidClient.fetchCellData(wgsLat, wgsLng, tokenToUse)
            val cellsArray = org.json.JSONArray(rawJsonArrayString)
            android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Received cell list size: ${cellsArray.length()}")
            if (cellsArray.length() > 0) {
                val formattedCells = normalizeCellArrayForStorage(cellsArray)
                if (formattedCells.length() == 0) {
                    android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: No usable cells after normalization.")
                    return false
                }

                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, "{}", formattedCells.toString(), "[]")
                    val newestLocation = environmentDao.getAllLocations().firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        environmentDao.updateMetadata(
                            newestLocation.id,
                            resolveNearbyPlaceName(lat, lng),
                            "由 OpenCellID 導入 · 經緯度: (${String.format("%.6f", lat)}, ${String.format("%.6f", lng)})"
                        )
                    }
                    android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Successfully inserted ${formattedCells.length()} cells into database.")
                }

                withContext(Dispatchers.Main) {
                    evaluateMockCapabilities()
                    // Refresh count
                    viewModelScope.launch(Dispatchers.IO) {
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                }
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun normalizeCellArrayForStorage(cellsArray: org.json.JSONArray): org.json.JSONArray {
        val formattedCells = org.json.JSONArray()
        for (i in 0 until cellsArray.length()) {
            val cell = cellsArray.optJSONObject(i) ?: continue
            val area = cellArea(cell)
            val identity = cellIdentity(cell)
            if (area <= 0 || identity <= 0) {
                android.util.Log.d("OpenCellID", "normalizeCellArrayForStorage: Skipping invalid cell: $cell")
                continue
            }

            val type = normalizeCellType(cell.optString("type", cell.optString("radio", "LTE")))
            val cellObj = org.json.JSONObject().apply {
                put("type", type)
                put("radio", cell.optString("radio", type))
                put("mcc", positiveCellInt(cell, "mcc", default = 460))
                put("mnc", positiveCellInt(cell, "mnc", "net", default = 0))
                put("tac", area)
                put("lac", area)
                put("ci", identity)
                put("cid", identity)
                put("cellid", identity)
                put("pci", positiveCellInt(cell, "pci", default = (identity % 504).coerceIn(0, 503)))
                put("dbm", cellSignalDbm(cell, i))
                put("isRegistered", cell.optBoolean("isRegistered", i == 0))
            }
            formattedCells.put(cellObj)
        }
        return formattedCells
    }

    private fun normalizeCellType(rawType: String): String {
        return when (rawType.uppercase(java.util.Locale.US)) {
            "GSM" -> "GSM"
            "UMTS", "WCDMA" -> "WCDMA"
            "NR", "NR5G", "5G" -> "NR"
            else -> "LTE"
        }
    }

    private fun cellArea(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "tac", "lac", "area", default = 0)

    private fun cellIdentity(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "ci", "cid", "cellid", "cell", default = 0)

    private fun positiveCellInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun cellSignalDbm(cell: org.json.JSONObject, index: Int): Int {
        val direct = cell.optInt("dbm", Int.MIN_VALUE)
        if (direct in -140..-40) return direct
        val average = cell.optInt("averageSignalStrength", Int.MIN_VALUE)
        if (average in -140..-40) return average
        val signal = cell.optInt("signal", Int.MIN_VALUE)
        if (signal in -140..-40) return signal
        return (-70 - index * 3).coerceAtLeast(-110)
    }

    // 定点模拟

    @android.annotation.SuppressLint("MissingPermission")
    fun startSpoofing() {
        val state = _uiState.value
        
        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(context, context.getString(com.shiraka.locatiobprovid.R.string.disable_continuous_scan_first), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val lng = state.longitudeInput.toDoubleOrNull()
        val lat = state.latitudeInput.toDoubleOrNull()
        if (lng == null || lat == null || lng !in -180.0..180.0 || lat !in -90.0..90.0) {
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }
        
        settingsRepository.isSpoofingActive = true
        settingsRepository.lastSpoofedLat = lat.toString()
        settingsRepository.lastSpoofedLng = lng.toString()
        
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSavingConfig = true,
                    startSpoofingProgress = StartSpoofingProgress(
                        phase = StartSpoofingPhase.PREPARING,
                        message = "正在檢查本地環境資料"
                    )
                )
            }
            
            val needsWifiFetch = state.mockWifi && !hasLocalWifiWithin50m(lat, lng)
            val needsCellFetch = state.mockCell && !hasLocalCellsWithin50m(lat, lng)
            val fetchSources = buildList {
                if (needsWifiFetch) add("WiGLE")
                if (needsCellFetch) add("OpenCellID")
            }

            if (fetchSources.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        startSpoofingProgress = StartSpoofingProgress(
                            phase = StartSpoofingPhase.FETCHING,
                            message = "正在從 ${fetchSources.joinToString(" / ")} 拉取資料",
                            sources = fetchSources
                        )
                    )
                }

                val failedSources = mutableListOf<String>()
                if (needsWifiFetch && !fetchWifiFromWigleSync(lat, lng)) {
                    failedSources += "WiGLE"
                }
                if (needsCellFetch && !fetchCellFromOpenCellIdSync(lat, lng)) {
                    failedSources += "OpenCellID"
                }
                if (failedSources.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isSavingConfig = false,
                            startSpoofingProgress = StartSpoofingProgress(
                                phase = StartSpoofingPhase.ERROR,
                                message = "資料拉取未完成",
                                sources = fetchSources,
                                errors = failedSources.map { source -> "$source 來源未取得可用資料" }
                            )
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    startSpoofingProgress = StartSpoofingProgress(
                        phase = StartSpoofingPhase.ENABLING,
                        message = "正在寫入模擬配置",
                        sources = fetchSources
                    )
                )
            }
            evaluateMockCapabilitiesSuspend(lat, lng)
            
            val updatedState = _uiState.value
            recordRecentLocation(
                SavedLocation(
                    String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng),
                    lat,
                    lng,
                    updatedState.collectedWifiJson,
                    updatedState.collectedCellJson
                )
            )
            val now = System.currentTimeMillis()
            locationRepository.startSpoofing(
                context, lat, lng,
                "STILL", 0f, now,
                emptyList(), false,
                updatedState.appCoordinateSystems,
                updatedState.collectedWifiJson,
                updatedState.collectedCellJson,
                updatedState.collectedBluetoothJson,
                updatedState.mockWifi && updatedState.canMockWifi,
                updatedState.mockCell,
                updatedState.mockBluetooth && updatedState.canMockBluetooth,
                updatedState.enableJitter,
                updatedState.jitterRadiusMeters,
                updatedState.jitterSpeed.name
            )
            
            // Wait briefly to ensure root shell syncs to disk fully
            kotlinx.coroutines.delay(200)

            _uiState.update {
                it.copy(
                    isSpoofingActive = true,
                    isSavingConfig = false,
                    startSpoofingProgress = StartSpoofingProgress(
                        phase = StartSpoofingPhase.SUCCESS,
                        message = "虛擬位置已啟用",
                        sources = fetchSources,
                        usedLocalCache = fetchSources.isEmpty()
                    )
                )
            }
        }
    }

    fun resetStartSpoofingProgress() {
        _uiState.update { it.copy(startSpoofingProgress = StartSpoofingProgress()) }
    }

    fun stopSpoofing() {
        settingsRepository.isSpoofingActive = false
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        viewModelScope.launch {
            locationRepository.stopSpoofing(context)
            _uiState.update {
                it.copy(isSpoofingActive = false)
            }
        }
    }

    fun cleanupRuntimeEnvironment(onComplete: (Boolean) -> Unit) {
        settingsRepository.isSpoofingActive = false
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE,
                    routePoints = emptyList(),
                    routeRunMode = RouteRunMode.MANUAL
                )
            }
            val ok = withContext(Dispatchers.IO) {
                locationRepository.cleanupRuntimeEnvironment(context)
            }
            onComplete(ok)
        }
    }

    // 摇杆控制

    fun moveByJoystick(bearing: Double, intensity: Float, maxSpeedMs: Float) {
        val elapsedSec = 0.1
        val distance = maxSpeedMs * intensity * elapsedSec
        val R = 6378137.0
        val bearingRad = Math.toRadians(bearing)
        val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
        val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val newLatRad = Math.asin(
            kotlin.math.sin(latRad) * kotlin.math.cos(distance / R) +
            kotlin.math.cos(latRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(bearingRad)
        )
        val newLngRad = lngRad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(latRad),
            kotlin.math.cos(distance / R) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad)
        )
        val newLat = Math.toDegrees(newLatRad)
        val newLng = Math.toDegrees(newLngRad)
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", newLat),
                longitudeInput = String.format("%.6f", newLng),
                simBearing = bearing.toFloat(),
                showCoordinateError = false
            )
        }
        // 实时同步给 SpooferProvider
        SpooferProvider.latitude = newLat
        SpooferProvider.longitude = newLng
        SpooferProvider.currentLatitude = newLat
        SpooferProvider.currentLongitude = newLng
        SpooferProvider.simBearing = bearing.toFloat()
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    // 路线规划状态机

    /** 进入全屏地图，进入选点阶段 */
    fun enterRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.SELECTING,
                routePoints = emptyList()
            )
        }
    }

    /** 地图中心确认添加路点 */
    fun addRoutePoint(lat: Double, lng: Double) {
        _uiState.update { it.copy(routePoints = it.routePoints + RoutePoint(lat, lng)) }
    }

    /** 撤销最后一个路点 */
    fun undoLastRoutePoint() {
        _uiState.update { state ->
            if (state.routePoints.isEmpty()) state
            else state.copy(routePoints = state.routePoints.dropLast(1))
        }
    }

    /** 结束选点 → READY */
    fun finishSelectingPoints() {
        if (_uiState.value.routePoints.size < 2) return
        _uiState.update { it.copy(routePlanStage = RoutePlanStage.READY) }
    }

    /** 重新选点：清空路点，回到 SELECTING */
    fun restartSelectingPoints() {
        _uiState.update {
            it.copy(
                routePoints = emptyList(),
                routePlanStage = RoutePlanStage.SELECTING
            )
        }
    }

    /** 设置路线运行模式 */
    fun setRouteRunMode(mode: RouteRunMode) {
        _uiState.update { it.copy(routeRunMode = mode) }
    }

    fun saveRoute(name: String, points: List<RoutePoint>) {
        viewModelScope.launch(Dispatchers.IO) {
            locationRepository.insertSavedRoute(name, points)
        }
    }
    
    fun deleteSavedRoute(route: com.shiraka.locatiobprovid.data.model.SavedRoute) {
        viewModelScope.launch(Dispatchers.IO) {
            // we delete by finding the entity with matching name
            // (a bit hacky but works for now, or we can add delete by name in DAO)
            val routes = locationRepository.getSavedRoutes().first()
            val entity = routes.find { it.name == route.name }
            if (entity != null) {
                locationRepository.deleteSavedRoute(entity)
            }
        }
    }

    /** 设置循环模式速度 */
    fun setRouteSimMode(mode: SimMode) {
        _uiState.update { it.copy(routeSimMode = mode) }
    }

    /** 设置自定义速度 (m/s) */
    fun setCustomSpeedMs(speed: Double) {
        _uiState.update { it.copy(customSpeedMs = speed.coerceIn(0.1, 100.0)) }
    }

    /** 获取实际生效的速度 (m/s) */
    private fun getEffectiveSpeedMs(): Double {
        val state = _uiState.value
        return if (state.routeSimMode == SimMode.CUSTOM) state.customSpeedMs
        else state.routeSimMode.speedMs
    }

    /** 首页地图确认选点 */
    fun confirmMapPoint(lat: Double, lng: Double) {
        val latStr = String.format("%.6f", lat)
        val lngStr = String.format("%.6f", lng)
        settingsRepository.lastSpoofedLat = latStr
        settingsRepository.lastSpoofedLng = lngStr
        _uiState.update {
            it.copy(
                latitudeInput = latStr,
                longitudeInput = lngStr,
                mapConfirmedPoint = Pair(lat, lng),
                showCoordinateError = false
            )
        }
        evaluateMockCapabilities()
    }

    fun updateSpoofingPositionFromMap(lat: Double, lng: Double): Boolean {
        val state = _uiState.value
        if (!state.isSpoofingActive) return false
        if (lng !in -180.0..180.0 || lat !in -90.0..90.0) return false

        val oldLat = state.latitudeInput.toDoubleOrNull()
        val oldLng = state.longitudeInput.toDoubleOrNull()
        if (oldLat != null && oldLng != null && haversineMeters(RoutePoint(oldLat, oldLng), RoutePoint(lat, lng)) < 0.5) {
            return false
        }

        autoRouteJob?.cancel()
        autoRouteJob = null
        settingsRepository.lastSpoofedLat = lat.toString()
        settingsRepository.lastSpoofedLng = lng.toString()
        updatePosition(lat, lng, state.simBearing, forceConfigUpdate = true)
        return true
    }

    fun selectConnectedWifi(bssid: String) {
        val state = _uiState.value
        val updatedJson = switchConnectedWifi(state.collectedWifiJson, bssid) ?: return
        _uiState.update { it.copy(collectedWifiJson = updatedJson) }
        if (state.isSpoofingActive) {
            syncMockSettings()
        }
    }

    /** 清除地图选点状态 */

    fun setUseRealRoute(use: Boolean) {
        _uiState.update { it.copy(useRealRoute = use) }
    }

    /**
     * 开始路线模拟。
     * - 手动模式：启动 spoofing（STILL），由摇杆驱动 moveByJoystick 实时更新坐标。
     * - 循环模式：启动 spoofing，自动沿路线点按速度移动，到终点后反向循环。
     */
    fun startRoutePlanning() {
        val state = _uiState.value
        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(context, context.getString(com.shiraka.locatiobprovid.R.string.disable_continuous_scan_route_first), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (state.routePoints.size < 2) return

        startSimulationWithPoints(state.routePoints, state)
    }

    private fun startSimulationWithPoints(pointsToRun: List<RoutePoint>, state: AppState) {
        val startPoint = pointsToRun.first()

        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", startPoint.lat),
                longitudeInput = String.format("%.6f", startPoint.lng),
                routePlanStage = RoutePlanStage.RUNNING,
                routePoints = pointsToRun
            )
        }

        val isLoop = state.routeRunMode == RouteRunMode.LOOP

        viewModelScope.launch {
            settingsRepository.isSpoofingActive = true
            settingsRepository.lastSpoofedLat = startPoint.lat.toString()
            settingsRepository.lastSpoofedLng = startPoint.lng.toString()
            val now = System.currentTimeMillis()
            
            locationRepository.startSpoofing(
                context, startPoint.lat, startPoint.lng,
                if (isLoop) state.routeSimMode.name else "STILL",
                0f, now, pointsToRun, isLoop, state.appCoordinateSystems,
                state.collectedWifiJson, state.collectedCellJson, state.collectedBluetoothJson,
                state.mockWifi, state.mockCell, state.mockBluetooth, state.enableJitter,
                state.jitterRadiusMeters, state.jitterSpeed.name
            )
            _uiState.update {
                it.copy(isSpoofingActive = true)
            }
        }

        if (isLoop) {
            startAutoRouteLoop()
        }
    }

    /** 停止路线模拟，重置所有状态 */
    fun cancelRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList(),
                routeRunMode = RouteRunMode.MANUAL
            )
        }
    }

    fun stopRoutePlanning() {
        settingsRepository.isSpoofingActive = false
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        viewModelScope.launch {
            locationRepository.stopSpoofing(context)
            _uiState.update {
                it.copy(
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE,
                    routePoints = emptyList(),
                    routeRunMode = RouteRunMode.MANUAL
                )
            }
        }
    }

    // 保存位置

    fun saveCurrentLocation(name: String) {
        val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
        val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
        val state = _uiState.value
        settingsRepository.addSavedLocation(SavedLocation(name, lat, lng, state.collectedWifiJson, state.collectedCellJson))
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun loadSavedLocation(loc: SavedLocation) {
        recordRecentLocation(loc)
        val wifiCount = try { org.json.JSONArray(loc.wifiJson).length() } catch(e: Exception) { 0 }
        _uiState.update { 
            it.copy(
                latitudeInput = String.format("%.6f", loc.lat),
                longitudeInput = String.format("%.6f", loc.lng),
                collectedWifiJson = loc.wifiJson,
                collectedCellJson = loc.cellJson,
                wifiApCount = wifiCount,
                wifiLoadStatus = if (wifiCount > 0) com.shiraka.locatiobprovid.data.model.WifiLoadStatus.DONE else com.shiraka.locatiobprovid.data.model.WifiLoadStatus.IDLE
            ) 
        }
        viewModelScope.launch {
            evaluateMockCapabilitiesSuspend(loc.lat, loc.lng)
        }
    }

    fun removeSavedLocation(location: SavedLocation) {
        settingsRepository.removeSavedLocation(location)
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun removeRecentLocation(location: SavedLocation) {
        settingsRepository.removeRecentLocation(location)
        _uiState.update { it.copy(recentLocations = settingsRepository.getRecentLocations()) }
    }

    private fun recordRecentLocation(location: SavedLocation) {
        settingsRepository.addRecentLocation(location)
        _uiState.update { it.copy(recentLocations = settingsRepository.getRecentLocations()) }
    }

    fun addSavedRoute(name: String) {
        val points = _uiState.value.routePoints
        if (points.size >= 2) {
            settingsRepository.addSavedRoute(com.shiraka.locatiobprovid.data.model.SavedRoute(name, points))
            _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
        }
    }

    fun removeSavedRoute(route: com.shiraka.locatiobprovid.data.model.SavedRoute) {
        settingsRepository.removeSavedRoute(route)
        _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
    }

    fun loadSavedRoute(route: com.shiraka.locatiobprovid.data.model.SavedRoute) {
        _uiState.update { it.copy(
            routePoints = route.points,
            routePlanStage = com.shiraka.locatiobprovid.data.model.RoutePlanStage.READY
        )}
    }

    // 搜索



    // 内部工具

    /**
     * 循环模式自动移动。
     * 按路点顺序移动，到终点后反向，不断循环。
     * 同时实时同步坐标到 SpooferProvider。
     */
    private fun startAutoRouteLoop() {
        autoRouteJob?.cancel()
        autoRouteJob = viewModelScope.launch(Dispatchers.Default) {
            val points = _uiState.value.routePoints
            if (points.size < 2) return@launch

            val speedMs = getEffectiveSpeedMs()
            if (speedMs <= 0.0) return@launch

            val tickMs = 100L
            val tickSec = tickMs / 1000.0
            var forward = true
            var segmentIndex = 0
            var progress = 0.0 // 当前段上已走过的距离（米）

            while (isActive) {
                val fromIdx = if (forward) segmentIndex else segmentIndex + 1
                val toIdx = if (forward) segmentIndex + 1 else segmentIndex
                val from = points[fromIdx]
                val to = points[toIdx]
                val segLen = haversineMeters(from, to)

                val stepDist = speedMs * tickSec
                progress += stepDist

                if (progress >= segLen) {
                    // 到达当前段终点
                    progress -= segLen
                    if (forward) {
                        segmentIndex++
                        if (segmentIndex >= points.lastIndex) {
                            // 到达终点，反向
                            forward = false
                            segmentIndex = points.lastIndex - 1
                            progress = 0.0
                        }
                    } else {
                        segmentIndex--
                        if (segmentIndex < 0) {
                            // 回到起点，正向
                            forward = true
                            segmentIndex = 0
                            progress = 0.0
                        }
                    }
                    // 重新获取段信息并继续
                    val newFrom = if (forward) points[segmentIndex] else points[segmentIndex + 1]
                    updatePosition(newFrom.lat, newFrom.lng, 0f)
                } else {
                    // 在段中间插值
                    val ratio = if (segLen > 0) progress / segLen else 0.0
                    val lat = from.lat + (to.lat - from.lat) * ratio
                    val lng = from.lng + (to.lng - from.lng) * ratio
                    val bearing = bearingBetween(from, to).toFloat()
                    updatePosition(lat, lng, bearing)
                }

                delay(tickMs)
            }
        }
    }

    private var lastDbQueryLat: Double = 0.0
    private var lastDbQueryLng: Double = 0.0

    private fun switchConnectedWifi(wifiJson: String, selectedBssid: String): String? {
        return try {
            val source = org.json.JSONObject(wifiJson)
            val selectedKey = selectedBssid.trim()
            if (selectedKey.isBlank()) return null

            val currentConnected = source.optJSONObject("connectedWifi")
            val nearby = source.optJSONArray("nearbyWifi") ?: org.json.JSONArray()
            var selected: org.json.JSONObject? = currentConnected
                ?.takeIf { it.optString("bssid", "").equals(selectedKey, ignoreCase = true) }

            if (selected == null) {
                for (i in 0 until nearby.length()) {
                    val candidate = nearby.optJSONObject(i) ?: continue
                    if (candidate.optString("bssid", "").equals(selectedKey, ignoreCase = true)) {
                        selected = candidate
                        break
                    }
                }
            }
            val selectedWifi = selected ?: return null

            val newNearby = org.json.JSONArray()
            fun addIfDifferent(wifi: org.json.JSONObject?) {
                if (wifi == null) return
                val bssid = wifi.optString("bssid", "")
                if (bssid.isBlank() || bssid.equals(selectedKey, ignoreCase = true)) return
                newNearby.put(org.json.JSONObject(wifi.toString()))
            }

            addIfDifferent(currentConnected)
            for (i in 0 until nearby.length()) {
                addIfDifferent(nearby.optJSONObject(i))
            }

            org.json.JSONObject(source.toString()).apply {
                put("isConnected", true)
                put("connectedWifi", org.json.JSONObject(selectedWifi.toString()))
                put("nearbyWifi", newNearby)
            }.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** 更新当前模拟位置到 UI 和 SpooferProvider */
    private fun updatePosition(lat: Double, lng: Double, bearing: Float, forceConfigUpdate: Boolean = false) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", lat),
                longitudeInput = String.format("%.6f", lng),
                simBearing = bearing,
                showCoordinateError = false
            )
        }
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.currentLatitude = lat
        SpooferProvider.currentLongitude = lng
        SpooferProvider.simBearing = bearing
        SpooferProvider.startTimestamp = System.currentTimeMillis()
        
        // Check if we need to query the database (e.g. moved more than 20 meters since last query)
        val dLat = Math.toRadians(lat - lastDbQueryLat)
        val dLng = Math.toRadians(lng - lastDbQueryLng)
        val a = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(lastDbQueryLat)) * kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.sin(dLng / 2).let { it * it }
        val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        
        if (forceConfigUpdate || distance > 20.0) {
            lastDbQueryLat = lat
            lastDbQueryLng = lng
            viewModelScope.launch(Dispatchers.IO) {
                val records = environmentDao.getNearestLocations(lat, lng, 3)
                if (records.isNotEmpty()) {
                    val record = records[0]
                    // Check if the closest record is actually within ~50 meters
                    val rLat = Math.toRadians(record.location.lat - lat)
                    val rLng = Math.toRadians(record.location.lng - lng)
                    val rA = kotlin.math.sin(rLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.cos(Math.toRadians(record.location.lat)) * kotlin.math.sin(rLng / 2).let { it * it }
                    val rDist = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(rA), kotlin.math.sqrt(1 - rA))
                    
                    if (rDist <= 50.0) {
                        val jsons = locationToJson(records, lat, lng)
                        SpooferProvider.cellJson = jsons.second
                        // Save config file with new cell_json and wifi_json and bluetoothJson
                        locationRepository.updateConfig(
                            lat = lat,
                            lng = lng,
                            simMode = "STILL",
                            simBearing = bearing,
                            startTime = SpooferProvider.startTimestamp,
                            routePoints = _uiState.value.routePoints,
                            isRouteMode = _uiState.value.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.RUNNING,
                            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                            wifiJson = jsons.first,
                            cellJson = jsons.second,
                            bluetoothJson = jsons.third
                        )
                    } else {
                        // No trusted local environment data around this point; keep RF data empty.
                        SpooferProvider.cellJson = "[]"
                        locationRepository.updateConfig(
                            lat = lat,
                            lng = lng,
                            simMode = "STILL",
                            simBearing = bearing,
                            startTime = SpooferProvider.startTimestamp,
                            routePoints = _uiState.value.routePoints,
                            isRouteMode = _uiState.value.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.RUNNING,
                            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                            wifiJson = "[]",
                            cellJson = "[]",
                            bluetoothJson = "[]"
                        )
                    }
                } else {
                    SpooferProvider.cellJson = "[]"
                    locationRepository.updateConfig(
                        lat = lat,
                        lng = lng,
                        simMode = "STILL",
                        simBearing = bearing,
                        startTime = SpooferProvider.startTimestamp,
                        routePoints = _uiState.value.routePoints,
                        isRouteMode = _uiState.value.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.RUNNING,
                        appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                        wifiJson = "[]",
                        cellJson = "[]",
                        bluetoothJson = "[]"
                    )
                }
            }
        }
    }

    private fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
        val R = 6378137.0
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * R * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
    }

    private fun bearingBetween(from: RoutePoint, to: RoutePoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val x = kotlin.math.sin(dLng) * kotlin.math.cos(lat2)
        val y = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
    }
    fun toggleMockWifi() {
        val newVal = !_uiState.value.mockWifi
        settingsRepository.mockWifi = newVal
        _uiState.update { it.copy(mockWifi = newVal) }
        syncMockSettings()
    }

    fun toggleMockCell() {
        val newVal = !_uiState.value.mockCell
        settingsRepository.mockCell = newVal
        _uiState.update { it.copy(mockCell = newVal) }
        syncMockSettings()
    }

    fun toggleMockBluetooth() {
        val newVal = !_uiState.value.mockBluetooth
        settingsRepository.mockBluetooth = newVal
        _uiState.update { it.copy(mockBluetooth = newVal) }
        syncMockSettings()
    }

    fun toggleEnableJitter() {
        val newVal = !_uiState.value.enableJitter
        settingsRepository.enableJitter = newVal
        _uiState.update { it.copy(enableJitter = newVal) }
        syncMockSettings()
    }

    fun setJitterRadiusMeters(value: Int) {
        val newVal = value.coerceIn(1, 80)
        if (_uiState.value.jitterRadiusMeters == newVal) return
        settingsRepository.jitterRadiusMeters = newVal
        SpooferProvider.jitterRadiusMeters = newVal
        _uiState.update { it.copy(jitterRadiusMeters = newVal) }
        syncMockSettings()
    }

    fun setJitterOptions(enabled: Boolean, radiusMeters: Int, speed: JitterSpeed) {
        val radius = radiusMeters.coerceIn(1, 80)
        settingsRepository.enableJitter = enabled
        settingsRepository.jitterRadiusMeters = radius
        settingsRepository.jitterSpeed = speed.name
        SpooferProvider.enableJitter = enabled
        SpooferProvider.jitterRadiusMeters = radius
        SpooferProvider.jitterSpeed = speed.name
        _uiState.update {
            it.copy(
                enableJitter = enabled,
                jitterRadiusMeters = radius,
                jitterSpeed = speed
            )
        }
        syncMockSettings()
    }
    
    private fun syncMockSettings() {
        if (_uiState.value.isSpoofingActive) {
            val state = _uiState.value
            val lat = state.latitudeInput.toDoubleOrNull() ?: return
            val lng = state.longitudeInput.toDoubleOrNull() ?: return
            viewModelScope.launch {
                locationRepository.updateConfig(
                    lat = lat,
                    lng = lng,
                    simMode = if (state.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.RUNNING) state.routeSimMode.name else "STILL",
                    simBearing = state.simBearing,
                    startTime = SpooferProvider.startTimestamp,
                    routePoints = state.routePoints,
                    isRouteMode = state.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.RUNNING,
                    appCoordinateSystems = state.appCoordinateSystems,
                    wifiJson = state.collectedWifiJson,
                    cellJson = state.collectedCellJson,
                    bluetoothJson = state.collectedBluetoothJson,
                    mockWifi = state.mockWifi,
                    mockCell = state.mockCell,
                    mockBluetooth = state.mockBluetooth,
                    enableJitter = state.enableJitter,
                    jitterRadiusMeters = state.jitterRadiusMeters,
                    jitterSpeed = state.jitterSpeed.name
                )
            }
        }
    }



    fun toggleContinuousScanning() {
        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(context, context.getString(com.shiraka.locatiobprovid.R.string.disable_continuous_scan_route_first), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(context, context.getString(com.shiraka.locatiobprovid.R.string.stop_spoofing_before_scan), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentState = _uiState.value.isContinuousScanning
        _uiState.update { it.copy(isContinuousScanning = !currentState) }
        
        if (!currentState) {
            // Start scanning
            continuousScanJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val realLoc = fetchRealLocationSilent(context)
                    if (realLoc != null) {
                        val lat = realLoc.first
                        val lng = realLoc.second
                        
                        val wifiJson = environmentScanner.scanWifi()
                        val cellJson = environmentScanner.scanCell()
                        val bluetoothJson = environmentScanner.scanBluetooth()
                        
                        saveEnvironmentData(lat, lng, wifiJson, cellJson, bluetoothJson)
                        
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                    
                    // Delay 10 seconds between scans
                    delay(10000)
                }
            }
        } else {
            // Stop scanning
            continuousScanJob?.cancel()
            continuousScanJob = null
        }
    }

    fun refreshRecordCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = environmentDao.getRecordCount()
            _uiState.update { it.copy(environmentRecordCount = count) }
        }
    }

    suspend fun getAllLocations(): List<com.shiraka.locatiobprovid.data.db.LocationRecord> {
        return withContext(Dispatchers.IO) { environmentDao.getAllLocations() }
    }

    suspend fun getLocationsInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        limit: Int = 1000
    ): List<com.shiraka.locatiobprovid.data.db.LocationRecord> {
        return withContext(Dispatchers.IO) {
            environmentDao.getLocationsInBounds(minLat, maxLat, minLng, maxLng, limit)
        }
    }

    suspend fun getLatestLocation(): com.shiraka.locatiobprovid.data.db.LocationRecord? {
        return withContext(Dispatchers.IO) { environmentDao.getLatestLocation() }
    }

    fun loadManageData() {
        _uiState.update { it.copy(manageDataIsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val list = environmentDao.getAllCompleteLocations()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(manageDataList = list, manageDataIsLoading = false) }
                evaluateMockCapabilities()
            }
            refreshNearbyPlaceNames(list)
        }
    }

    fun refreshNearbyPlaceNames(records: List<com.shiraka.locatiobprovid.data.db.CompleteLocation>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updates = mutableMapOf<String, String>()
            records.forEach { record ->
                val key = nearbyPlaceKey(record.location.lat, record.location.lng)
                if (_uiState.value.nearbyPlaceNames.containsKey(key)) return@forEach
                val existing = record.location.placeName
                    .takeIf { it.isNotBlank() && !isSourceLabel(it) && isReadablePlaceName(it) }
                val name = existing ?: resolveNearbyPlaceName(record.location.lat, record.location.lng)
                if (name.isNotBlank()) {
                    updates[key] = name
                    nearbyNameMemory[key] = name
                }
            }
            if (updates.isNotEmpty()) {
                _uiState.update { it.copy(nearbyPlaceNames = it.nearbyPlaceNames + updates) }
            }
        }
    }

    fun nearbyPlaceKey(lat: Double, lng: Double): String {
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lng)
    }

    private fun isSourceLabel(value: String): Boolean {
        return value.contains("OpenCellID", ignoreCase = true) ||
            value.contains("WiGLE", ignoreCase = true) ||
            value.contains("导入") ||
            value.contains("導入") ||
            value.contains("Import", ignoreCase = true)
    }

    private fun resolveNearbyPlaceName(lat: Double, lng: Double): String {
        val key = nearbyPlaceKey(lat, lng)
        nearbyNameMemory[key]?.let { return it }
        val apiKey = settingsRepository.getGoogleApiKey()
        if (apiKey.isNotBlank()) {
            resolveNearbyPlaceNameFromPlaces(lat, lng, apiKey)?.let {
                nearbyNameMemory[key] = it
                return it
            }
            resolveNearbyPlaceNameFromGoogleGeocoding(lat, lng, apiKey)?.let {
                nearbyNameMemory[key] = it
                return it
            }
        } else {
            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: no Google API key, using system geocoder only lat=$lat lng=$lng")
        }
        resolveNearbyPlaceNameFromSystem(lat, lng)?.let {
            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: system geocoder success lat=$lat lng=$lng name=$it")
            nearbyNameMemory[key] = it
            return it
        }
        return ""
    }

    private fun resolveNearbyPlaceNameFromPlaces(lat: Double, lng: Double, apiKey: String): String? {
        return try {
            val language = if (isDomesticEnvironment()) "zh-TW" else "en"
            val requestJson = org.json.JSONObject().apply {
                put("maxResultCount", 5)
                put("rankPreference", "DISTANCE")
                put("languageCode", language)
                put("locationRestriction", org.json.JSONObject().apply {
                    put("circle", org.json.JSONObject().apply {
                        put("center", org.json.JSONObject().apply {
                            put("latitude", lat)
                            put("longitude", lng)
                        })
                        put("radius", 120.0)
                    })
                })
            }
            val request = Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchNearby")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader("X-Goog-FieldMask", "places.displayName,places.types")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            geocodeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Places Nearby HTTP ${response.code} lat=$lat lng=$lng")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val places = org.json.JSONObject(body).optJSONArray("places")
                if (places == null || places.length() == 0) {
                    android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Places Nearby empty lat=$lat lng=$lng")
                    return null
                }
                for (i in 0 until places.length()) {
                    val place = places.optJSONObject(i) ?: continue
                    val name = place.optJSONObject("displayName")?.optString("text").orEmpty().trim()
                    if (isReadablePlaceName(name)) {
                        android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Places Nearby success lat=$lat lng=$lng name=$name")
                        return name
                    }
                }
                android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Places Nearby only unreadable names lat=$lat lng=$lng")
                null
            }
        } catch (e: Exception) {
            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Places Nearby failed lat=$lat lng=$lng error=${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun resolveNearbyPlaceNameFromGoogleGeocoding(lat: Double, lng: Double, apiKey: String): String? {
        return try {
            val language = if (isDomesticEnvironment()) "zh-TW" else "en"
            val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&language=$language&key=${
                URLEncoder.encode(apiKey, "UTF-8")
            }"
            val request = Request.Builder().url(url).build()
            geocodeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding HTTP ${response.code} lat=$lat lng=$lng")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = org.json.JSONObject(body)
                val status = json.optString("status")
                if (status != "OK") {
                    android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding status=$status error=${json.optString("error_message")} lat=$lat lng=$lng")
                    return null
                }
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) {
                    android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding empty results lat=$lat lng=$lng")
                    return null
                }
                val preferredTypes = listOf(
                    "point_of_interest",
                    "premise",
                    "park",
                    "airport",
                    "neighborhood",
                    "sublocality",
                    "locality",
                    "administrative_area_level_3",
                    "administrative_area_level_2"
                )
                for (type in preferredTypes) {
                    for (i in 0 until results.length()) {
                        val result = results.optJSONObject(i) ?: continue
                        val resultTypes = result.optJSONArray("types")?.toString().orEmpty()
                        if (!resultTypes.contains(type)) continue
                        val name = nameFromAddressComponents(result, type)
                        if (isReadablePlaceName(name)) {
                            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding type=$type success lat=$lat lng=$lng name=$name")
                            return name
                        }
                    }
                }
                for (i in 0 until results.length()) {
                    val result = results.optJSONObject(i) ?: continue
                    val fallback = result.optString("formatted_address")
                        .split(",")
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (isReadablePlaceName(fallback)) {
                        android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding formatted success lat=$lat lng=$lng name=$fallback")
                        return fallback
                    }
                }
                android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding only unreadable names lat=$lat lng=$lng")
                null
            }
        } catch (e: Exception) {
            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: Google Geocoding failed lat=$lat lng=$lng error=${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun nameFromAddressComponents(result: org.json.JSONObject, preferredType: String): String {
        val components = result.optJSONArray("address_components") ?: return ""
        for (i in 0 until components.length()) {
            val component = components.optJSONObject(i) ?: continue
            val types = component.optJSONArray("types")?.toString().orEmpty()
            if (types.contains(preferredType)) {
                return component.optString("long_name").trim()
            }
        }
        return ""
    }

    private fun isReadablePlaceName(value: String): Boolean {
        val name = value.trim()
        if (name.isBlank()) return false
        if (name.equals("Unnamed Road", ignoreCase = true)) return false
        if (name.contains("+")) return false
        if (name.matches(Regex("[0-9０-９\\-－ー丁目番地号之の\\s]+"))) return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun resolveNearbyPlaceNameFromSystem(lat: Double, lng: Double): String? {
        return try {
            val locale = if (isDomesticEnvironment()) Locale.TRADITIONAL_CHINESE else Locale.getDefault()
            val address = Geocoder(context, locale).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null
            listOf(
                address.subLocality,
                address.locality,
                address.adminArea,
                address.thoroughfare,
                address.featureName,
                address.getAddressLine(0)?.split(",")?.firstOrNull()
            ).firstOrNull { !it.isNullOrBlank() && isReadablePlaceName(it) }?.trim()
        } catch (e: Exception) {
            android.util.Log.d("LocationSpoofer_Debug", "resolveNearbyPlaceName: system geocoder failed lat=$lat lng=$lng error=${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun deleteManageData(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocations(ids)
            loadManageData()
            refreshRecordCount()
        }
    }

    fun deleteManageDataSingle(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocation(id)
            loadManageData()
            refreshRecordCount()
        }
    }

    fun updateManageDataMetadata(id: Long, placeName: String, remark: String) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateMetadata(id, placeName, remark)
            loadManageData()
        }
    }

    fun clearAllManageData() {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.clearAll()
            loadManageData()
            refreshRecordCount()
        }
    }

    fun toggleManageDataScreen(show: Boolean) {
        _uiState.update { it.copy(isManageDataScreen = show) }
        if (show) {
            loadManageData()
        }
    }


    fun setGoogleApiKey(key: String) {
        settingsRepository.setGoogleApiKey(key)
        _uiState.update { it.copy(googleApiKey = key) }
    }

    fun setWigleApiToken(token: String) {
        settingsRepository.setWigleApiToken(token)
        _uiState.update { it.copy(wigleToken = token) }
    }

    fun setOpencellidApiToken(token: String) {
        settingsRepository.setOpencellidApiToken(token)
        _uiState.update { it.copy(opencellidToken = token) }
    }

    @Suppress("DEPRECATION")
    private fun getAppSignatureSHA1(): String {
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            val signatures = info.signatures ?: return "Unknown"
            if (signatures.isEmpty()) return "Unknown"
            val cert = signatures[0].toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA1")
            val publicKey = md.digest(cert)
            val hexString = StringBuilder()
            for (b in publicKey) {
                val appendString = Integer.toHexString(0xFF and b.toInt())
                if (appendString.length == 1) hexString.append("0")
                hexString.append(appendString)
                hexString.append(":")
            }
            return hexString.toString().dropLast(1).uppercase()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    fun setAppCoordinateSystem(pkg: String, sys: String) {
        val currentMap = _uiState.value.appCoordinateSystems.toMutableMap()
        currentMap[pkg] = sys
        settingsRepository.setAppCoordinateSystems(currentMap)
        _uiState.update { it.copy(appCoordinateSystems = currentMap) }
        
        // If spoofing is active, update config
        if (_uiState.value.isSpoofingActive) {
            viewModelScope.launch {
                locationRepository.updateConfig(
                    SpooferProvider.latitude,
                    SpooferProvider.longitude,
                    SpooferProvider.simMode,
                    SpooferProvider.simBearing,
                    SpooferProvider.startTimestamp,
                    if (SpooferProvider.isRouteMode) parseRoutePoints(SpooferProvider.routeJson) else emptyList(),
                    SpooferProvider.isRouteMode,
                    currentMap
                )
            }
        }
    }

    fun removeAppCoordinateSystem(pkg: String) {
        val currentMap = _uiState.value.appCoordinateSystems.toMutableMap()
        currentMap.remove(pkg)
        settingsRepository.setAppCoordinateSystems(currentMap)
        _uiState.update { it.copy(appCoordinateSystems = currentMap) }

        if (_uiState.value.isSpoofingActive) {
            viewModelScope.launch {
                locationRepository.updateConfig(
                    SpooferProvider.latitude,
                    SpooferProvider.longitude,
                    SpooferProvider.simMode,
                    SpooferProvider.simBearing,
                    SpooferProvider.startTimestamp,
                    if (SpooferProvider.isRouteMode) parseRoutePoints(SpooferProvider.routeJson) else emptyList(),
                    SpooferProvider.isRouteMode,
                    currentMap
                )
            }
        }
    }
    
    private fun parseRoutePoints(json: String): List<RoutePoint> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveEnvironmentData(lat: Double, lng: Double, wifiJson: String, cellJson: String, bluetoothJson: String) {
        val locId = environmentDao.insertLocation(com.shiraka.locatiobprovid.data.db.LocationRecord(lat = lat, lng = lng))
        
        try {
            val wifiObj = org.json.JSONObject(wifiJson)
            val isConnected = wifiObj.optBoolean("isConnected", false)
            if (isConnected && wifiObj.has("connectedWifi")) {
                val conn = wifiObj.getJSONObject("connectedWifi")
                val connWifi = com.shiraka.locatiobprovid.data.db.LocationConnectedWifi(
                    locationId = locId,
                    bssid = conn.optString("bssid"),
                    ssid = conn.optString("ssid"),
                    vendor = conn.optString("vendor"),
                    macAddress = conn.optString("macAddress"),
                    frequency = conn.optInt("frequency"),
                    linkSpeed = conn.optInt("linkSpeed"),
                    level = conn.optInt("level"),
                    capabilities = conn.optString("capabilities"),
                    networkId = conn.optInt("networkId"),
                    wifiStandard = conn.optInt("wifiStandard")
                )
                environmentDao.insertConnectedWifi(connWifi)
            }
            
            val nearbyArr = wifiObj.optJSONArray("nearbyWifi")
            if (nearbyArr != null) {
                for (i in 0 until nearbyArr.length()) {
                    val obj = nearbyArr.getJSONObject(i)
                    val bssid = obj.optString("bssid")
                    if (bssid.isEmpty()) continue
                    environmentDao.insertWifiDevice(
                        com.shiraka.locatiobprovid.data.db.WifiDevice(
                            bssid = bssid,
                            ssid = obj.optString("ssid", ""),
                            frequency = obj.optInt("frequency", 0),
                            capabilities = obj.optString("capabilities", ""),
                            vendor = obj.optString("vendor", "")
                        )
                    )
                    environmentDao.insertLocationWifi(
                        com.shiraka.locatiobprovid.data.db.LocationWifi(
                            locationId = locId,
                            bssid = bssid,
                            level = obj.optInt("level", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val cellArr = org.json.JSONArray(cellJson)
            for (i in 0 until cellArr.length()) {
                val obj = cellArr.getJSONObject(i)
                val type = normalizeCellType(obj.optString("type", obj.optString("radio", "UNKNOWN")))
                val area = cellArea(obj)
                val identity = cellIdentity(obj)
                val tac = when (type) {
                    "LTE", "NR" -> area
                    else -> positiveCellInt(obj, "tac", default = 0)
                }
                val lac = when (type) {
                    "GSM", "WCDMA" -> area
                    else -> positiveCellInt(obj, "lac", default = 0)
                }
                val ci = when (type) {
                    "LTE" -> identity
                    else -> positiveCellInt(obj, "ci", default = 0)
                }
                val cid = when (type) {
                    "GSM", "WCDMA" -> identity
                    else -> positiveCellInt(obj, "cid", default = 0)
                }
                val nci = if (type == "NR") {
                    obj.optLong("nci", identity.toLong()).takeIf { it > 0L } ?: identity.toLong()
                } else {
                    obj.optLong("nci", 0L)
                }
                val basestationId = if (type == "CDMA") {
                    positiveCellInt(obj, "basestationId", "cellid", "cell", default = 0)
                } else {
                    positiveCellInt(obj, "basestationId", default = 0)
                }
                if (area <= 0 && identity <= 0 && basestationId <= 0) continue
                val cellKey = "${type}_${positiveCellInt(obj, "mcc", default = 460)}_${positiveCellInt(obj, "mnc", "net", default = 0)}_${tac}_${ci}_${cid}_${basestationId}_${nci}"
                val device = com.shiraka.locatiobprovid.data.db.CellDevice(
                    cellKey = cellKey, type = type,
                    mcc = positiveCellInt(obj, "mcc", default = 460),
                    mnc = positiveCellInt(obj, "mnc", "net", default = 0),
                    tac = tac, ci = ci,
                    pci = positiveCellInt(obj, "pci", default = if (identity > 0) (identity % 504).coerceIn(0, 503) else 0),
                    lac = lac, cid = cid,
                    psc = positiveCellInt(obj, "psc", default = 0),
                    nci = nci,
                    networkId = positiveCellInt(obj, "networkId", default = 0),
                    systemId = positiveCellInt(obj, "systemId", default = 0),
                    basestationId = basestationId
                )
                environmentDao.insertCellDevice(device)
                environmentDao.insertLocationCell(
                    com.shiraka.locatiobprovid.data.db.LocationCell(
                        locId,
                        cellKey,
                        cellSignalDbm(obj, i),
                        obj.optBoolean("isRegistered", i == 0)
                    )
                )
            }
        } catch (e: Exception) {}

        try {
            val btArr = org.json.JSONArray(bluetoothJson)
            for (i in 0 until btArr.length()) {
                val obj = btArr.getJSONObject(i)
                val address = obj.optString("address")
                if (address.isEmpty()) continue
                environmentDao.insertBluetoothDevice(com.shiraka.locatiobprovid.data.db.BluetoothDevice(address, obj.optString("name", ""), obj.optString("scanRecordHex", "")))
                environmentDao.insertLocationBluetooth(com.shiraka.locatiobprovid.data.db.LocationBluetooth(locId, address, obj.optInt("rssi", -60)))
            }
        } catch (e: Exception) {}
    }

    private fun locationToJson(records: List<com.shiraka.locatiobprovid.data.db.CompleteLocation>, targetLat: Double, targetLng: Double): Triple<String, String, String> {
        if (records.isEmpty()) return Triple("{}", "[]", "[]")

        val weights = records.map {
            val rLat = Math.toRadians(it.location.lat - targetLat)
            val rLng = Math.toRadians(it.location.lng - targetLng)
            val rA = kotlin.math.sin(rLat / 2).let { v -> v * v } + kotlin.math.cos(Math.toRadians(targetLat)) * kotlin.math.cos(Math.toRadians(it.location.lat)) * kotlin.math.sin(rLng / 2).let { v -> v * v }
            val dist = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(rA), kotlin.math.sqrt(1 - rA))
            val safeDist = kotlin.math.max(dist, 1.0)
            1.0 / (safeDist * safeDist)
        }

        // 1. Reconstruct connected Wi-Fi using the closest record's connectedWi-Fi
        val closestRecord = records.firstOrNull()
        val hasConnected = closestRecord?.connectedWifi != null
        val connectedObj = if (hasConnected) {
            val cw = closestRecord!!.connectedWifi!!
            org.json.JSONObject().apply {
                put("ssid", cw.ssid)
                put("bssid", cw.bssid)
                put("vendor", cw.vendor)
                put("macAddress", cw.macAddress)
                put("frequency", cw.frequency)
                put("channel", com.shiraka.locatiobprovid.utils.MacVendorHelper.frequencyToChannel(cw.frequency))
                put("linkSpeed", cw.linkSpeed)
                put("level", cw.level)
                put("capabilities", cw.capabilities)
                put("networkId", cw.networkId)
                put("wifiStandard", cw.wifiStandard)
            }
        } else {
            null
        }

        // 2. Interpolate nearby Wi-Fis
        val wifiMap = mutableMapOf<String, com.shiraka.locatiobprovid.data.db.LocationWithWifi>()
        val wifiLevels = mutableMapOf<String, Double>()
        val wifiWeights = mutableMapOf<String, Double>()
        
        records.forEachIndexed { i, rec ->
            rec.wifis.forEach { rw ->
                val bssid = rw.device.bssid
                if (!wifiMap.containsKey(bssid)) wifiMap[bssid] = rw
                wifiLevels[bssid] = (wifiLevels[bssid] ?: 0.0) + rw.locationWifi.level * weights[i]
                wifiWeights[bssid] = (wifiWeights[bssid] ?: 0.0) + weights[i]
            }
        }
        
        val nearbyArr = org.json.JSONArray()
        wifiMap.forEach { (bssid, rw) ->
            val w = wifiWeights[bssid]!!
            val interpolatedLevel = (wifiLevels[bssid]!! / w).toInt()
            val obj = org.json.JSONObject().apply {
                put("bssid", bssid)
                put("ssid", rw.device.ssid)
                put("vendor", rw.device.vendor)
                put("frequency", rw.device.frequency)
                put("channel", com.shiraka.locatiobprovid.utils.MacVendorHelper.frequencyToChannel(rw.device.frequency))
                put("capabilities", rw.device.capabilities)
                put("level", interpolatedLevel)
            }
            nearbyArr.put(obj)
        }

        val wifiResultObj = org.json.JSONObject().apply {
            put("isConnected", hasConnected)
            put("connectedWifi", connectedObj ?: org.json.JSONObject.NULL)
            put("nearbyWifi", nearbyArr)
        }
        val wifiArr = wifiResultObj // Just assign it to match the rest of the method variables if needed, or we return wifiResultObj.toString()

        
        val cellMap = mutableMapOf<String, com.shiraka.locatiobprovid.data.db.LocationWithCell>()
        val cellDbms = mutableMapOf<String, Double>()
        val cellWeights = mutableMapOf<String, Double>()
        
        records.forEachIndexed { i, rec ->
            rec.cells.forEach { rc ->
                val cellKey = rc.device.cellKey
                if (!cellMap.containsKey(cellKey)) cellMap[cellKey] = rc
                cellDbms[cellKey] = (cellDbms[cellKey] ?: 0.0) + rc.locationCell.dbm * weights[i]
                cellWeights[cellKey] = (cellWeights[cellKey] ?: 0.0) + weights[i]
            }
        }
        
        val cellArr = org.json.JSONArray()
        cellMap.forEach { (cellKey, rc) ->
            val w = cellWeights[cellKey]!!
            val interpolatedDbm = (cellDbms[cellKey]!! / w).toInt()
            val obj = org.json.JSONObject()
            obj.put("type", rc.device.type)
            obj.put("mcc", rc.device.mcc)
            obj.put("mnc", rc.device.mnc)
            obj.put("tac", rc.device.tac)
            obj.put("ci", rc.device.ci)
            obj.put("pci", rc.device.pci)
            obj.put("lac", rc.device.lac)
            obj.put("cid", rc.device.cid)
            obj.put("psc", rc.device.psc)
            obj.put("nci", rc.device.nci)
            obj.put("networkId", rc.device.networkId)
            obj.put("systemId", rc.device.systemId)
            obj.put("basestationId", rc.device.basestationId)
            obj.put("dbm", interpolatedDbm)
            obj.put("isRegistered", rc.locationCell.isRegistered)
            cellArr.put(obj)
        }
        
        val btMap = mutableMapOf<String, com.shiraka.locatiobprovid.data.db.LocationWithBluetooth>()
        val btRssis = mutableMapOf<String, Double>()
        val btWeights = mutableMapOf<String, Double>()
        
        records.forEachIndexed { i, rec ->
            rec.bluetooths.forEach { rb ->
                val address = rb.device.address
                if (!btMap.containsKey(address)) btMap[address] = rb
                btRssis[address] = (btRssis[address] ?: 0.0) + rb.locationBluetooth.rssi * weights[i]
                btWeights[address] = (btWeights[address] ?: 0.0) + weights[i]
            }
        }
        
        val btArr = org.json.JSONArray()
        btMap.forEach { (address, rb) ->
            val w = btWeights[address]!!
            val interpolatedRssi = (btRssis[address]!! / w).toInt()
            val obj = org.json.JSONObject()
            obj.put("address", address)
            obj.put("name", rb.device.name)
            obj.put("scanRecordHex", rb.device.scanRecordHex)
            obj.put("rssi", interpolatedRssi)
            btArr.put(obj)
        }
        
        return Triple(wifiArr.toString(), cellArr.toString(), btArr.toString())
    }

    fun exportEnvironmentData(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val locations = environmentDao.getAllCompleteLocations()
                val jsonStr = kotlinx.serialization.json.Json.encodeToString(locations)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonStr.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importEnvironmentData(uri: android.net.Uri, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (jsonStr != null) {
                    val locations: List<com.shiraka.locatiobprovid.data.db.CompleteLocation> = kotlinx.serialization.json.Json.decodeFromString(jsonStr)
                    locations.forEach { cl ->
                        val locId = environmentDao.insertLocation(cl.location)
                        cl.connectedWifi?.let { cw ->
                            val newCw = cw.copy(locationId = locId)
                            environmentDao.insertConnectedWifi(newCw)
                        }
                        cl.wifis.forEach { w ->
                            environmentDao.insertWifiDevice(w.device)
                            val lw = w.locationWifi.copy(locationId = locId)
                            environmentDao.insertLocationWifi(lw)
                        }
                        cl.cells.forEach { c ->
                            environmentDao.insertCellDevice(c.device)
                            val lc = c.locationCell.copy(locationId = locId)
                            environmentDao.insertLocationCell(lc)
                        }
                        cl.bluetooths.forEach { b ->
                            environmentDao.insertBluetoothDevice(b.device)
                            val lb = b.locationBluetooth.copy(locationId = locId)
                            environmentDao.insertLocationBluetooth(lb)
                        }
                    }
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                    launch(Dispatchers.Main) { onComplete() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getIgnoredVersion(): String = settingsRepository.getIgnoredVersion()

    fun setIgnoredVersion(version: String) {
        settingsRepository.setIgnoredVersion(version)
    }
}
