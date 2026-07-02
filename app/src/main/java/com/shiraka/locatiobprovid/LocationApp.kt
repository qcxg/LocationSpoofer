package com.shiraka.locatiobprovid

import android.app.Application
import com.google.android.libraries.places.api.Places
import com.shiraka.locatiobprovid.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationApp : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        private val _isModuleActive = MutableStateFlow(false)
        val isModuleActive: StateFlow<Boolean> = _isModuleActive
        var mService: XposedService? = null
        lateinit var instance: LocationApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            XposedServiceHelper.registerListener(this)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val googleApiKey = prefs.getString("google_api_key", "")
        val keyToUse = if (!googleApiKey.isNullOrEmpty()) googleApiKey else BuildConfig.GOOGLE_MAPS_API_KEY
        if (!keyToUse.isNullOrEmpty()) {
            if (!Places.isInitialized()) {
                Places.initialize(this, keyToUse)
            }
        } else {
            android.util.Log.w("LocationApp", "Google Places API key is empty.")
        }

        startKoin {
            androidLogger()
            androidContext(this@LocationApp)
            modules(appModule)
        }
    }

    override fun onServiceBind(service: XposedService) {
        mService = service
        _isModuleActive.value = true
    }

    override fun onServiceDied(service: XposedService) {
        mService = null
        _isModuleActive.value = false
    }
}
