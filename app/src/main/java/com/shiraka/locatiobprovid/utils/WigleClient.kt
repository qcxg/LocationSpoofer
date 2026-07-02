package com.shiraka.locatiobprovid.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class WigleClient {
    private val client = OkHttpClient()
    private companion object {
        const val DEFAULT_WIFI_LIMIT = 10
    }

    /** 验证 token 是否有效（调用 /profile/user 接口） */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        return@withContext try {
            val request = Request.Builder()
                .url("https://api.wigle.net/api/v2/profile/user")
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext false
                org.json.JSONObject(body).optString("success") == "true"
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                return@withContext "[]"
            }

            val latrange1 = lat - 0.002
            val latrange2 = lat + 0.002
            val longrange1 = lng - 0.002
            val longrange2 = lng + 0.002

            val url =
                "https://api.wigle.net/api/v2/network/search?latrange1=$latrange1&latrange2=$latrange2&longrange1=$longrange1&longrange2=$longrange2"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext "[]"
                    val jsonObject = JSONObject(body)
                    val results = jsonObject.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        return@withContext "[]"
                    }

                    val wifiList = JSONArray()

                    for (i in 0 until results.length()) {
                        if (wifiList.length() >= DEFAULT_WIFI_LIMIT) break
                        val item = results.optJSONObject(i) ?: continue
                        val bssid = item.optString("netid", "").trim()
                        if (bssid.isBlank()) continue
                        val wifi = JSONObject()
                        wifi.put("ssid", item.optString("ssid", ""))
                        wifi.put("bssid", bssid)
                        wifi.put("source", "wigle")
                        wifiList.put(wifi)
                    }
                    return@withContext wifiList.toString()
                } else {
                    return@withContext "[]"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext "[]"
            }
        }
}
