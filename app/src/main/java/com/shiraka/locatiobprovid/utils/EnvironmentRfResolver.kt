package com.shiraka.locatiobprovid.utils

import com.shiraka.locatiobprovid.data.db.EnvironmentDao
import com.shiraka.locatiobprovid.data.db.LocationWithCell
import com.shiraka.locatiobprovid.data.db.LocationWithWifi
import com.shiraka.locatiobprovid.data.db.RfCoverageLocation
import com.shiraka.locatiobprovid.data.db.RuntimeRfLocation
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The single Room -> runtime RF resolver used by both UI capability checks and
 * the foreground runtime service. A painted hex and an accepted backend RF
 * snapshot therefore use exactly the same cell-membership rule.
 */
class EnvironmentRfResolver(private val environmentDao: EnvironmentDao) {

    data class ResolvedRfCoverage(
        val cell: EnvironmentCoveragePolicy.Cell?,
        val wifiJson: String,
        val cellJson: String,
        val bluetoothJson: String,
        val hasWifi: Boolean,
        val hasCell: Boolean,
        val hasBluetooth: Boolean,
        val wifiApCount: Int
    ) {
        companion object {
            fun empty(cell: EnvironmentCoveragePolicy.Cell? = null) = ResolvedRfCoverage(
                cell = cell,
                wifiJson = EMPTY_WIFI_JSON,
                cellJson = "[]",
                bluetoothJson = "[]",
                hasWifi = false,
                hasCell = false,
                hasBluetooth = false,
                wifiApCount = 0
            )
        }
    }

    suspend fun findCoverageInTargetCell(lat: Double, lng: Double): List<RfCoverageLocation> {
        val targetCell = EnvironmentCoveragePolicy.cellFor(lat, lng) ?: return emptyList()
        val bounds = EnvironmentCoveragePolicy.candidateBounds(lat, lng) ?: return emptyList()
        val candidates = bounds.longitudeRanges.flatMap { longitudeRange ->
            environmentDao.getRfCoverageLocationsInBounds(
                bounds.minLat,
                bounds.maxLat,
                longitudeRange.min,
                longitudeRange.max
            )
        }

        return candidates
            .distinctBy { it.location.id }
            .filter { candidate ->
                EnvironmentCoveragePolicy.isInCell(
                    targetCell,
                    candidate.location.lat,
                    candidate.location.lng
                )
            }
            .sortedWith(
                compareBy<RfCoverageLocation> {
                    distanceMeters(lat, lng, it.location.lat, it.location.lng)
                }.thenByDescending { it.location.timestamp }
            )
    }

    suspend fun resolve(lat: Double, lng: Double): ResolvedRfCoverage {
        val targetCell = EnvironmentCoveragePolicy.cellFor(lat, lng)
            ?: return ResolvedRfCoverage.empty()
        val coverageLocations = findCoverageInTargetCell(lat, lng)
        if (coverageLocations.isEmpty()) return ResolvedRfCoverage.empty(targetCell)

        // Bound relation loading while retaining at least one representative of
        // every RF type available in this exact cell.
        val selectedIds = LinkedHashSet<Long>()
        coverageLocations.firstOrNull()?.let { selectedIds += it.location.id }
        coverageLocations.firstOrNull { it.hasWifi }?.let { selectedIds += it.location.id }
        coverageLocations.firstOrNull { it.hasCell }?.let { selectedIds += it.location.id }
        for (coverageLocation in coverageLocations) {
            if (selectedIds.size >= MAX_RF_PAYLOAD_RECORDS) break
            selectedIds += coverageLocation.location.id
        }

        val records = environmentDao.getRuntimeRfLocationsByIds(selectedIds.toList())
        val order = coverageLocations.mapIndexed { index, point -> point.location.id to index }.toMap()
        val sortedRecords = records.sortedBy { order[it.location.id] ?: Int.MAX_VALUE }
        if (sortedRecords.isEmpty()) return ResolvedRfCoverage.empty(targetCell)

        val (wifiJson, cellJson) = locationToJson(sortedRecords, lat, lng)
        val hasWifi = sortedRecords.any { it.connectedWifi != null || it.wifis.isNotEmpty() }
        val hasCell = sortedRecords.any { it.cells.isNotEmpty() }
        val wifiApCount = runCatching {
            JSONObject(wifiJson).optJSONArray("nearbyWifi")?.length() ?: 0
        }.getOrDefault(0)

        return ResolvedRfCoverage(
            cell = targetCell,
            wifiJson = wifiJson,
            cellJson = cellJson,
            bluetoothJson = "[]",
            hasWifi = hasWifi,
            hasCell = hasCell,
            hasBluetooth = false,
            wifiApCount = wifiApCount
        )
    }

