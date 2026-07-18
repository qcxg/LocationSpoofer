package com.shiraka.locatiobprovid.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
class EnvironmentScanner(private val context: Context) {

    private val lastWifiErrorLogUptimeMs = AtomicLong(0L)
    private val lastCellErrorLogUptimeMs = AtomicLong(0L)

    private fun logScanFailure(kind: String, error: Exception) {
        val slot = if (kind == "Wi-Fi") lastWifiErrorLogUptimeMs else lastCellErrorLogUptimeMs
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = slot.get()
        if ((previous == 0L || now - previous >= 60_000L) && slot.compareAndSet(previous, now)) {
            android.util.Log.w("EnvironmentScanner", "$kind scan failed; returning an empty snapshot", error)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun scanWifi(): String = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val resultObj = JSONObject()
        try {
            var isWifiConnected = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val networks = connectivityManager.allNetworks
                for (network in networks) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        // 确保这个 Wi-Fi 网络具备基础的连接能力
                        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) || 
                            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            isWifiConnected = true
                            break
                        }
                    }
                }
            } else {
                val activeNetwork = connectivityManager.activeNetworkInfo
                isWifiConnected = activeNetwork != null && activeNetwork.type == android.net.ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected
            }

            resultObj.put("isConnected", isWifiConnected)

            val connectionInfo = wifiManager.connectionInfo
            val connectedBssid = if (isWifiConnected) connectionInfo?.bssid else null
            val results = wifiManager.scanResults ?: emptyList()

            // 1. 优先提取当前正在连接的 Wi-Fi 信息
            if (isWifiConnected && connectedBssid != null && connectedBssid != "02:00:00:00:00:00") {
                val connObj = JSONObject()
                connObj.put("bssid", connectedBssid)
                val rawSsid = connectionInfo.ssid
                var cleanSsid = if (rawSsid != null && rawSsid.startsWith("\"") && rawSsid.endsWith("\"")) {
                    rawSsid.substring(1, rawSsid.length - 1)
                } else {
                    rawSsid ?: ""
                }
                
                val match = results.find { it.BSSID == connectedBssid }
                // 如果是 unknown ssid 或为空，尝试从扫描结果中恢复真实 SSID
                if (cleanSsid == "<unknown ssid>" || cleanSsid.isEmpty()) {
                    if (match != null && !match.SSID.isNullOrEmpty()) {
                        cleanSsid = match.SSID
                    }
                }
                
                connObj.put("ssid", if (cleanSsid == "<unknown ssid>") "" else cleanSsid)
                connObj.put("vendor", MacVendorHelper.getVendor(connectedBssid))
                connObj.put("level", connectionInfo.rssi)
                connObj.put("frequency", connectionInfo.frequency)
                connObj.put("channel", MacVendorHelper.frequencyToChannel(connectionInfo.frequency))
                connObj.put("capabilities", match?.capabilities ?: "[WPA2-PSK-CCMP][ESS]")
                try { connObj.put("macAddress", connectionInfo.macAddress) } catch(e:Throwable){}
                try { connObj.put("linkSpeed", connectionInfo.linkSpeed) } catch(e:Throwable){}
                try { connObj.put("networkId", connectionInfo.networkId) } catch(e:Throwable){}
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try { connObj.put("wifiStandard", connectionInfo.wifiStandard) } catch(e:Throwable){}
                }
                resultObj.put("connectedWifi", connObj)
            } else {
                resultObj.put("connectedWifi", JSONObject.NULL)
            }

            // 2. 提取周围扫描到的其他 Wi-Fi（去重，不包含当前已连接的）
            val nearbyArray = JSONArray()
            val seenBssids = mutableSetOf<String>()
            if (isWifiConnected && connectedBssid != null) {
                seenBssids.add(connectedBssid)
            }
            results.forEach { scanResult ->
                val bssid = scanResult.BSSID
                if (bssid != null && !seenBssids.contains(bssid)) {
                    seenBssids.add(bssid)
                    val obj = JSONObject()
                    obj.put("bssid", bssid)
                    obj.put("ssid", scanResult.SSID ?: "")
                    obj.put("vendor", MacVendorHelper.getVendor(bssid))
                    obj.put("level", scanResult.level)
                    obj.put("capabilities", scanResult.capabilities ?: "")
                    obj.put("frequency", scanResult.frequency)
                    obj.put("channel", MacVendorHelper.frequencyToChannel(scanResult.frequency))
                    nearbyArray.put(obj)
                }
            }
            resultObj.put("nearbyWifi", nearbyArray)
        } catch (e: Exception) {
            logScanFailure("Wi-Fi", e)
            resultObj.put("isConnected", false)
            resultObj.put("connectedWifi", JSONObject.NULL)
            resultObj.put("nearbyWifi", JSONArray())
        }
        resultObj.toString()
    }

    @SuppressLint("MissingPermission")
    suspend fun scanCell(): String = withContext(Dispatchers.IO) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val jsonArray = JSONArray()
        try {
            val allCellInfo = telephonyManager.allCellInfo
            allCellInfo?.forEach { cellInfo ->
                val obj = JSONObject()
                obj.put("isRegistered", cellInfo.isRegistered)
                
                when (cellInfo) {
                    is CellInfoLte -> {
                        obj.put("type", "LTE")
                        val id = cellInfo.cellIdentity
                        obj.put("mcc", id.mccString?.toIntOrNull() ?: 460)
                        obj.put("mnc", id.mncString?.toIntOrNull() ?: 0)
                        obj.put("tac", id.tac)
                        obj.put("ci", id.ci)
                        obj.put("pci", id.pci)
                        val signal = cellInfo.cellSignalStrength
                        obj.put("dbm", signal.dbm)
                    }
                    is CellInfoGsm -> {
                        obj.put("type", "GSM")
                        val id = cellInfo.cellIdentity
                        obj.put("mcc", id.mccString?.toIntOrNull() ?: 460)
                        obj.put("mnc", id.mncString?.toIntOrNull() ?: 0)
                        obj.put("lac", id.lac)
                        obj.put("cid", id.cid)
                        val signal = cellInfo.cellSignalStrength
                        obj.put("dbm", signal.dbm)
                    }
                    is CellInfoWcdma -> {
                        obj.put("type", "WCDMA")
                        val id = cellInfo.cellIdentity
                        obj.put("mcc", id.mccString?.toIntOrNull() ?: 460)
                        obj.put("mnc", id.mncString?.toIntOrNull() ?: 0)
                        obj.put("lac", id.lac)
                        obj.put("cid", id.cid)
                        obj.put("psc", id.psc)
                        val signal = cellInfo.cellSignalStrength
                        obj.put("dbm", signal.dbm)
                    }
                    is CellInfoNr -> {
                        obj.put("type", "NR")
                        val id = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                        obj.put("mcc", id?.mccString?.toIntOrNull() ?: 460)
                        obj.put("mnc", id?.mncString?.toIntOrNull() ?: 0)
                        obj.put("tac", id?.tac ?: Int.MAX_VALUE)
                        obj.put("nci", id?.nci ?: Long.MAX_VALUE)
                        obj.put("pci", id?.pci ?: Int.MAX_VALUE)
                        val signal = cellInfo.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                        obj.put("dbm", signal?.dbm ?: 0)
                    }
                    is CellInfoCdma -> {
                        obj.put("type", "CDMA")
                        val id = cellInfo.cellIdentity
                        obj.put("networkId", id.networkId)
                        obj.put("systemId", id.systemId)
                        obj.put("basestationId", id.basestationId)
                        val signal = cellInfo.cellSignalStrength
                        obj.put("dbm", signal.dbm)
                    }
                }
                jsonArray.put(obj)
            }
        } catch (e: Exception) {
            logScanFailure("Cell", e)
        }
        jsonArray.toString()
    }

}
