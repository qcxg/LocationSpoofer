package com.shiraka.locatiobprovid.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.shiraka.locatiobprovid.R
import com.shiraka.locatiobprovid.data.model.AppState
import com.shiraka.locatiobprovid.data.model.JitterSpeed
import com.shiraka.locatiobprovid.data.model.SearchMode
import com.shiraka.locatiobprovid.data.model.SavedLocation
import com.shiraka.locatiobprovid.data.model.WifiLoadStatus
import com.shiraka.locatiobprovid.ui.components.AppMapView
import com.shiraka.locatiobprovid.ui.components.AppMapController
import com.shiraka.locatiobprovid.data.model.AppMapType
import com.shiraka.locatiobprovid.ui.components.MapTypeDialog
import androidx.compose.material.icons.rounded.Layers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.shiraka.locatiobprovid.ui.theme.AccentBlue
import com.shiraka.locatiobprovid.ui.theme.AccentGreen
import com.shiraka.locatiobprovid.ui.theme.AccentOrange
import kotlin.math.roundToInt
import com.shiraka.locatiobprovid.ui.theme.AppColors
import com.shiraka.locatiobprovid.viewmodel.MainViewModel

data class AppPoiItem(val title: String, val snippet: String, val lat: Double, val lng: Double)

data class RecommendedApp(val nameRes: Int, val packageName: String, val icon: ImageVector)

val RECOMMENDED_APPS = listOf(
    RecommendedApp(R.string.app_wechat, "com.tencent.mm", Icons.AutoMirrored.Outlined.Chat),
    RecommendedApp(R.string.app_chaoxing, "com.chaoxing.mobile", Icons.Outlined.School),
    RecommendedApp(R.string.app_tencentmap, "com.tencent.map", Icons.Outlined.Map),
    RecommendedApp(R.string.app_meituan, "com.sankuai.meituan", Icons.Outlined.LocalDining),
    RecommendedApp(R.string.app_dingtalk, "com.alibaba.android.rimet", Icons.Outlined.Work),
    RecommendedApp(R.string.app_google, "com.google.android.gms", Icons.Outlined.Android),
    RecommendedApp(R.string.app_settings, "com.android.settings", Icons.Outlined.Settings),
)

