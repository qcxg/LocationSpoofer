package com.shiraka.locatiobprovid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import com.shiraka.locatiobprovid.ui.screen.BlockingScreen
import com.shiraka.locatiobprovid.ui.screen.FullScreenMapPage
import com.shiraka.locatiobprovid.ui.screen.InitializingScreen
import com.shiraka.locatiobprovid.ui.screen.SpoofingScreen
import com.shiraka.locatiobprovid.ui.screen.LanguageSelectionScreen
import com.shiraka.locatiobprovid.ui.theme.AppColorSchemeDark
import com.shiraka.locatiobprovid.ui.theme.AppColorSchemeLight
import com.shiraka.locatiobprovid.utils.LocaleUtils
import com.shiraka.locatiobprovid.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isLangSet = prefs.getBoolean("is_language_set", false)
        val savedLang = if (isLangSet) prefs.getString("language", "") ?: "" else ""
        val lang = savedLang.takeIf { it in SUPPORTED_LANGUAGE_TAGS }.orEmpty()
        
        val context = if (lang.isNotEmpty()) {
            LocaleUtils.wrap(newBase, lang)
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 仅在初始启动或语言变更后同步系统 Locale
        // AppCompatDelegate.setApplicationLocales 会自动处理持久化
        val savedLang = viewModel.getSavedLanguage()
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (savedLang.isNotEmpty() && (currentLocales.isEmpty || currentLocales.toLanguageTags() != savedLang)) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(savedLang)
            )
        }

        checkAndRequestPermissions()

        // 拦截 Back 键：模拟定位开启时退到后台，由前台服务与通知保持运行
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.isSpoofingActive) {
                    moveTaskToBack(true)
                } else {
                    finish()
                }
            }
        })

        setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                val uiState by viewModel.uiState.collectAsState()
                val isDark = isSystemInDarkTheme()
                val colorScheme = if (isDark) AppColorSchemeDark else AppColorSchemeLight
                val baseDensity = LocalDensity.current
                val appDensity = remember(baseDensity.density, baseDensity.fontScale) {
                    Density(baseDensity.density, baseDensity.fontScale.coerceAtMost(1.15f))
                }

                // 核心：在 Compose 层级内部通过 CompositionLocalProvider 动态刷新
                // 使用 remember(uiState.currentLanguage) 确保语言切换时重新计算 Context
                val context = LocalContext.current
                val wrappedContext = remember(uiState.currentLanguage) {
                    if (uiState.currentLanguage.isNotEmpty()) {
                        LocaleUtils.wrap(context, uiState.currentLanguage)
                    } else {
                        context
                    }
                }
                val configuration = wrappedContext.resources.configuration

                CompositionLocalProvider(
                    LocalContext provides wrappedContext,
                    LocalConfiguration provides configuration,
                    LocalDensity provides appDensity
                ) {
                    MaterialTheme(colorScheme = colorScheme) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            MainScreen(viewModel = viewModel, uiState = uiState, isDark = isDark)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val SUPPORTED_LANGUAGE_TAGS = setOf("en", "zh", "zh-TW")
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissions(notGranted.toTypedArray(), 100)
        } else {
            checkBackgroundLocation()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
            }
        }
    }

    @Deprecated("Deprecated in Android framework; kept for permission flow compatibility.")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkBackgroundLocation()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }
}

private data class MainNavState(
    val fullScreen: Boolean,
    val scanner: Boolean,
    val manage: Boolean,
    val settings: Boolean
)

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    uiState: com.shiraka.locatiobprovid.data.model.AppState,
    isDark: Boolean
) {
    var isFullScreenMap by remember { mutableStateOf(false) }
    var isScannerMap by remember { mutableStateOf(false) }
    var isSettingsScreen by remember { mutableStateOf(false) }

    val closeMapAndResetRouteIfNeeded = {
        if (uiState.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.SELECTING ||
            uiState.routePlanStage == com.shiraka.locatiobprovid.data.model.RoutePlanStage.READY) {
            viewModel.cancelRoutePlanning()
        }
        isFullScreenMap = false
    }

    BackHandler(enabled = isFullScreenMap || isScannerMap || uiState.isManageDataScreen || isSettingsScreen) {
        if (uiState.isManageDataScreen) {
            viewModel.toggleManageDataScreen(false)
        } else if (isSettingsScreen) {
            isSettingsScreen = false
        } else if (isScannerMap) {
            isScannerMap = false
        } else {
            closeMapAndResetRouteIfNeeded()
        }
    }

    AnimatedContent(
        targetState = MainNavState(isFullScreenMap, isScannerMap, uiState.isManageDataScreen, isSettingsScreen),
        transitionSpec = {
            if (initialState.fullScreen || targetState.fullScreen) {
                // Window scale/fade expanding and shrinking transitions
                if (targetState.fullScreen) {
                    scaleIn(initialScale = 0.8f, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400)) togetherWith
                    scaleOut(targetScale = 1.2f, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400))
                } else {
                    scaleIn(initialScale = 1.2f, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400)) togetherWith
                    scaleOut(targetScale = 0.8f, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400))
                }
            } else {
                val isEntering = (targetState.settings || targetState.manage || targetState.scanner) && 
                                 !(initialState.settings || initialState.manage || initialState.scanner)
                if (isEntering) {
                    slideInHorizontally(tween(400)) { it } togetherWith slideOutHorizontally(tween(400)) { -it }
                } else {
                    slideInHorizontally(tween(400)) { -it } togetherWith slideOutHorizontally(tween(400)) { it }
                }
            }
        },
        label = "screen_transition"
    ) { navState ->
        if (navState.fullScreen) {
            FullScreenMapPage(
                viewModel = viewModel,
                uiState = uiState,
                isDark = isDark,
                isInPipMode = false,
                onClose = { closeMapAndResetRouteIfNeeded() }
            )
        } else if (navState.scanner) {
            com.shiraka.locatiobprovid.ui.screen.ScannerMapScreen(
                viewModel = viewModel,
                uiState = uiState,
                isDark = isDark,
                onClose = { isScannerMap = false }
            )
        } else if (navState.manage) {
            com.shiraka.locatiobprovid.ui.screen.ManageDataScreen(
                viewModel = viewModel,
                uiState = uiState,
                isDark = isDark,
                onClose = { viewModel.toggleManageDataScreen(false) }
            )
        } else if (navState.settings) {
            com.shiraka.locatiobprovid.ui.screen.SettingsScreen(
                viewModel = viewModel,
                uiState = uiState,
                onClose = { isSettingsScreen = false }
            )
        } else {
            when {
                uiState.isInitializing -> InitializingScreen(isDark)
                !uiState.isLanguageSet -> LanguageSelectionScreen(viewModel)
                !uiState.hasRootAccess -> BlockingScreen(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.root_required),
                    message = stringResource(R.string.root_message),
                    isDark = isDark
                )
                !uiState.isLSPosedActive -> BlockingScreen(
                    icon = Icons.Rounded.Extension,
                    title = stringResource(R.string.lsposed_not_active),
                    message = stringResource(R.string.lsposed_message),
                    isDark = isDark
                )
                else -> SpoofingScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    isDark = isDark,
                    onExpandMap = { isFullScreenMap = true },
                    onExpandScannerMap = { isScannerMap = true },
                    onExpandSettings = { isSettingsScreen = true }
                )
            }
        }
    }
}