    private fun locationToJson(
        records: List<RuntimeRfLocation>,
        targetLat: Double,
        targetLng: Double
    ): Pair<String, String> {
        if (records.isEmpty()) return EMPTY_WIFI_JSON to "[]"

        val weights = records.map {
            val distance = distanceMeters(targetLat, targetLng, it.location.lat, it.location.lng)
            val safeDistance = max(distance, 1.0)
            1.0 / (safeDistance * safeDistance)
        }

        val closestConnectedRecord = records.firstOrNull { it.connectedWifi != null }
        val connectedObj = closestConnectedRecord?.connectedWifi?.let { wifi ->
            JSONObject().apply {
                put("ssid", wifi.ssid)
                put("bssid", wifi.bssid)
                put("vendor", wifi.vendor)
                put("macAddress", wifi.macAddress)
                put("frequency", wifi.frequency)
                put("channel", MacVendorHelper.frequencyToChannel(wifi.frequency))
                put("linkSpeed", wifi.linkSpeed)
                put("level", wifi.level)
                put("capabilities", wifi.capabilities)
                put("networkId", wifi.networkId)
                put("wifiStandard", wifi.wifiStandard)
            }
        }

        val wifiMap = mutableMapOf<String, LocationWithWifi>()
        val wifiLevels = mutableMapOf<String, Double>()
        val wifiWeights = mutableMapOf<String, Double>()
        records.forEachIndexed { index, record ->
            record.wifis.forEach { wifi ->
                val bssid = wifi.device.bssid
                wifiMap.putIfAbsent(bssid, wifi)
                wifiLevels[bssid] = (wifiLevels[bssid] ?: 0.0) + wifi.locationWifi.level * weights[index]
                wifiWeights[bssid] = (wifiWeights[bssid] ?: 0.0) + weights[index]
            }
        }
        val nearbyWifi = JSONArray()
        wifiMap.forEach { (bssid, wifi) ->
            val weight = wifiWeights[bssid] ?: return@forEach
            nearbyWifi.put(JSONObject().apply {
                put("bssid", bssid)
                put("ssid", wifi.device.ssid)
                put("vendor", wifi.device.vendor)
                put("frequency", wifi.device.frequency)
                put("channel", MacVendorHelper.frequencyToChannel(wifi.device.frequency))
                put("capabilities", wifi.device.capabilities)
                put("level", ((wifiLevels[bssid] ?: 0.0) / weight).toInt())
            })
        }
        val wifiResult = JSONObject().apply {
            put("isConnected", connectedObj != null)
            put("connectedWifi", connectedObj ?: JSONObject.NULL)
            put("nearbyWifi", nearbyWifi)
        }

        val cellMap = mutableMapOf<String, LocationWithCell>()
        val cellDbms = mutableMapOf<String, Double>()
        val cellWeights = mutableMapOf<String, Double>()
        records.forEachIndexed { index, record ->
            record.cells.forEach { cell ->
                val key = cell.device.cellKey
                cellMap.putIfAbsent(key, cell)
                cellDbms[key] = (cellDbms[key] ?: 0.0) + cell.locationCell.dbm * weights[index]
                cellWeights[key] = (cellWeights[key] ?: 0.0) + weights[index]
            }
        }
        val cellResult = JSONArray()
        cellMap.forEach { (key, cell) ->
            val weight = cellWeights[key] ?: return@forEach
            cellResult.put(JSONObject().apply {
                put("type", cell.device.type)
                put("mcc", cell.device.mcc)
                put("mnc", cell.device.mnc)
                put("tac", cell.device.tac)
                put("ci", cell.device.ci)
                put("pci", cell.device.pci)
                put("lac", cell.device.lac)
                put("cid", cell.device.cid)
                put("psc", cell.device.psc)
                put("nci", cell.device.nci)
                put("networkId", cell.device.networkId)
                put("systemId", cell.device.systemId)
                put("basestationId", cell.device.basestationId)
                put("dbm", ((cellDbms[key] ?: 0.0) / weight).toInt())
                put("isRegistered", cell.locationCell.isRegistered)
            })
        }

        return wifiResult.toString() to cellResult.toString()
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Double {
        val radius = 6_378_137.0
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLat = Math.toRadians(toLat - fromLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * radius * atan2(sqrt(h), sqrt(1 - h))
    }

    companion object {
        private const val MAX_RF_PAYLOAD_RECORDS = 32
        const val EMPTY_WIFI_JSON = "{\"isConnected\":false,\"connectedWifi\":null,\"nearbyWifi\":[]}"

        fun selectConnectedWifi(wifiJson: String, selectedBssid: String): String? {
            return try {
                val source = JSONObject(wifiJson)
                val selectedKey = selectedBssid.trim()
                if (selectedKey.isBlank()) return null
                val currentConnected = source.optJSONObject("connectedWifi")
                val nearby = source.optJSONArray("nearbyWifi") ?: JSONArray()
                var selected = currentConnected?.takeIf {
                    it.optString("bssid", "").equals(selectedKey, ignoreCase = true)
                }
                if (selected == null) {
                    for (index in 0 until nearby.length()) {
                        val candidate = nearby.optJSONObject(index) ?: continue
                        if (candidate.optString("bssid", "").equals(selectedKey, ignoreCase = true)) {
                            selected = candidate
                            break
                        }
                    }
                }
                val selectedWifi = selected ?: return null
                val newNearby = JSONArray()
                fun addIfDifferent(wifi: JSONObject?) {
                    if (wifi == null) return
                    val bssid = wifi.optString("bssid", "")
                    if (bssid.isBlank() || bssid.equals(selectedKey, ignoreCase = true)) return
                    newNearby.put(JSONObject(wifi.toString()))
                }
                addIfDifferent(currentConnected)
                for (index in 0 until nearby.length()) addIfDifferent(nearby.optJSONObject(index))

                JSONObject(source.toString()).apply {
                    put("isConnected", true)
                    put("connectedWifi", JSONObject(selectedWifi.toString()))
                    put("nearbyWifi", newNearby)
                }.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
