package com.shiraka.locatiobprovid.data.repository

import com.shiraka.locatiobprovid.utils.WigleClient

class WifiRepository(private val wigleClient: WigleClient) {

    suspend fun validateToken(token: String): Boolean = wigleClient.validateToken(token)

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        wigleClient.fetchWifiData(lat, lng, token)
}
