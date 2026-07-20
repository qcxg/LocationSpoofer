package com.shiraka.locatiobprovid.ui.components

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shiraka.locatiobprovid.data.model.AppMapType

import com.google.android.gms.maps.CameraUpdateFactory as GCameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView as GMapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory as GBitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.android.gms.maps.model.MarkerOptions as GMarkerOptions
import com.google.android.gms.maps.model.PolylineOptions as GPolylineOptions

interface AppMapMarker {
    fun setPosition(lat: Double, lng: Double)
}

enum class MarkerType { GREEN, RED, ORANGE, DEFAULT }

data class MapVisibleBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
)

interface AppMapController {
    fun clear()
    fun clearCoverage()
    fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float)
    fun addCoveragePolygon(points: List<Pair<Double, Double>>, fillColorInt: Int, strokeColorInt: Int, strokeWidth: Float)
    fun addCircle(lat: Double, lng: Double, radius: Double, fillColorInt: Int, strokeColorInt: Int, strokeWidth: Float)
    fun addMarker(lat: Double, lng: Double, title: String, type: MarkerType): AppMapMarker
    fun animateCamera(lat: Double, lng: Double, zoom: Float? = null)
    fun fitBounds(points: List<Pair<Double, Double>>, padding: Int)
    fun moveCamera(lat: Double, lng: Double, zoom: Float? = null)
    val cameraTargetLat: Double?
    val cameraTargetLng: Double?
    val cameraZoom: Float?
    fun visibleBounds(): MapVisibleBounds?
    fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit)
    fun disableUiControls()
    fun setMapType(type: AppMapType)
    fun setDarkMode(isDark: Boolean, context: android.content.Context)
}

class GMapControllerImpl(private val map: GoogleMap) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL
    private val coveragePolygons = mutableListOf<com.google.android.gms.maps.model.Polygon>()

    override fun setDarkMode(isDark: Boolean, context: android.content.Context) {
        isDarkMode = isDark
        try {
            if (isDark) {
                map.setMapStyle(com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(context, com.shiraka.locatiobprovid.R.raw.map_style_dark))
            } else {
                map.setMapStyle(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setMapType(currentMapType)
    }

    override fun clear() {
        map.clear()
        coveragePolygons.clear()
    }
    override fun clearCoverage() {
        coveragePolygons.forEach { polygon -> polygon.remove() }
        coveragePolygons.clear()
    }
    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        map.addPolyline(
            GPolylineOptions().color(colorInt).width(width).apply {
                points.forEach { add(GLatLng(it.first, it.second)) }
            }
        )
    }
    override fun addCoveragePolygon(points: List<Pair<Double, Double>>, fillColorInt: Int, strokeColorInt: Int, strokeWidth: Float) {
        val polygon = map.addPolygon(
            com.google.android.gms.maps.model.PolygonOptions()
                .fillColor(fillColorInt)
                .strokeColor(strokeColorInt)
                .strokeWidth(strokeWidth)
                .apply { points.forEach { add(GLatLng(it.first, it.second)) } }
        )
        coveragePolygons += polygon
    }
    override fun addCircle(lat: Double, lng: Double, radius: Double, fillColorInt: Int, strokeColorInt: Int, strokeWidth: Float) {
        map.addCircle(
            com.google.android.gms.maps.model.CircleOptions()
                .center(GLatLng(lat, lng))
                .radius(radius)
                .fillColor(fillColorInt)
                .strokeColor(strokeColorInt)
                .strokeWidth(strokeWidth)
        )
    }
    override fun addMarker(lat: Double, lng: Double, title: String, type: MarkerType): AppMapMarker {
        val hue = when(type) {
            MarkerType.GREEN -> GBitmapDescriptorFactory.HUE_GREEN
            MarkerType.RED -> GBitmapDescriptorFactory.HUE_RED
            MarkerType.ORANGE -> GBitmapDescriptorFactory.HUE_ORANGE
            else -> GBitmapDescriptorFactory.HUE_RED
        }
        val marker = map.addMarker(
            GMarkerOptions()
                .position(GLatLng(lat, lng))
                .title(title)
                .icon(GBitmapDescriptorFactory.defaultMarker(hue))
        )
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                marker?.position = GLatLng(lat, lng)
            }
        }
    }
    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        if (zoom != null) map.animateCamera(GCameraUpdateFactory.newLatLngZoom(GLatLng(lat, lng), zoom))
        else map.animateCamera(GCameraUpdateFactory.newLatLng(GLatLng(lat, lng)))
    }
    override fun fitBounds(points: List<Pair<Double, Double>>, padding: Int) {
        if (points.isEmpty()) return
        val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        points.forEach { builder.include(GLatLng(it.first, it.second)) }
        try {
            map.animateCamera(GCameraUpdateFactory.newLatLngBounds(builder.build(), padding))
        } catch (e: Exception) { e.printStackTrace() }
    }
    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        if (zoom != null) map.moveCamera(GCameraUpdateFactory.newLatLngZoom(GLatLng(lat, lng), zoom))
        else map.moveCamera(GCameraUpdateFactory.newLatLng(GLatLng(lat, lng)))
    }
    override val cameraTargetLat: Double? get() = map.cameraPosition.target.latitude
    override val cameraTargetLng: Double? get() = map.cameraPosition.target.longitude
    override val cameraZoom: Float? get() = map.cameraPosition.zoom
    override fun visibleBounds(): MapVisibleBounds? {
        val bounds = map.projection.visibleRegion.latLngBounds
        return MapVisibleBounds(
            minLat = bounds.southwest.latitude,
            maxLat = bounds.northeast.latitude,
            minLng = bounds.southwest.longitude,
            maxLng = bounds.northeast.longitude
        )
    }
    
    override fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit) {
        map.setOnCameraIdleListener {
            val target = map.cameraPosition.target
            onFinish(target.latitude, target.longitude)
        }
    }
    override fun disableUiControls() {
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.setAllGesturesEnabled(true)
    }

    override fun setMapType(type: AppMapType) {
        currentMapType = type
        when (type) {
            AppMapType.NORMAL -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(0f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }
            AppMapType.SATELLITE -> {
                map.mapType = GoogleMap.MAP_TYPE_HYBRID
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(0f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }
            AppMapType.MAP_3D -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                map.isBuildingsEnabled = true
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(45f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }
        }
    }
}

@Composable
fun AppMapView(modifier: Modifier = Modifier, onMapReady: (AppMapController) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var mapController by remember { mutableStateOf<AppMapController?>(null) }

    LaunchedEffect(isDark, mapController) {
        mapController?.setDarkMode(isDark, context)
    }

    val gmapView = remember(context) { GMapView(context) }
    DisposableEffect(lifecycle, gmapView) {
        var destroyed = false
        fun destroyOnce() {
            if (!destroyed) {
                destroyed = true
                gmapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME  -> gmapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> gmapView.onPause()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) gmapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) gmapView.onPause()
            destroyOnce()
        }
    }
    AndroidView(
        factory = {
            gmapView.apply {
                onCreate(Bundle())
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                getMapAsync { map -> 
                    val controller = GMapControllerImpl(map)
                    mapController = controller
                    controller.setDarkMode(isDark, context)
                    onMapReady(controller) 
                }
            }
        },
        modifier = modifier
    )
}
