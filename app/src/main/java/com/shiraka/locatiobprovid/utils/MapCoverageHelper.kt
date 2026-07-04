package com.shiraka.locatiobprovid.utils

import com.shiraka.locatiobprovid.data.db.LocationRecord
import com.shiraka.locatiobprovid.ui.components.AppMapController
import kotlin.math.floor
import kotlin.math.sqrt

object MapCoverageHelper {
    /**
     * Draws collected coverage like mainstream map apps: nearby zooms show each
     * collected point, wider zooms group dense areas into aggregate coverage.
     */
    fun drawCoverage(controller: AppMapController, locations: List<LocationRecord>, zoom: Float? = controller.cameraZoom) {
        if (locations.isEmpty()) return

        val currentZoom = zoom ?: 15f
        val fillColor = android.graphics.Color.argb(54, 46, 204, 113)
        val strokeColor = android.graphics.Color.argb(112, 46, 204, 113)

        if (currentZoom >= 16.5f) {
            locations.take(350).forEach { loc ->
                controller.addCircle(loc.lat, loc.lng, 48.0, fillColor, strokeColor, 2f)
            }
            return
        }

        val cellSize = cellSizeDegrees(currentZoom)
        locations
            .groupBy { loc ->
                Pair(floor(loc.lat / cellSize).toLong(), floor(loc.lng / cellSize).toLong())
            }
            .values
            .map { group ->
                val count = group.size
                val lat = group.sumOf { it.lat } / count
                val lng = group.sumOf { it.lng } / count
                Cluster(lat, lng, count)
            }
            .sortedByDescending { it.count }
            .take(260)
            .forEach { cluster ->
                val radius = (60.0 + sqrt(cluster.count.toDouble()) * 32.0).coerceAtMost(maxRadiusForZoom(currentZoom))
                controller.addCircle(cluster.lat, cluster.lng, radius, fillColor, strokeColor, 2f)
            }
    }

    private data class Cluster(val lat: Double, val lng: Double, val count: Int)

    private fun cellSizeDegrees(zoom: Float): Double {
        return when {
            zoom < 10f -> 0.04
            zoom < 12f -> 0.02
            zoom < 14f -> 0.008
            zoom < 16f -> 0.003
            else -> 0.0012
        }
    }

    private fun maxRadiusForZoom(zoom: Float): Double {
        return when {
            zoom < 10f -> 900.0
            zoom < 12f -> 650.0
            zoom < 14f -> 420.0
            zoom < 16f -> 240.0
            else -> 140.0
        }
    }
}
