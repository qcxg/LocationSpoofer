package com.shiraka.locatiobprovid.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiraka.locatiobprovid.R
import com.shiraka.locatiobprovid.data.db.CompleteLocation
import com.shiraka.locatiobprovid.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shiraka.locatiobprovid.ui.components.AppMapView
import com.shiraka.locatiobprovid.ui.components.AppMapController
import com.shiraka.locatiobprovid.utils.MapCoverageHelper

private data class ManagedLocationGroup(
    val ids: List<Long>,
    val first: CompleteLocation,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val nearbyName: String,
    val wifiCount: Int,
    val cellCount: Int,
    val bluetoothCount: Int,
    val wifiSources: List<String>,
    val cellSources: List<String>,
    val bluetoothSources: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDataScreen(
    viewModel: MainViewModel,
    uiState: com.shiraka.locatiobprovid.data.model.AppState,
    isDark: Boolean,
    onClose: () -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    
    var editingItem by remember { mutableStateOf<CompleteLocation?>(null) }

    val dataList = uiState.manageDataList
    val coverageLocations by viewModel.coverageLocations.collectAsState()
    val latestCoverageLocations = rememberUpdatedState(coverageLocations)
    val groupedData = remember(dataList, uiState.nearbyPlaceNames) {
        groupManagedLocations(dataList, uiState.nearbyPlaceNames)
    }
    LaunchedEffect(dataList) {
        viewModel.refreshNearbyPlaceNames(dataList)
    }
    
    LaunchedEffect(mapController, uiState.mapType) {
        mapController?.setMapType(uiState.mapType)
    }

    LaunchedEffect(mapController, coverageLocations) {
        val controller = mapController ?: return@LaunchedEffect
        if (coverageLocations.isNotEmpty()) {
            val latest = coverageLocations.first()
            controller.moveCamera(latest.lat, latest.lng, 15f)
        }
        MapCoverageHelper.drawCoverage(controller, coverageLocations)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.selected_items, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.title_manage_data))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedIds.clear()
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(
                            if (isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            val allIds = groupedData.flatMap { it.ids }
                            if (selectedIds.size == allIds.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(allIds)
                            }
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.deleteManageData(selectedIds.toList())
                                isSelectionMode = false
                                selectedIds.clear()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Rounded.Checklist, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_all), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.shiraka.locatiobprovid.ui.theme.AppColors.topBarBackground(isDark),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        if (uiState.manageDataIsLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (dataList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_data_collected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Top Map
                Box(modifier = Modifier.fillMaxWidth().weight(0.4f)) {
                    AppMapView(
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { controller ->
                            mapController = controller
                            controller.disableUiControls()
                            controller.setOnCameraChangeListener { _, _ ->
                                MapCoverageHelper.drawCoverage(
                                    controller,
                                    latestCoverageLocations.value
                                )
                            }
                        }
                    )
                }
                
                // Bottom List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.6f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(groupedData, key = { it.ids.joinToString("-") }) { item ->
                        val isSelected = item.ids.all { selectedIds.contains(it) }
                        DataListItem(
                            group = item,
                            isDark = isDark,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onSelect = {
                                if (isSelected) selectedIds.removeAll(item.ids.toSet())
                                else selectedIds.addAll(item.ids.filterNot { selectedIds.contains(it) })
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.addAll(item.ids)
                                }
                            },
                            onClick = {
                                viewModel.updateLatitude(String.format(Locale.US, "%.6f", item.lat))
                                viewModel.updateLongitude(String.format(Locale.US, "%.6f", item.lng))
                                mapController?.animateCamera(item.lat, item.lng, 17f)
                            },
                            onDeleteSingle = {
                                viewModel.deleteManageData(item.ids)
                            },
                            onEdit = {
                                editingItem = item.first
                            }
                        )
                    }
                }
            }
        }
    }

    if (editingItem != null) {
        val editingLocation = editingItem!!.location
        val editingKey = placeKey(editingLocation.lat, editingLocation.lng)
        val initialPlaceName = editingLocation.placeName
            .takeIf { it.isNotBlank() && !isImportSourceText(it) && isReadablePlaceNameForUi(it) }
            ?: uiState.nearbyPlaceNames[editingKey].orEmpty()
        val initialRemark = editingLocation.remark
            .takeUnless { it.startsWith("经纬度") || it.startsWith("經緯度") }
            .orEmpty()
        var placeName by remember(editingItem, uiState.nearbyPlaceNames) { mutableStateOf(initialPlaceName) }
        var remark by remember(editingItem) { mutableStateOf(initialRemark) }
        
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(stringResource(R.string.edit_location_data)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        label = { Text(stringResource(R.string.place_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text(stringResource(R.string.remark_note)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateManageDataMetadata(editingItem!!.location.id, placeName, remark)
                    editingItem = null
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_all_data_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllManageData()
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DataListItem(
    group: ManagedLocationGroup,
    isDark: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDeleteSingle: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(group.timestamp) { dateFormat.format(Date(group.timestamp)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { 
                    if (isSelectionMode) onSelect() 
                    else onClick()
                },
                onLongClick = onLongClick
            ),
        color = if (isSelected) com.shiraka.locatiobprovid.ui.theme.AccentBlue.copy(alpha = 0.15f) else com.shiraka.locatiobprovid.ui.theme.AppColors.cardBackground(isDark),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Lat: ${String.format("%.5f", group.lat)}, Lng: ${String.format("%.5f", group.lng)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconTextRow(Icons.Rounded.Wifi, sourceText(group.wifiCount, group.wifiSources))
                    IconTextRow(Icons.Rounded.CellTower, sourceText(group.cellCount, group.cellSources))
                    if (group.bluetoothCount > 0) {
                        IconTextRow(Icons.Rounded.Bluetooth, sourceText(group.bluetoothCount, group.bluetoothSources))
                    }
                }
                
                if (group.nearbyName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "此地點位於 ${group.nearbyName} 附近",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            if (!isSelectionMode) {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.edit_location_data),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onDeleteSingle) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconTextRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

private fun groupManagedLocations(
    records: List<CompleteLocation>,
    nearbyNames: Map<String, String>
): List<ManagedLocationGroup> {
    return records
        .groupBy { placeKey(it.location.lat, it.location.lng) }
        .values
        .map { group ->
            val first = group.minByOrNull { it.location.timestamp } ?: group.first()
            val key = placeKey(first.location.lat, first.location.lng)
            val placeName = nearbyNames[key]
                ?: group.map { it.location.placeName }.firstOrNull { it.isNotBlank() && !isImportSourceText(it) && isReadablePlaceNameForUi(it) }
                ?: ""

            ManagedLocationGroup(
                ids = group.map { it.location.id },
                first = first,
                lat = first.location.lat,
                lng = first.location.lng,
                timestamp = group.maxOf { it.location.timestamp },
                nearbyName = placeName,
                wifiCount = group.sumOf { it.wifis.size },
                cellCount = group.sumOf { it.cells.size },
                bluetoothCount = group.sumOf { it.bluetooths.size },
                wifiSources = sourceLabelsForGroup(group, "wifi"),
                cellSources = sourceLabelsForGroup(group, "cell"),
                bluetoothSources = sourceLabelsForGroup(group, "bluetooth")
            )
        }
        .sortedByDescending { it.timestamp }
}

private fun sourceText(count: Int, sources: List<String>): String {
    if (count <= 0) return "0"
    return if (sources.isEmpty()) "$count" else "$count · ${sources.joinToString("/")}"
}

private fun placeKey(lat: Double, lng: Double): String {
    return String.format(Locale.US, "%.6f,%.6f", lat, lng)
}

private fun sourceLabelsForGroup(group: List<CompleteLocation>, kind: String): List<String> {
    val records = group.filter {
        when (kind) {
            "wifi" -> it.wifis.isNotEmpty()
            "cell" -> it.cells.isNotEmpty()
            "bluetooth" -> it.bluetooths.isNotEmpty()
            else -> false
        }
    }
    if (records.isEmpty()) return emptyList()

    val groupText = group.joinToString(" ") { "${it.location.placeName} ${it.location.remark}" }
    val labels = mutableListOf<String>()
    if (kind == "wifi" && groupText.contains("WiGLE", ignoreCase = true)) {
        labels += "由 WiGLE 導入"
    }
    if (kind == "cell" && groupText.contains("OpenCellID", ignoreCase = true)) {
        labels += "由 OpenCellID 導入"
    }
    if (labels.isEmpty() && isImportSourceText(groupText)) {
        labels += if (kind == "cell") "由 OpenCellID 導入" else "由 WiGLE 導入"
    }
    if (labels.isEmpty()) {
        labels += "本地采集"
    }
    return labels.distinct()
}

private fun isImportSourceText(text: String): Boolean {
    return text.contains("WiGLE", ignoreCase = true) ||
        text.contains("OpenCellID", ignoreCase = true) ||
        text.contains("导入") ||
        text.contains("導入") ||
        text.contains("Import", ignoreCase = true)
}

private fun isReadablePlaceNameForUi(text: String): Boolean {
    val name = text.trim()
    if (name.isBlank()) return false
    if (name.equals("Unnamed Road", ignoreCase = true)) return false
    if (name.contains("+")) return false
    return !name.matches(Regex("[0-9０-９\\-－ー丁目番地号之の\\s]+"))
}