@Composable
fun SpoofingScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    onExpandMap: () -> Unit,
    onExpandScannerMap: () -> Unit,
    onExpandSettings: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showSavedLocations by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showAppCoordinateScreen by remember { mutableStateOf(false) }
    var showEnvironmentDialog by remember { mutableStateOf(false) }
    var showStartSpoofingDialog by remember { mutableStateOf(false) }
    var showMapTypeDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { 
            viewModel.exportEnvironmentData(it)
            Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { 
            viewModel.importEnvironmentData(it) {
                Toast.makeText(context, "导入合并成功", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    var showCustomCoordDialog by remember { mutableStateOf(false) }
    val topBarBg = MaterialTheme.colorScheme.background
    val isDomestic = viewModel.isDomesticEnvironment()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AppPoiItem>>(emptyList()) }
    var showSearchPanel by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var mapHeight by remember { mutableStateOf(280.dp) }
    val searchPopupOffsetY = with(density) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp).roundToPx()
    }

    fun closeSearchPanel() {
        showSearchPanel = false
        showSearchResults = false
        focusManager.clearFocus()
    }

    fun openSearchPanel(mode: SearchMode) {
        val isSameOpenPanel = showSearchPanel && uiState.searchMode == mode
        if (isSameOpenPanel) {
            closeSearchPanel()
            return
        }

        viewModel.setSearchMode(mode)
        showSearchPanel = true
        if (mode == SearchMode.LOCAL) {
            focusManager.clearFocus()
            scope.launch {
                val results = withContext(Dispatchers.IO) { viewModel.performLocalSearch() }
                searchResults = results
                showSearchResults = results.isNotEmpty()
            }
        } else {
            searchResults = emptyList()
            showSearchResults = false
        }
    }

    // 拦截返回键：如果有搜索结果，按返回键先关闭搜索结果
    BackHandler(enabled = showSearchResults || showSearchPanel) {
        closeSearchPanel()
    }

    // 请求当前位置（仅在坐标为空时），并确保本地采集数据已加载
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.fetchCurrentLocation(context)
        }
        // 强制在界面展示时再加载一次数据，防止因启动时序导致的数据未及时注入
        viewModel.loadManageData()
    }

    // 小地图实例，用于响应坐标更新
    var smallMapRef by remember { mutableStateOf<AppMapController?>(null) }
    var mapSettleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val latestUiState = rememberUpdatedState(uiState)
    DisposableEffect(Unit) {
        onDispose { mapSettleJob?.cancel() }
    }
    val lat = uiState.latitudeInput.toDoubleOrNull()
    val lng = uiState.longitudeInput.toDoubleOrNull()
    LaunchedEffect(lat, lng, smallMapRef) {
        if (lat != null && lng != null) {
            smallMapRef?.animateCamera(lat, lng)
        }
    }

    LaunchedEffect(smallMapRef, uiState.mapType) {
        smallMapRef?.setMapType(uiState.mapType)
    }

    LaunchedEffect(smallMapRef, uiState.manageDataList) {
        val map = smallMapRef ?: return@LaunchedEffect
        map.clear()
        val locations = uiState.manageDataList.map { it.location }
        com.shiraka.locatiobprovid.utils.MapCoverageHelper.drawCoverage(map, locations)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarBg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MyLocation, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { openSearchPanel(SearchMode.NETWORK) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.Search,
                    stringResource(R.string.network_search),
                    tint = if (showSearchPanel && uiState.searchMode == SearchMode.NETWORK) AccentBlue else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { openSearchPanel(SearchMode.LOCAL) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.Storage,
                    stringResource(R.string.local_collection),
                    tint = if (showSearchPanel && uiState.searchMode == SearchMode.LOCAL) AccentBlue else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showSavedLocations = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Bookmarks, stringResource(R.string.collection_list),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onExpandSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Settings, stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

        // 地图缩略图
        Box(modifier = Modifier.fillMaxWidth().height(mapHeight)) {
            AppMapView(mapEngine = uiState.mapEngine, isDomestic = isDomestic, modifier = Modifier.fillMaxSize()) { map ->
                smallMapRef = map
                map.disableUiControls()
                val initLat = uiState.latitudeInput.toDoubleOrNull() ?: 0.0
                val initLng = uiState.longitudeInput.toDoubleOrNull() ?: 0.0
                map.moveCamera(initLat, initLng, 15f)

                // 移动地图即选点；模拟中等待地图稳定后再刷新运行坐标。
                map.setOnCameraChangeListener { lat, lng ->
                    if (!latestUiState.value.isSpoofingActive) {
                        viewModel.confirmMapPoint(lat, lng)
                        return@setOnCameraChangeListener
                    }
                    mapSettleJob?.cancel()
                    mapSettleJob = scope.launch {
                        delay(1500L)
                        if (latestUiState.value.isSpoofingActive &&
                            viewModel.updateSpoofingPositionFromMap(lat, lng)
                        ) {
                            Toast.makeText(context, context.getString(R.string.location_updated), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 十字准星（始终显示在中间）
            Icon(
                Icons.Rounded.AddLocationAlt, null,
                tint = AccentBlue.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(32.dp).padding(bottom = 16.dp) // 准星底部对齐中心
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .clickable { onExpandMap() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Fullscreen, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.fullscreen_selection), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
                }
            }
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 72.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .clickable {
                        showMapTypeDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 24.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .clickable {
                        viewModel.fetchCurrentLocation(context) { _, _ -> }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.MyLocation, 
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            mapHeight = (mapHeight + with(density) { dragAmount.toDp() }).coerceIn(180.dp, 400.dp)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f))
                )
            }
        }

        // 滚动内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (uiState.isSpoofingActive) {
                WifiStatusCard(
                    uiState = uiState,
                    onClick = { showEnvironmentDialog = true }
                )
                Spacer(Modifier.height(12.dp))
            }

            CoordinateInputCard(
                uiState = uiState,
                isDark = isDark,
                onSaveClick = { showSaveDialog = true },
                onCustomClick = { showCustomCoordDialog = true }
            )
            Spacer(Modifier.height(12.dp))

            ActionButtons(viewModel, uiState, onExpandMap, onStartFixedSpoofing = { showStartSpoofingDialog = true })
            Spacer(Modifier.height(16.dp))

            // 已保存的位置列表（显示在操作按钮下方）
            if (uiState.savedLocations.isNotEmpty()) {
                SectionHeader(Icons.Outlined.Bookmarks, stringResource(R.string.collection_list), isDark)
                Spacer(Modifier.height(8.dp))
                SavedLocationsCard(
                    savedLocations = uiState.savedLocations,
                    onSelect = { loc ->
                        viewModel.loadSavedLocation(loc)
                    },
                    onDelete = { loc -> viewModel.removeSavedLocation(loc) }
                )
                Spacer(Modifier.height(16.dp))
            }


            SectionHeader(Icons.Rounded.Extension, stringResource(R.string.custom_coordinate_algo), isDark)
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark)),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.clickable { showAppCoordinateScreen = true }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            .background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Extension, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.config_app_coordinate), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.config_app_coordinate_desc), color = AppColors.textSecondary(isDark), fontSize = 11.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = AppColors.textSecondary(isDark), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(16.dp))


            SectionHeader(Icons.Rounded.Radar, stringResource(R.string.spatial_env_collection), isDark)
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark)),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.clickable { 
                    onExpandScannerMap()
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            .background(AccentGreen.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Map, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.env_map_scan), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val statusText = if (uiState.isContinuousScanning) stringResource(R.string.scanning_reference_points, uiState.environmentRecordCount) else stringResource(R.string.view_heatmap_start_scan)
                        Text(statusText, color = AppColors.textSecondary(isDark), fontSize = 11.sp)
                    }
                    if (uiState.isContinuousScanning) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AccentGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = AppColors.textSecondary(isDark), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark)),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.clickable { 
                    viewModel.toggleManageDataScreen(true)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.title_manage_data), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.manage_collected_data_desc), color = AppColors.textSecondary(isDark), fontSize = 11.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = AppColors.textSecondary(isDark), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            .background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ImportExport, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.env_data_sharing), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.env_data_sharing_desc), color = AppColors.textSecondary(isDark), fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Text(stringResource(R.string.import_data), color = AccentBlue)
                        }
                        TextButton(onClick = { exportLauncher.launch("environment_data.json") }) {
                            Text(stringResource(R.string.export_data), color = AccentBlue)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showSearchPanel) {
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, searchPopupOffsetY),
            onDismissRequest = { closeSearchPanel() },
            properties = PopupProperties(focusable = true, clippingEnabled = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (uiState.searchMode == SearchMode.NETWORK) {
                        HomeSearchBar(
                            query = searchQuery,
                            searchMode = uiState.searchMode,
                            onQueryChange = { searchQuery = it },
                            onSearch = {
                                focusManager.clearFocus()
                                if (searchQuery.isNotBlank()) {
                                    performPoiSearch(context, uiState.mapEngine, searchQuery, isDomestic) { results ->
                                        searchResults = results
                                        showSearchResults = results.isNotEmpty()
                                    }
                                }
                            }
                        )
                    } else {
                        LocalSearchResultList(
                            searchResults = searchResults,
                            onSelect = { poi ->
                                val pLat = poi.lat
                                val pLng = poi.lng
                                viewModel.updateLatitude(String.format("%.6f", pLat))
                                viewModel.updateLongitude(String.format("%.6f", pLng))
                                smallMapRef?.animateCamera(pLat, pLng, 16f)
                                searchQuery = poi.title
                                closeSearchPanel()
                            }
                        )
                    }
                }

                if (uiState.searchMode == SearchMode.NETWORK) {
                    AnimatedVisibility(visible = showSearchResults && searchResults.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        ) {
                            LocalSearchResultList(
                                searchResults = searchResults,
                                onSelect = { poi ->
                                    val pLat = poi.lat
                                    val pLng = poi.lng
                                    viewModel.updateLatitude(String.format("%.6f", pLat))
                                    viewModel.updateLongitude(String.format("%.6f", pLng))
                                    smallMapRef?.animateCamera(pLat, pLng, 16f)
                                    searchQuery = poi.title
                                    closeSearchPanel()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveNameDialog(
            title = stringResource(R.string.save_current_location),
            onConfirm = { name ->
                viewModel.saveCurrentLocation(name)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    if (showSavedLocations) {
        SavedLocationsDialog(
            savedLocations = uiState.savedLocations,
            onDismiss = { showSavedLocations = false },
            onSelect = { loc ->
                viewModel.loadSavedLocation(loc)
                showSavedLocations = false
            },
            onDelete = { loc -> viewModel.removeSavedLocation(loc) }
        )
    }

    if (showAppCoordinateScreen) {
        LocalizedDialog(
            onDismissRequest = { showAppCoordinateScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AppCoordinateScreen(
                viewModel = viewModel,
                uiState = uiState,
                onBack = { showAppCoordinateScreen = false }
            )
        }
    }


    if (showStartSpoofingDialog) {
        StartSpoofingDialog(
            uiState = uiState,
            isDark = isDark,
            onDismiss = { showStartSpoofingDialog = false },
            onConfirm = {
                viewModel.startSpoofing()
                showStartSpoofingDialog = false
            },
            onToggleWifi = { viewModel.toggleMockWifi() },
            onToggleCell = { viewModel.toggleMockCell() },
            onToggleBluetooth = { viewModel.toggleMockBluetooth() },
            onJitterOptionsConfirm = { enabled, radius, speed ->
                viewModel.setJitterOptions(enabled, radius, speed)
            },
            onAltitudeChange = { viewModel.setAltitude(it) },
            onSatelliteCountChange = { viewModel.setSatelliteCount(it) }
        )
    }

    if (showCustomCoordDialog) {
        CustomCoordinateDialog(
            initialLat = uiState.latitudeInput,
            initialLng = uiState.longitudeInput,
            isDark = isDark,
            onDismiss = { showCustomCoordDialog = false },
            onConfirm = { coordLat, coordLng ->
                viewModel.updateLatitude(coordLat)
                viewModel.updateLongitude(coordLng)
                showCustomCoordDialog = false
            }
        )
    }

    if (showEnvironmentDialog) {
        var environmentDataTab by remember(showEnvironmentDialog) { mutableStateOf(EnvironmentDataTab.WIFI) }
        var expandedWifiIndex by remember(showEnvironmentDialog) { mutableStateOf<Int?>(null) }
        val environmentListState = rememberLazyListState()
        val showRfDiagnostics by remember {
            derivedStateOf {
                environmentListState.firstVisibleItemIndex == 0 &&
                    environmentListState.firstVisibleItemScrollOffset < 24
            }
        }
        LaunchedEffect(environmentDataTab) {
            environmentListState.scrollToItem(0)
            expandedWifiIndex = null
        }
        LocalizedDialog(onDismissRequest = { showEnvironmentDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.local_env_data), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(16.dp))
                    
                    if (uiState.wifiLoadStatus == com.shiraka.locatiobprovid.data.model.WifiLoadStatus.LOADING) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue)
                        }
                    } else {
                        val wifiSummary = remember(uiState.collectedWifiJson) { parseWifiPayload(uiState.collectedWifiJson) }
                        val connectedWifi = wifiSummary.connectedWifi
                        val nearbyArray = wifiSummary.nearbyWifi
                        val cellArray = try { org.json.JSONArray(uiState.collectedCellJson) } catch (e: Exception) { org.json.JSONArray() }

                        AnimatedVisibility(visible = showRfDiagnostics) {
                            Column {
                                RfChainDiagnosticsPanel(
                                    uiState = uiState,
                                    wifiSummary = wifiSummary,
                                    cellCount = cellArray.length()
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EnvironmentTabButton(
                                selected = environmentDataTab == EnvironmentDataTab.WIFI,
                                text = stringResource(R.string.wifi_fingerprint_count, wifiSummary.totalCount),
                                icon = Icons.Outlined.Wifi,
                                color = AccentBlue,
                                modifier = Modifier.weight(1f),
                                onClick = { environmentDataTab = EnvironmentDataTab.WIFI }
                            )
                            EnvironmentTabButton(
                                selected = environmentDataTab == EnvironmentDataTab.CELL,
                                text = stringResource(R.string.cell_simulation_count, cellArray.length()),
                                icon = Icons.Outlined.CellTower,
                                color = AccentOrange,
                                modifier = Modifier.weight(1f),
                                onClick = { environmentDataTab = EnvironmentDataTab.CELL }
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        val connectedBssid = connectedWifi?.optString("bssid", "").orEmpty()
                        val wifiItems = remember(uiState.collectedWifiJson) {
                            val seen = linkedSetOf<String>()
                            val list = mutableListOf<org.json.JSONObject>()
                            for (i in 0 until nearbyArray.length()) {
                                nearbyArray.optJSONObject(i)?.let { obj ->
                                    val bssid = obj.optString("bssid", "")
                                    if (bssid.isBlank() || seen.add(bssid.lowercase())) {
                                        list.add(obj)
                                    }
                                }
                            }
                            if (connectedWifi != null && connectedBssid.isNotBlank() && seen.add(connectedBssid.lowercase())) {
                                list.add(connectedWifi)
                            }
                            list
                        }

                        LazyColumn(
                            state = environmentListState,
                            modifier = Modifier.weight(1f)
                        ) {
                            when (environmentDataTab) {
                                EnvironmentDataTab.WIFI -> {
                                    if (wifiItems.isEmpty()) {
                                        item {
                                            Text(stringResource(R.string.no_data_collected), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                        }
                                    }
                                    for (i in wifiItems.indices) {
                                        val obj = wifiItems[i]
                                        val bssid = obj.optString("bssid", "")
                                        item {
                                            WifiInfoListRow(
                                                wifi = obj,
                                                selected = bssid.isNotBlank() && bssid.equals(connectedBssid, ignoreCase = true),
                                                expanded = expandedWifiIndex == i,
                                                onSelect = {
                                                    if (bssid.isNotBlank()) viewModel.selectConnectedWifi(bssid)
                                                },
                                                onToggleExpanded = { expandedWifiIndex = if (expandedWifiIndex == i) null else i }
                                            )
                                        }
                                    }
                                }
                                EnvironmentDataTab.CELL -> {
                                    if (cellArray.length() == 0) {
                                        item {
                                            Text(stringResource(R.string.no_cell_data), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                        }
                                    }
                                    for (i in 0 until cellArray.length()) {
                                        val obj = cellArray.optJSONObject(i)
                                        if (obj != null) {
                                            item {
                                                CellInfoCard(obj)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEnvironmentDialog = false }) {
                            Text(stringResource(R.string.confirm), color = AccentBlue)
                        }
                    }
                }
            }
        }
    }

    if (showMapTypeDialog) {
        MapTypeDialog(
            currentMapType = uiState.mapType,
            onMapTypeSelected = { viewModel.setMapType(it) },
            onDismiss = { showMapTypeDialog = false }
        )
    }
}


// Wi-Fi 状态卡片

private data class StatusStyle(
    val bgColor: Color, val tint: Color, val text: String, val icon: ImageVector
)

private enum class EnvironmentDataTab {
    WIFI,
    CELL
}

private enum class RfChainState {
    READY,
    BLOCK_ONLY,
    OFF,
    LOADING
}

private data class WifiPayloadSummary(
    val connectedWifi: org.json.JSONObject?,
    val nearbyWifi: org.json.JSONArray
) {
    val totalCount: Int
        get() {
            val seen = linkedSetOf<String>()
            var count = 0
            for (i in 0 until nearbyWifi.length()) {
                val bssid = nearbyWifi.optJSONObject(i)?.optString("bssid", "").orEmpty()
                if (bssid.isBlank() || seen.add(bssid.lowercase())) count++
            }
            val connectedBssid = connectedWifi?.optString("bssid", "").orEmpty()
            if (connectedWifi != null && connectedBssid.isNotBlank() && seen.add(connectedBssid.lowercase())) {
                count++
            }
            return count
        }
}

private fun parseWifiPayload(json: String): WifiPayloadSummary {
    return try {
        val obj = org.json.JSONObject(json)
        val connected = if (obj.optBoolean("isConnected", false) && !obj.isNull("connectedWifi")) {
            obj.optJSONObject("connectedWifi")
        } else {
            null
        }
        WifiPayloadSummary(
            connectedWifi = connected,
            nearbyWifi = obj.optJSONArray("nearbyWifi") ?: org.json.JSONArray()
        )
    } catch (_: Exception) {
        WifiPayloadSummary(null, org.json.JSONArray())
    }
}

@Composable
private fun RfChainDiagnosticsPanel(
    uiState: AppState,
    wifiSummary: WifiPayloadSummary,
    cellCount: Int
) {
    val wifiState = when {
        !uiState.mockWifi -> RfChainState.OFF
        uiState.wifiLoadStatus == WifiLoadStatus.LOADING -> RfChainState.LOADING
        wifiSummary.totalCount > 0 -> RfChainState.READY
        else -> RfChainState.BLOCK_ONLY
    }
    val cellState = when {
        !uiState.mockCell -> RfChainState.OFF
        cellCount > 0 -> RfChainState.READY
        else -> RfChainState.BLOCK_ONLY
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SettingsInputAntenna, null, tint = AccentBlue, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.rf_chain_diagnostics),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (uiState.isSpoofingActive) stringResource(R.string.rf_runtime_active) else stringResource(R.string.rf_runtime_standby),
                color = if (uiState.isSpoofingActive) AccentGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(10.dp))
        RfChainRow(
            icon = Icons.Outlined.Wifi,
            title = "Wi-Fi",
            state = wifiState,
            countText = stringResource(R.string.rf_wifi_payload, wifiSummary.nearbyWifi.length(), if (wifiSummary.connectedWifi != null) 1 else 0),
            readyText = stringResource(R.string.rf_wifi_ready_path),
            blockText = stringResource(R.string.rf_wifi_block_path),
            offText = stringResource(R.string.rf_wifi_off_path)
        )
        Spacer(Modifier.height(8.dp))
        RfChainRow(
            icon = Icons.Outlined.CellTower,
            title = stringResource(R.string.rf_cell_title),
            state = cellState,
            countText = stringResource(R.string.rf_cell_payload, cellCount),
            readyText = stringResource(R.string.rf_cell_ready_path),
            blockText = stringResource(R.string.rf_cell_block_path),
            offText = stringResource(R.string.rf_cell_off_path)
        )
    }
}

@Composable
private fun RfChainRow(
    icon: ImageVector,
    title: String,
    state: RfChainState,
    countText: String,
    readyText: String,
    blockText: String,
    offText: String
) {
    val tint = when (state) {
        RfChainState.READY -> AccentGreen
        RfChainState.BLOCK_ONLY -> AccentOrange
        RfChainState.OFF -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        RfChainState.LOADING -> AccentBlue
    }
    val stateText = when (state) {
        RfChainState.READY -> stringResource(R.string.rf_state_ready)
        RfChainState.BLOCK_ONLY -> stringResource(R.string.rf_state_block_only)
        RfChainState.OFF -> stringResource(R.string.rf_state_off)
        RfChainState.LOADING -> stringResource(R.string.rf_state_loading)
    }
    val detail = when (state) {
        RfChainState.READY -> readyText
        RfChainState.BLOCK_ONLY -> blockText
        RfChainState.OFF -> offText
        RfChainState.LOADING -> stringResource(R.string.rf_loading_path)
    }

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(8.dp))
                Text(stateText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
                Spacer(Modifier.weight(1f))
                Text(countText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f))
            }
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f), lineHeight = 13.sp)
        }
    }
}

@Composable
private fun EnvironmentTabButton(
    selected: Boolean,
    text: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) color else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) color else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
        )
    }
}

