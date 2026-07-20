package com.shiraka.locatiobprovid.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts user-facing GCJ-02 coordinates into the WGS-84 coordinates required by
 * Android framework locations and external RF data providers.
 */
object CoordinateUtils {

    private const val PI = Math.PI
    private const val A = 6378245.0           // 克拉索夫斯基椭球体长半轴
    private const val EE = 0.00669342162296594 // 偏心率平方

    data class LatLng(val lat: Double, val lng: Double)

    /** Converts a GCJ-02 map coordinate to the canonical WGS-84 runtime coordinate. */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): LatLng {
        if (outOfChina(gcjLat, gcjLng)) return LatLng(gcjLat, gcjLng)
        val d = delta(gcjLat, gcjLng)
        return LatLng(gcjLat - d.lat, gcjLng - d.lng)
    }

    private fun delta(lat: Double, lng: Double): LatLng {
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        return LatLng(
            (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI),
            (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        )
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

}
