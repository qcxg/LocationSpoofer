package com.shiraka.locatiobprovid.di

import com.shiraka.locatiobprovid.data.repository.LocationRepository
import com.shiraka.locatiobprovid.data.repository.SettingsRepository
import com.shiraka.locatiobprovid.utils.ConfigManager
import com.shiraka.locatiobprovid.utils.LSPosedManager
import com.shiraka.locatiobprovid.utils.RootManager
import com.shiraka.locatiobprovid.utils.SettingsManager
import com.shiraka.locatiobprovid.viewmodel.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { RootManager() }
    single { ConfigManager(get()) }
    single { LSPosedManager() }
    single { SettingsManager(androidContext()) }
    single { com.shiraka.locatiobprovid.utils.EnvironmentScanner(androidContext()) }

    single { com.shiraka.locatiobprovid.utils.WigleClient() }
    single { com.shiraka.locatiobprovid.utils.OpenCellIdClient() }
    single { com.shiraka.locatiobprovid.data.repository.WifiRepository(get()) }

    single { LocationRepository(get(), get(), get(), get(), get()) }
    single { SettingsRepository(get()) }

    single { com.shiraka.locatiobprovid.data.db.AppDatabase.getDatabase(androidContext()) }
    single { get<com.shiraka.locatiobprovid.data.db.AppDatabase>().environmentDao() }
    single { get<com.shiraka.locatiobprovid.data.db.AppDatabase>().savedRouteDao() }

    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext()) }
}
