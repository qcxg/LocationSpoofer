package com.shiraka.locatiobprovid.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class RootManager {

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("id").contains("uid=0(root)")
    }

    fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    suspend fun cleanupRuntimeEnvironment(): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand(
            """
            sleep 2
            rm -f /data/local/tmp/locationspoofer_config.json
            rm -f /data/system/locationspoofer_config.json
            cmd location providers send-extra-command gps delete_aiding_data 2>/dev/null || true
            cmd location providers send-extra-command gps force_time_injection 2>/dev/null || true
            cmd location providers send-extra-command gps force_psds_injection 2>/dev/null || true
            cmd location set-location-enabled false 2>/dev/null || true
            sleep 1
            cmd location set-location-enabled true 2>/dev/null || true
            am force-stop com.google.android.gms 2>/dev/null || true
            am force-stop com.google.android.apps.maps 2>/dev/null || true
            """.trimIndent()
        )
        result != "ERROR"
    }
}