@Composable
fun WifiStatusCard(uiState: AppState, onClick: () -> Unit) {
    val cellCount = remember(uiState.collectedCellJson) {
        try { org.json.JSONArray(uiState.collectedCellJson).length() } catch (e: Exception) { 0 }
    }
    val hasCellSimulation = uiState.mockCell && cellCount > 0
    val hasWifiSimulation = uiState.mockWifi && uiState.wifiLoadStatus == WifiLoadStatus.DONE && uiState.wifiApCount > 0

    val style = when {
        uiState.wifiLoadStatus == WifiLoadStatus.LOADING -> StatusStyle(
            AccentOrange.copy(alpha = 0.12f), AccentOrange,
            stringResource(R.string.fetching_wifi), Icons.Outlined.CloudDownload
        )
        hasWifiSimulation || hasCellSimulation -> {
            val parts = mutableListOf<String>()
            if (hasWifiSimulation) {
                parts.add(stringResource(R.string.wifi_fingerprint_count, uiState.wifiApCount))
            }
            if (hasCellSimulation) {
                parts.add(stringResource(R.string.cell_simulation_count, cellCount))
            } else if (uiState.mockCell) {
                parts.add(stringResource(R.string.cell_enabled_no_data))
            }
            StatusStyle(
                AccentGreen.copy(alpha = 0.12f), AccentGreen,
                parts.joinToString(" · "), Icons.Outlined.Wifi
            )
        }
        else -> StatusStyle(
            AccentBlue.copy(alpha = 0.12f), AccentBlue,
            stringResource(R.string.gps_taken_over), Icons.Outlined.GpsFixed
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(style.bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.wifiLoadStatus == WifiLoadStatus.LOADING) {
            CircularProgressIndicator(color = style.tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(style.icon, null, tint = style.tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(style.text, color = style.tint, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = style.tint.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WifiInfoListRow(
    wifi: org.json.JSONObject,
    selected: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentBlue.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(
                onClick = onToggleExpanded,
                onLongClick = onSelect
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Wifi, null, tint = if (selected) AccentGreen else AccentBlue, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val ssid = wifi.optString("ssid", "Unknown")
                Text(
                    text = ssid,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${wifi.optString("bssid", "Unknown")} · ${wifi.optInt("level", 0)} dBm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = AccentGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpanded)
                    .padding(5.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            StatusDetailRow("Vendor", wifi.optString("vendor", "Unknown"))
            StatusDetailRow("Channel", "${wifi.optInt("channel", 0)} (${wifi.optInt("frequency", 0)} MHz)")
            StatusDetailRow("Capabilities", wifi.optString("capabilities", ""))
            if (wifi.has("linkSpeed")) {
                StatusDetailRow("Link", "${wifi.optInt("linkSpeed", 0)} Mbps")
            }
            if (wifi.has("macAddress")) {
                StatusDetailRow("Client MAC", wifi.optString("macAddress", "Unknown"))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CellInfoCard(cell: org.json.JSONObject) {
    val type = cell.optString("type", cell.optString("radio", "Unknown"))
    val dbm = cell.optInt("dbm", 0)
    val registered = cell.optBoolean("isRegistered", false)
    val areaValue = when (type.uppercase()) {
        "GSM", "WCDMA" -> cell.optInt("lac", cell.optInt("tac", 0))
        else -> cell.optInt("tac", cell.optInt("lac", 0))
    }
    val cellValue = when (type.uppercase()) {
        "NR" -> cell.optLong("nci", 0L).takeIf { it > 0L }?.toString()
        "GSM", "WCDMA" -> cell.optInt("cid", cell.optInt("ci", 0)).takeIf { it > 0 }?.toString()
        else -> cell.optInt("ci", cell.optInt("cid", 0)).takeIf { it > 0 }?.toString()
    } ?: "0"
    val secondaryCode = when (type.uppercase()) {
        "WCDMA" -> "PSC" to cell.optInt("psc", 0)
        else -> "PCI" to cell.optInt("pci", 0)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentOrange.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CellTower, null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(type, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                }
                Text("$dbm dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            }
            Spacer(Modifier.height(8.dp))
            StatusDetailRow("MCC/MNC", "${cell.optInt("mcc", 0)}/${cell.optInt("mnc", 0)}")
            StatusDetailRow(if (type.uppercase() in listOf("GSM", "WCDMA")) "LAC" else "TAC", areaValue.toString())
            StatusDetailRow(if (type.uppercase() == "NR") "NCI" else if (type.uppercase() in listOf("GSM", "WCDMA")) "CID" else "CI", cellValue)
            if (secondaryCode.second > 0) {
                StatusDetailRow(secondaryCode.first, secondaryCode.second.toString())
            }
            StatusDetailRow("Registered", if (registered) "true" else "false")
        }
    }
}

@Composable
private fun StatusDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.56f))
        Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f))
    }
}

// 坐标输入卡片

@Composable
fun CoordinateInputCard(
    uiState: AppState,
    isDark: Boolean,
    onSaveClick: () -> Unit,
    onCustomClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(Icons.Outlined.PinDrop, stringResource(R.string.target_coordinates), isDark)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCustomClick) {
                    Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.custom))
                }
                TextButton(onClick = onSaveClick) {
                    Icon(Icons.Rounded.StarBorder, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.save))
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stringResource(R.string.longitude)}: ${uiState.longitudeInput.ifEmpty { "0.0" }}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stringResource(R.string.latitude)}: ${uiState.latitudeInput.ifEmpty { "0.0" }}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (uiState.showCoordinateError) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.invalid_coordinates), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun coordinateFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
    cursorColor = AccentBlue
)

