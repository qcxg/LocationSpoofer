package com.shiraka.locatiobprovid.utils

import com.shiraka.locatiobprovid.data.db.LocationRecord
import com.shiraka.locatiobprovid.ui.components.AppMapController
import kotlin.math.abs
import kotlin.math.max

object MapCoverageHelper {
    /**
     * Draws fixed-size occupied hex cells instead of density-scaled circles.
     * Multiple samples in the same cell only strengthen confidence in that
     * cell; they must not expand the claimed coverage into nearby empty space.
     */
    fun drawCoverage(controller: AppMapController, locations: List<LocationRecord>) {
        controller.clearCoverage()
        if (locations.isEmpty()) return

        val visible = controller.visibleBounds()
        val filteredLocations = if (visible == null) {
            locations
        } else {
            // A wide viewport can have a much larger longitude scale at its
            // high-latitude edge than at its centre. Use the worst visible
            // latitude so a cell touching the viewport is never filtered out
            // merely because its stored sample is just outside the edge.
            val paddingLatitude = max(abs(visible.minLat), abs(visible.maxLat))
            val padding = EnvironmentCoveragePolicy.coordinatePadding(paddingLatitude)
                ?: return
            locations.filter { location ->
                location.lat in
                    (visible.minLat - padding.latitudeDegrees)..
                    (visible.maxLat + padding.latitudeDegrees) &&
                    longitudeInBounds(
                        location.lng,
                        visible.minLng - padding.longitudeDegrees,
                        visible.maxLng + padding.longitudeDegrees
                    )
            }
        }

        val occupiedCells = LinkedHashSet<EnvironmentCoveragePolicy.Cell>()
        for (location in filteredLocations) {
            val cell = EnvironmentCoveragePolicy.cellFor(location.lat, location.lng) ?: continue
            occupiedCells += cell
        }

        val fillColor = android.graphics.Color.argb(62, 46, 204, 113)
        val strokeColor = android.graphics.Color.argb(128, 46, 204, 113)
        occupiedCells.forEach { cell ->
            controller.addCoveragePolygon(
                points = EnvironmentCoveragePolicy.corners(cell),
                fillColorInt = fillColor,
                strokeColorInt = strokeColor,
                strokeWidth = 1.25f
            )
        }
    }

    private fun longitudeInBounds(longitude: Double, rawMin: Double, rawMax: Double): Boolean {
        if (rawMax - rawMin >= 360.0) return true
        val normalizedLongitude = EnvironmentCoveragePolicy.normalizeLongitude(longitude)
        val normalizedMin = EnvironmentCoveragePolicy.normalizeLongitude(rawMin)
        val normalizedMax = EnvironmentCoveragePolicy.normalizeLongitude(rawMax)
        return if (normalizedMin <= normalizedMax) {
            normalizedLongitude in normalizedMin..normalizedMax
        } else {
            normalizedLongitude >= normalizedMin || normalizedLongitude <= normalizedMax
        }
    }
}
