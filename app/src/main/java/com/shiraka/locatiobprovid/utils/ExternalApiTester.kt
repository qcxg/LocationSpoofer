package com.shiraka.locatiobprovid.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Performs small, read-only requests against the external services configured in Settings.
 * Server response bodies are deliberately reduced to semantic result codes so credentials or
 * provider diagnostics never become part of the UI state or application logs.
 */
object ExternalApiTester {
    enum class ResultCode {
        SUCCESS,
        MISSING_CREDENTIAL,
        INVALID_CREDENTIAL,
        ACCESS_RESTRICTED,
        SERVICE_NOT_ENABLED,
        BILLING_REQUIRED,
        QUOTA_EXCEEDED,
        NETWORK_UNAVAILABLE,
        TIMEOUT,
        SERVER_UNAVAILABLE,
        INVALID_RESPONSE,
        UNKNOWN_ERROR
    }

    data class Result(
        val code: ResultCode,
        val httpStatus: Int? = null
    ) {
        val isSuccess: Boolean get() = code == ResultCode.SUCCESS
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Tests the Places API used by the app without changing the globally initialized SDK key. */
    suspend fun testGooglePlaces(apiKey: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result(ResultCode.MISSING_CREDENTIAL)

        val payload = JSONObject()
            .put("maxResultCount", 5)
            .put("rankPreference", "DISTANCE")
            .put("languageCode", "en")
            .put(
                "locationRestriction",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put(
                            "center",
                            JSONObject()
                                .put("latitude", 35.681236)
                                .put("longitude", 139.767125)
                        )
                        .put("radius", 120.0)
                )
            )

        val request = try {
            Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchNearby")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", "places.displayName,places.types")
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext Result(ResultCode.INVALID_CREDENTIAL)
        }