// 操作按钮

@Composable
fun ActionButtons(viewModel: MainViewModel, uiState: AppState, onOpenMap: () -> Unit, onStartFixedSpoofing: () -> Unit) {
    if (uiState.isSpoofingActive) {
        val stopColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.error,
            animationSpec = tween(300), label = "stop_color"
        )
        Button(
            onClick = { viewModel.stopSpoofing() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = stopColor)
        ) {
            Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.stop_simulation), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStartFixedSpoofing,
                enabled = !uiState.isSavingConfig,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                if (uiState.isSavingConfig) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Starting...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Rounded.MyLocation, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.fixed_simulation), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = { viewModel.enterRoutePlanning(); onOpenMap() },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Icon(Icons.Rounded.Route, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.route_planning), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// 章节标题

@Composable
fun SectionHeader(icon: ImageVector, title: String, isDark: Boolean) {
    val textSecondary = AppColors.textSecondary(isDark)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            color = textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
}

// 已保存位置对话框

@Composable
fun SavedLocationsDialog(
    savedLocations: List<SavedLocation>,
    onDismiss: () -> Unit,
    onSelect: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit
) {
    LocalizedDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(stringResource(R.string.saved_locations), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                if (savedLocations.isEmpty()) {
                    Text(stringResource(R.string.no_saved_locations), color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(savedLocations) { loc ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(loc) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Place, null, tint = AccentBlue)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(loc.name, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                                    Text("${loc.lat}, ${loc.lng}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(onClick = { onDelete(loc) }) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

// 首页搜索栏

@Composable
fun LocalSearchResultList(
    searchResults: List<AppPoiItem>,
    onSelect: (AppPoiItem) -> Unit
) {
    if (searchResults.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.no_data_collected),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
        items(searchResults.take(15)) { poi ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onSelect(poi) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Place, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(poi.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    Text(poi.snippet, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun HomeSearchBar(
    query: String,
    searchMode: SearchMode,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .shadow(4.dp, RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            val hint = if (searchMode == SearchMode.LOCAL) {
                                stringResource(R.string.local_collection)
                            } else {
                                stringResource(R.string.search_location_hint)
                            }
                            Text(hint, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        )
        Spacer(Modifier.width(10.dp))
        FilledIconButton(
            onClick = onSearch,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentBlue)
        ) {
            Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
}

// 首页已保存位置卡片（内嵌列表，非弹窗）

@Composable
fun SavedLocationsCard(
    savedLocations: List<SavedLocation>,
    onSelect: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            savedLocations.forEachIndexed { index, loc ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelect(loc) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Place, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(loc.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                        Text("${loc.lat}, ${loc.lng}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { onDelete(loc) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
                if (index < savedLocations.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}

// 高德 POI 搜索

private var cachedPlacesClient: com.google.android.libraries.places.api.net.PlacesClient? = null

@Suppress("UNUSED_PARAMETER")
fun performPoiSearch(
    context: android.content.Context,
    mapEngine: com.shiraka.locatiobprovid.data.model.MapEngine,
    keyword: String,
    isDomestic: Boolean,
    onResult: (List<AppPoiItem>) -> Unit
) {
    if (!com.google.android.libraries.places.api.Places.isInitialized()) {
        android.widget.Toast.makeText(context, "Google Places 未初始化，請先在設置中配置 Google Map API Key！", android.widget.Toast.LENGTH_LONG).show()
        onResult(emptyList())
        return
    }
    try {
        val placesClient = cachedPlacesClient ?: com.google.android.libraries.places.api.Places.createClient(context.applicationContext).also {
            cachedPlacesClient = it
        }
        val sessionToken = com.google.android.libraries.places.api.model.AutocompleteSessionToken.newInstance()
        val worldBounds = com.google.android.libraries.places.api.model.RectangularBounds.newInstance(
            com.google.android.gms.maps.model.LatLng(-90.0, -180.0),
            com.google.android.gms.maps.model.LatLng(90.0, 180.0)
        )
        val autocompleteRequest = com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest.builder()
            .setQuery(keyword)
            .setLocationBias(worldBounds)
            .setSessionToken(sessionToken)
            .build()

        placesClient.findAutocompletePredictions(autocompleteRequest)
            .addOnSuccessListener { autocompleteResponse ->
                val predictions = autocompleteResponse.autocompletePredictions
                android.util.Log.d("SpoofingScreen", "Autocomplete got ${predictions.size} predictions")
                if (predictions.isEmpty()) {
                    android.widget.Toast.makeText(context, "No predictions found for: $keyword", android.widget.Toast.LENGTH_SHORT).show()
                    onResult(emptyList())
                    return@addOnSuccessListener
                }
                val fetchFields = listOf(
                    com.google.android.libraries.places.api.model.Place.Field.ID,
                    com.google.android.libraries.places.api.model.Place.Field.NAME,
                    com.google.android.libraries.places.api.model.Place.Field.LAT_LNG,
                    com.google.android.libraries.places.api.model.Place.Field.ADDRESS
                )
                val resultList = mutableListOf<AppPoiItem>()
                val topPredictions = predictions.take(5)
                var completedCount = 0
                topPredictions.forEach { prediction ->
                    val fetchRequest = com.google.android.libraries.places.api.net.FetchPlaceRequest.newInstance(prediction.placeId, fetchFields)
                    placesClient.fetchPlace(fetchRequest)
                        .addOnSuccessListener { fetchResponse ->
                            val place = fetchResponse.place
                            val latLng = place.latLng
                            if (latLng != null) {
                                resultList.add(AppPoiItem(
                                    title = place.name ?: prediction.getPrimaryText(null).toString(),
                                    snippet = place.address ?: prediction.getSecondaryText(null).toString(),
                                    lat = latLng.latitude,
                                    lng = latLng.longitude
                                ))
                            }
                        }
                        .addOnCompleteListener {
                            completedCount++
                            if (completedCount == topPredictions.size) {
                                android.widget.Toast.makeText(context, "Search Success: ${resultList.size} results", android.widget.Toast.LENGTH_SHORT).show()
                                onResult(resultList)
                            }
                        }
                }
            }
            .addOnFailureListener { exception ->
                android.widget.Toast.makeText(context, "Search Error: ${exception.message}", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("SpoofingScreen", "Autocomplete failed: ${exception.message}", exception)
                onResult(emptyList())
            }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Search Catch Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        android.util.Log.e("SpoofingScreen", "Places API exception: ${e.message}", e)
        onResult(emptyList())
    }
}


@Composable
fun CustomCoordinateDialog(
    initialLat: String,
    initialLng: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var lat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLat) }
    var lng by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialLng) }
    val textSecondary = AppColors.textSecondary(isDark)

    val currentContext = androidx.compose.ui.platform.LocalContext.current
    val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        title = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides currentContext, androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.custom_coordinate_title), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        },
        text = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides currentContext, androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration) {
                Column {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.custom_coord_desc), color = textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = lng,
                        onValueChange = { lng = it },
                        label = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.longitude)) },
                        placeholder = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.coordinate_hint), color = textSecondary) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.East, null, tint = textSecondary, modifier = Modifier.size(18.dp)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = coordinateFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.latitude)) },
                        placeholder = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.coordinate_hint), color = textSecondary) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.North, null, tint = textSecondary, modifier = Modifier.size(18.dp)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = coordinateFieldColors()
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides currentContext, androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration) {
                androidx.compose.material3.TextButton(onClick = { onConfirm(lat, lng) }) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.confirm), color = AccentBlue)
                }
            }
        },
        dismissButton = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides currentContext, androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.shiraka.locatiobprovid.R.string.cancel), color = textSecondary)
                }
            }
        }
    )
}

