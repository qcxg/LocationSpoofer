package com.shiraka.locatiobprovid.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The single spatial policy for trusted local RF data.
 *
 * A coordinate is covered only when it falls in the same fixed world-grid hex
 * as at least one stored RF-bearing sample. Density never grows a cell and a
 * sample in a neighbouring cell is never borrowed across the shared edge.
 */
object EnvironmentCoveragePolicy {
    private const val EARTH_RADIUS_METERS = 6_378_137.0
    private const val MAX_MERCATOR_LATITUDE = 85.05112878
    private const val WORLD_Q_PERIOD = 771_244L

    const val HEX_RADIUS_METERS = 30.0
    const val MAX_SAME_CELL_DISTANCE_METERS = HEX_RADIUS_METERS * 2.0

    /*
     * A Web-Mercator world must contain an integer number of horizontal hex
     * steps, otherwise two coordinates immediately either side of the date
     * line can never share a cell. 771,244 is the closest integer for a 30 m
     * radius. The resulting radius differs from 30 m by about two micrometres.
     */
    private val worldWidthMeters = 2.0 * PI * EARTH_RADIUS_METERS
    private val gridRadiusMeters =
        worldWidthMeters / (sqrt(3.0) * WORLD_Q_PERIOD.toDouble())

    data class Cell(val q: Long, val r: Long)

    data class LongitudeRange(val min: Double, val max: Double)

    data class CandidateBounds(
        val minLat: Double,
        val maxLat: Double,
        val longitudeRanges: List<LongitudeRange>
    )

    internal data class CoordinatePadding(
        val latitudeDegrees: Double,
        val longitudeDegrees: Double
    )

    fun cellFor(latitude: Double, longitude: Double): Cell? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -MAX_MERCATOR_LATITUDE..MAX_MERCATOR_LATITUDE) return null
        if (longitude !in -180.0..180.0) return null

        val (x, y) = toMercatorMeters(latitude, longitude)
        val fractionalQ = (sqrt(3.0) / 3.0 * x - y / 3.0) / gridRadiusMeters
        val fractionalR = (2.0 / 3.0 * y) / gridRadiusMeters
        return roundAxial(fractionalQ, fractionalR).canonicalized()
    }

    fun isInCell(cell: Cell, latitude: Double, longitude: Double): Boolean =
        cellFor(latitude, longitude) == cell

    fun corners(cell: Cell): List<Pair<Double, Double>> {
        val canonicalCell = cell.canonicalized()
        val centerX = gridRadiusMeters * sqrt(3.0) *
            (canonicalCell.q + canonicalCell.r / 2.0)
        val centerY = gridRadiusMeters * 1.5 * canonicalCell.r
        return List(6) { corner ->
            val angle = Math.toRadians(60.0 * corner - 30.0)
            fromMercatorMeters(
                centerX + gridRadiusMeters * cos(angle),
                centerY + gridRadiusMeters * sin(angle)
            )
        }
    }

    /**
     * Returns a small indexed SQL pre-filter that is guaranteed to contain
     * every record which could share the target's hex. Kotlin then applies the
     * authoritative exact cell comparison.
     */
    fun candidateBounds(latitude: Double, longitude: Double): CandidateBounds? {
        if (cellFor(latitude, longitude) == null) return null

        // Add a tiny margin so floating-point values exactly on an SQL bound
        // cannot be lost before the exact cell comparison.
        val padding = coordinatePadding(latitude) ?: return null
        val normalizedLongitude = normalizeLongitude(longitude)

        return CandidateBounds(
            minLat = (latitude - padding.latitudeDegrees)
                .coerceAtLeast(-MAX_MERCATOR_LATITUDE),
            maxLat = (latitude + padding.latitudeDegrees)
                .coerceAtMost(MAX_MERCATOR_LATITUDE),
            longitudeRanges = longitudeRanges(normalizedLongitude, padding.longitudeDegrees)
        )
    }

    /** Conservative coordinate padding for any two points in one hex cell. */
    internal fun coordinatePadding(latitude: Double): CoordinatePadding? {
        if (!latitude.isFinite()) return null
        val safeLatitude = latitude.coerceIn(
            -MAX_MERCATOR_LATITUDE,
            MAX_MERCATOR_LATITUDE
        )
        // The grid radius is only micrometres above the public nominal radius;
        // this margin also protects inclusive SQL/map bounds from FP rounding.
        val radius = MAX_SAME_CELL_DISTANCE_METERS + 0.01
        return CoordinatePadding(
            latitudeDegrees = Math.toDegrees(radius / EARTH_RADIUS_METERS),
            longitudeDegrees = Math.toDegrees(
                radius / (
                    EARTH_RADIUS_METERS *
                        abs(cos(Math.toRadians(safeLatitude))).coerceAtLeast(0.01)
                    )
            )
        )
    }

    fun normalizeLongitude(longitude: Double): Double {
        if (!longitude.isFinite()) return Double.NaN
        var normalized = longitude % 360.0
        // Keep one canonical representation for the same meridian.
        if (normalized >= 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }

    private fun Cell.canonicalized(): Cell {
        val halfPeriod = WORLD_Q_PERIOD / 2L
        val positiveQ = Math.floorMod(q, WORLD_Q_PERIOD)
        val canonicalQ = if (positiveQ >= halfPeriod) {
            positiveQ - WORLD_Q_PERIOD
        } else {
            positiveQ
        }
        return if (canonicalQ == q) this else copy(q = canonicalQ)
    }

    private fun longitudeRanges(center: Double, delta: Double): List<LongitudeRange> {
        if (delta >= 180.0) return listOf(LongitudeRange(-180.0, 180.0))

        val min = center - delta
        val max = center + delta
        return when {
            min < -180.0 -> listOf(
                LongitudeRange(-180.0, max),
                LongitudeRange(min + 360.0, 180.0)
            )
            max > 180.0 -> listOf(
                LongitudeRange(min, 180.0),
                LongitudeRange(-180.0, max - 360.0)
            )
            else -> listOf(LongitudeRange(min, max))
        }
    }

    private fun roundAxial(q: Double, r: Double): Cell {
        val x = q
        val z = r
        val y = -x - z
        var roundedX = round(x)
        val roundedY = round(y)
        var roundedZ = round(z)

        val xDiff = abs(roundedX - x)
        val yDiff = abs(roundedY - y)
        val zDiff = abs(roundedZ - z)
        when {
            xDiff > yDiff && xDiff > zDiff -> roundedX = -roundedY - roundedZ
            yDiff > zDiff -> Unit
            else -> roundedZ = -roundedX - roundedY
        }
        return Cell(roundedX.toLong(), roundedZ.toLong())
    }

    private fun toMercatorMeters(latitude: Double, longitude: Double): Pair<Double, Double> {
        val x = EARTH_RADIUS_METERS * Math.toRadians(normalizeLongitude(longitude))
        val y = EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + Math.toRadians(latitude) / 2.0))
        return Pair(x, y)
    }

    private fun fromMercatorMeters(x: Double, y: Double): Pair<Double, Double> {
        val latitude = Math.toDegrees(2.0 * atan(exp(y / EARTH_RADIUS_METERS)) - PI / 2.0)
        val longitude = normalizeLongitude(Math.toDegrees(x / EARTH_RADIUS_METERS))
        return Pair(latitude, longitude)
    }
}
