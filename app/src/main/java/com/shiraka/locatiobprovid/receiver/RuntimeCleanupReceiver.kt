package com.shiraka.locatiobprovid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiraka.locatiobprovid.BuildConfig
import com.shiraka.locatiobprovid.provider.SpooferProvider
import com.shiraka.locatiobprovid.utils.ConfigManager
import com.shiraka.locatiobprovid.utils.RootManager
import com.shiraka.locatiobprovid.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RuntimeCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        SpooferProvider.isActive = false
        SpooferProvider.publishRuntimeConfigGeneration(0L)
        SpooferProvider.publishAnchorPosition(0.0, 0.0)
        SpooferProvider.publishCurrentPosition(0.0, 0.0)
        SpooferProvider.publishRuntimeHeartbeat(0L, "")
        SpooferProvider.clearRfForCoverageTransition()
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        SpooferProvider.appCoordinateSystemsJson = "{}"
        SettingsManager(context.applicationContext).apply {
            isSpoofingActive = false
            // Release replacement still requires an LSPosed reload. Debug APKs are
            // routinely replaced for host/service-only testing and must remain usable;
            // the tester explicitly reboots whenever hook bytecode itself changed.
            moduleRestartRequired = action == Intent.ACTION_MY_PACKAGE_REPLACED &&
                !BuildConfig.DEBUG
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val saved = ConfigManager(RootManager()).saveConfig(0.0, 0.0, false)
                if (saved) {
                    Log.d("RuntimeCleanupReceiver", "runtime config marked inactive after $action")
                } else {
                    Log.e(
                        "RuntimeCleanupReceiver",
                        "runtime stop fence was requested, but inactive files were not fully persisted after $action"
                    )
                }
            } catch (e: Throwable) {
                Log.e("RuntimeCleanupReceiver", "failed to mark runtime config inactive after $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