@Composable
fun LocalizedDialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit
) {
    val currentContext = androidx.compose.ui.platform.LocalContext.current
    val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides currentContext,
            androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartSpoofingDialog(
    uiState: AppState,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCell: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onJitterOptionsConfirm: (Boolean, Int, JitterSpeed) -> Unit,
    onAltitudeChange: (String) -> Unit,
    onSatelliteCountChange: (String) -> Unit
) {
    var pendingEnableJitter by remember(uiState.enableJitter) { mutableStateOf(uiState.enableJitter) }
    var pendingJitterRadius by remember(uiState.jitterRadiusMeters) { mutableStateOf(uiState.jitterRadiusMeters.coerceIn(1, 80).toFloat()) }
    var pendingJitterSpeed by remember(uiState.jitterSpeed) { mutableStateOf(uiState.jitterSpeed) }

    LocalizedDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.cardBackground(isDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.spoofing_options_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.spoofing_options_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(16.dp))

                if (uiState.canMockWifi || uiState.wigleToken.isNotBlank()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Wifi, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.mock_wifi_data), modifier = Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        Switch(checked = uiState.mockWifi, onCheckedChange = { onToggleWifi() })
                    }
                }
                
                if (uiState.canMockCell || uiState.opencellidToken.isNotBlank()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CellTower, null, tint = AccentOrange, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.mock_cell_data), modifier = Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        Switch(checked = uiState.mockCell, onCheckedChange = { onToggleCell() })
                    }
                }
                
                if (uiState.canMockBluetooth) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bluetooth, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.mock_bluetooth_data), modifier = Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        Switch(checked = uiState.mockBluetooth, onCheckedChange = { onToggleBluetooth() })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.enable_slight_jitter), modifier = Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                    Switch(checked = pendingEnableJitter, onCheckedChange = { pendingEnableJitter = it })
                }
                AnimatedVisibility(visible = pendingEnableJitter) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.jitter_radius),
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                            )
                            Text(
                                stringResource(R.string.jitter_radius_value, pendingJitterRadius.roundToInt()),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentBlue
                            )
                        }
                        Slider(
                            value = pendingJitterRadius,
                            onValueChange = { pendingJitterRadius = it.coerceIn(1f, 80f) },
                            valueRange = 1f..80f,
                            steps = 0,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentBlue,
                                activeTrackColor = AccentBlue
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("1m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
                            Text("80m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.jitter_speed),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                        )
                        Spacer(Modifier.height(6.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val speeds = JitterSpeed.values()
                            speeds.forEachIndexed { index, speed ->
                                SegmentedButton(
                                    selected = pendingJitterSpeed == speed,
                                    onClick = { pendingJitterSpeed = speed },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = speeds.size)
                                ) {
                                    Text(stringResource(speed.labelResId), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = uiState.altitudeInput,
                        onValueChange = onAltitudeChange,
                        label = { Text("海拔 (米)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            focusedLabelColor = AccentBlue
                        )
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = uiState.satelliteCountInput,
                        onValueChange = onSatelliteCountChange,
                        label = { Text("卫星数", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            focusedLabelColor = AccentBlue
                        )
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onJitterOptionsConfirm(
                                pendingEnableJitter,
                                pendingJitterRadius.roundToInt().coerceIn(1, 80),
                                pendingJitterSpeed
                            )
                            onConfirm()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(stringResource(R.string.start_simulation))
                    }
                }
            }
        }
    }
}

// Markdown 简易解析函数，渲染 Release Notes 内容
@Composable
fun parseMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        var isInCodeBlock = false
        
        lines.forEachIndexed { index, line ->
            val cleanLine = line.trim()
            
            // Check for code block boundary
            if (cleanLine.startsWith("```")) {
                isInCodeBlock = !isInCodeBlock
                return@forEachIndexed
            }
            
            if (isInCodeBlock) {
                withStyle(style = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    color = AccentBlue
                )) {
                    append("    ")
                    append(line)
                }
            } else {
                val headerMatch = Regex("""^(#{1,6})\s+(.*)""").matchEntire(cleanLine)
                if (headerMatch != null) {
                    val hashCount = headerMatch.groupValues[1].length
                    val rest = headerMatch.groupValues[2]
                    val fontSize = when (hashCount) {
                        1 -> 18.sp
                        2 -> 16.sp
                        3 -> 14.sp
                        4 -> 13.sp
                        else -> 12.sp
                    }
                    val fontWeight = FontWeight.Bold
                    val fontStyle = if (hashCount >= 6) FontStyle.Italic else FontStyle.Normal
                    
                    withStyle(style = SpanStyle(
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        fontSize = fontSize,
                        color = MaterialTheme.colorScheme.onBackground
                    )) {
                        parseInlineFormatting(rest)
                    }
                } else if (cleanLine.startsWith(">")) {
                    val rest = if (cleanLine.startsWith("> ")) cleanLine.substring(2) else cleanLine.substring(1)
                    withStyle(style = SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )) {
                        append("▎ ")
                        parseInlineFormatting(rest)
                    }
                } else if (cleanLine.startsWith("- ") || cleanLine.startsWith("* ") || cleanLine.startsWith("+ ")) {
                    append("  • ")
                    val rest = cleanLine.substring(2).trim()
                    parseInlineFormatting(rest)
                } else if (cleanLine.matches(Regex("""^\d+\.\s+.*"""))) {
                    val matchResult = Regex("""^(\d+\.\s+)(.*)""").find(cleanLine)
                    if (matchResult != null) {
                        val prefix = matchResult.groupValues[1]
                        val rest = matchResult.groupValues[2]
                        append("  $prefix")
                        parseInlineFormatting(rest)
                    } else {
                        parseInlineFormatting(cleanLine)
                    }
                } else {
                    parseInlineFormatting(cleanLine)
                }
            }
            
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

@Composable
private fun androidx.compose.ui.text.AnnotatedString.Builder.parseInlineFormatting(text: String) {
    var i = 0
    while (i < text.length) {
        var tokenType = ""
        var minIdx = Int.MAX_VALUE
        
        val bold1 = text.indexOf("**", i)
        val bold2 = text.indexOf("__", i)
        val italic1 = text.indexOf("*", i)
        val italic2 = text.indexOf("_", i)
        val code = text.indexOf("`", i)
        val strike = text.indexOf("~~", i)
        val link = text.indexOf("[", i)
        
        if (bold1 in i until minIdx) { minIdx = bold1; tokenType = "bold1" }
        if (bold2 in i until minIdx) { minIdx = bold2; tokenType = "bold2" }
        if (italic1 in i until minIdx && bold1 != italic1) { minIdx = italic1; tokenType = "italic1" }
        if (italic2 in i until minIdx && bold2 != italic2) { minIdx = italic2; tokenType = "italic2" }
        if (code in i until minIdx) { minIdx = code; tokenType = "code" }
        if (strike in i until minIdx) { minIdx = strike; tokenType = "strike" }
        if (link in i until minIdx) { minIdx = link; tokenType = "link" }
        
        if (minIdx == Int.MAX_VALUE) {
            append(text.substring(i))
            break
        }
        
        if (minIdx > i) {
            append(text.substring(i, minIdx))
        }
        
        i = minIdx
        var parsed = false
        
        when (tokenType) {
            "bold1", "bold2" -> {
                val delim = if (tokenType == "bold1") "**" else "__"
                val end = text.indexOf(delim, i + 2)
                if (end != -1) {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        parseInlineFormatting(text.substring(i + 2, end))
                    }
                    i = end + 2
                    parsed = true
                }
            }
            "italic1", "italic2" -> {
                val delim = if (tokenType == "italic1") "*" else "_"
                val end = text.indexOf(delim, i + 1)
                if (end != -1) {
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                        parseInlineFormatting(text.substring(i + 1, end))
                    }
                    i = end + 1
                    parsed = true
                }
            }
            "code" -> {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    withStyle(style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        color = AccentBlue
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    parsed = true
                }
            }
            "strike" -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(style = SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        parseInlineFormatting(text.substring(i + 2, end))
                    }
                    i = end + 2
                    parsed = true
                }
            }
            "link" -> {
                val closeBracket = text.indexOf("]", i + 1)
                if (closeBracket != -1) {
                    val openParen = closeBracket + 1
                    if (openParen < text.length && text[openParen] == '(') {
                        val closeParen = text.indexOf(")", openParen + 1)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val linkUrl = text.substring(openParen + 1, closeParen)
                            
                            withStyle(style = SpanStyle(
                                color = AccentBlue,
                                textDecoration = TextDecoration.Underline
                            )) {
                                pushStringAnnotation(tag = "URL", annotation = linkUrl)
                                parseInlineFormatting(linkText)
                                pop()
                            }
                            i = closeParen + 1
                            parsed = true
                        }
                    }
                }
            }
        }
        
        if (!parsed) {
            append(text[i])
            i++
        }
    }
}