        execute(request) { status, body ->
            if (status in 200..299) {
                if (body.isBlank()) Result(ResultCode.INVALID_RESPONSE, status)
                else runCatching { JSONObject(body) }
                    .fold(
                        onSuccess = { Result(ResultCode.SUCCESS, status) },
                        onFailure = { Result(ResultCode.INVALID_RESPONSE, status) }
                    )
            } else {
                classifyGoogleError(status, body)
            }
        }
    }

    suspend fun testWigle(token: String): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result(ResultCode.MISSING_CREDENTIAL)

        val request = try {
            Request.Builder()
                .url("https://api.wigle.net/api/v2/profile/user")
                .header("Authorization", "Basic $token")
                .header("Accept", "application/json")
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext Result(ResultCode.INVALID_CREDENTIAL)
        }

        execute(request) { status, body ->
            when {
                status == 401 -> Result(ResultCode.INVALID_CREDENTIAL, status)
                status == 403 -> Result(ResultCode.ACCESS_RESTRICTED, status)
                status == 429 -> Result(ResultCode.QUOTA_EXCEEDED, status)
                status >= 500 -> Result(ResultCode.SERVER_UNAVAILABLE, status)
                status !in 200..299 -> Result(ResultCode.UNKNOWN_ERROR, status)
                body.isBlank() -> Result(ResultCode.INVALID_RESPONSE, status)
                else -> runCatching { JSONObject(body) }.fold(
                    onSuccess = { json ->
                        if (json.optBoolean("success", false)) {
                            Result(ResultCode.SUCCESS, status)
                        } else {
                            classifyProviderMessage(status, body)
                        }
                    },
                    onFailure = { Result(ResultCode.INVALID_RESPONSE, status) }
                )
            }
        }
    }

    suspend fun testOpenCellId(token: String): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result(ResultCode.MISSING_CREDENTIAL)

        val url = try {
            okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("opencellid.org")
                .addPathSegments("cell/getInArea")
                .addQueryParameter("key", token)
                .addQueryParameter("BBOX", "-0.0001,-0.0001,0.0001,0.0001")
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "1")
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext Result(ResultCode.INVALID_CREDENTIAL)
        }

        execute(Request.Builder().url(url).header("Accept", "application/json").build()) { status, body ->
            when {
                status == 401 -> Result(ResultCode.INVALID_CREDENTIAL, status)
                status == 403 -> classifyProviderMessage(status, body)
                status == 429 -> Result(ResultCode.QUOTA_EXCEEDED, status)
                status >= 500 -> Result(ResultCode.SERVER_UNAVAILABLE, status)
                status !in 200..299 -> Result(ResultCode.UNKNOWN_ERROR, status)
                body.isBlank() -> Result(ResultCode.INVALID_RESPONSE, status)
                else -> runCatching { JSONObject(body) }.fold(
                    onSuccess = { json ->
                        when {
                            !json.has("error") -> Result(ResultCode.SUCCESS, status)
                            // OpenCellID code 1 means that the area contains no cells; the key was accepted.
                            json.optInt("code", -1) == 1 -> Result(ResultCode.SUCCESS, status)
                            json.optInt("code", -1) == 2 -> Result(ResultCode.INVALID_CREDENTIAL, status)
                            else -> classifyProviderMessage(status, body)
                        }
                    },
                    onFailure = { Result(ResultCode.INVALID_RESPONSE, status) }
                )
            }
        }
    }

    private inline fun execute(
        request: Request,
        classify: (status: Int, body: String) -> Result
    ): Result {
        return try {
            client.newCall(request).execute().use { response ->
                classify(response.code, response.body?.string().orEmpty())
            }
        } catch (_: SocketTimeoutException) {
            Result(ResultCode.TIMEOUT)
        } catch (_: UnknownHostException) {
            Result(ResultCode.NETWORK_UNAVAILABLE)
        } catch (_: ConnectException) {
            Result(ResultCode.NETWORK_UNAVAILABLE)
        } catch (_: SSLException) {
            Result(ResultCode.NETWORK_UNAVAILABLE)
        } catch (_: IOException) {
            Result(ResultCode.NETWORK_UNAVAILABLE)
        } catch (_: Throwable) {
            Result(ResultCode.UNKNOWN_ERROR)
        }
    }

    private fun classifyGoogleError(status: Int, body: String): Result {
        val normalized = body.lowercase()
        return when {
            status == 401 || normalized.contains("api key not valid") ||
                normalized.contains("api_key_invalid") -> Result(ResultCode.INVALID_CREDENTIAL, status)
            status == 429 || normalized.contains("quota") || normalized.contains("rate limit") ||
                normalized.contains("resource_exhausted") -> Result(ResultCode.QUOTA_EXCEEDED, status)
            normalized.contains("billing") -> Result(ResultCode.BILLING_REQUIRED, status)
            normalized.contains("has not been used") || normalized.contains("not enabled") ||
                normalized.contains("service_disabled") || normalized.contains("service blocked") ||
                normalized.contains("api_key_service_blocked") -> Result(ResultCode.SERVICE_NOT_ENABLED, status)
            status == 403 || normalized.contains("permission_denied") ||
                normalized.contains("application restriction") ||
                normalized.contains("android app") -> Result(ResultCode.ACCESS_RESTRICTED, status)
            status >= 500 -> Result(ResultCode.SERVER_UNAVAILABLE, status)
            else -> Result(ResultCode.UNKNOWN_ERROR, status)
        }
    }

    private fun classifyProviderMessage(status: Int, body: String): Result {
        val normalized = body.lowercase()
        return when {
            normalized.contains("quota") || normalized.contains("rate limit") ||
                normalized.contains("too many") || normalized.contains("daily limit") ->
                Result(ResultCode.QUOTA_EXCEEDED, status)
            normalized.contains("api key") || normalized.contains("token") ||
                normalized.contains("credential") || normalized.contains("unauthorized") ->
                Result(ResultCode.INVALID_CREDENTIAL, status)
            status == 401 -> Result(ResultCode.INVALID_CREDENTIAL, status)
            status == 403 -> Result(ResultCode.ACCESS_RESTRICTED, status)
            status >= 500 -> Result(ResultCode.SERVER_UNAVAILABLE, status)
            else -> Result(ResultCode.UNKNOWN_ERROR, status)
        }
    }
}
