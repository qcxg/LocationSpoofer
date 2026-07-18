package com.shiraka.locatiobprovid.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiraka.locatiobprovid.R
import com.shiraka.locatiobprovid.data.model.AppState
import com.shiraka.locatiobprovid.data.model.MapEngine
import com.shiraka.locatiobprovid.ui.theme.AccentBlue
import com.shiraka.locatiobprovid.utils.ExternalApiTester
import com.shiraka.locatiobprovid.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var localGoogleApiKey by remember(uiState.googleApiKey) { mutableStateOf(uiState.googleApiKey) }
    var localWigleToken by remember(uiState.wigleToken) { mutableStateOf(uiState.wigleToken) }
    var localOpencellidToken by remember(uiState.opencellidToken) { mutableStateOf(uiState.opencellidToken) }
    val clipboardManager = LocalClipboardManager.current
    var showCleanupDialog by remember { mutableStateOf(false) }
    var isCleaningRuntime by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf<String?>(null) }
    var googleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var wigleApiTest by remember { mutableStateOf(ApiTestUiState()) }
    var openCellIdApiTest by remember { mutableStateOf(ApiTestUiState()) }
    val apiTestScope = rememberCoroutineScope()
    val savedLanguage = uiState.currentLanguage.ifBlank { viewModel.getSavedLanguage() }
    val selectedLanguageName = LANGUAGES.firstOrNull { it.code == savedLanguage }?.nativeName ?: savedLanguage.ifBlank { "System" }

    fun toggleSection(key: String) {
        expandedSection = if (expandedSection == key) null else key
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ExpandableSettingsRow(
                title = stringResource(R.string.select_language),
                summary = selectedLanguageName,
                expanded = expandedSection == "language",
                onClick = { toggleSection("language") }
            ) {
                LANGUAGES.forEach { lang ->
                    LanguageItem(
                        option = lang,
                        isSelected = viewModel.getSavedLanguage() == lang.code,
                        onClick = {
                            viewModel.selectLanguage(lang.code)
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.forLanguageTags(lang.code)
                            )
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            ExpandableSettingsRow(
                title = stringResource(R.string.app_identity),
                summary = context.packageName,
                expanded = expandedSection == "identity",
                onClick = { toggleSection("identity") }
            ) {
                CopyableReadOnlyField(
                    value = context.packageName,
                    label = stringResource(R.string.app_package_name),
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(context.packageName))
                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(Modifier.height(8.dp))
                CopyableReadOnlyField(
                    value = uiState.appSha1,
                    label = stringResource(R.string.app_sha1),
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(uiState.appSha1))
                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    }
                )
            }

            ExpandableSettingsRow(
                title = stringResource(R.string.home_display_settings),
                summary = if (uiState.showHomeCoordinateAlgorithm) {
                    stringResource(R.string.coordinate_algorithm_visible)
                } else {
                    stringResource(R.string.coordinate_algorithm_hidden)
                },
                expanded = expandedSection == "home_display",
                onClick = { toggleSection("home_display") }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.show_home_coordinate_algorithm),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.show_home_coordinate_algorithm_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.56f)
                        )
                    }
                    Switch(
                        checked = uiState.showHomeCoordinateAlgorithm,
                        onCheckedChange = { viewModel.setShowHomeCoordinateAlgorithm(it) }
                    )
                }
            }

            val configuredApiCount = listOf(localGoogleApiKey, localWigleToken, localOpencellidToken).count { it.isNotBlank() }
            ExpandableSettingsRow(
                title = stringResource(R.string.api_config),
                summary = stringResource(R.string.api_config_summary, configuredApiCount, 3),
                expanded = expandedSection == "apis",
                onClick = { toggleSection("apis") }
            ) {
                ApiCredentialField(
                    value = localGoogleApiKey,
                    onValueChange = {
                        localGoogleApiKey = it
                        googleApiTest = ApiTestUiState()
                    },
                    label = stringResource(R.string.custom_google_key),
                    placeholder = stringResource(R.string.custom_google_key_hint),
                    state = googleApiTest,
                    onTest = {
                        val keyBeingTested = localGoogleApiKey
                        googleApiTest = ApiTestUiState(isTesting = true)
                        apiTestScope.launch {
                            val result = ExternalApiTester.testGooglePlaces(
                                apiKey = keyBeingTested
                            )
                            googleApiTest = ApiTestUiState(result = result)
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                ApiCredentialField(
                    value = localWigleToken,
                    onValueChange = {
                        localWigleToken = it
                        wigleApiTest = ApiTestUiState()
                    },
                    label = stringResource(R.string.custom_wigle_token),
                    placeholder = stringResource(R.string.custom_wigle_token_hint),
                    state = wigleApiTest,
                    onTest = {
                        val tokenBeingTested = localWigleToken
                        wigleApiTest = ApiTestUiState(isTesting = true)
                        apiTestScope.launch {
                            wigleApiTest = ApiTestUiState(
                                result = ExternalApiTester.testWigle(tokenBeingTested)
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                ApiCredentialField(
                    value = localOpencellidToken,
                    onValueChange = {
                        localOpencellidToken = it
                        openCellIdApiTest = ApiTestUiState()
                    },
                    label = stringResource(R.string.custom_opencellid_token),
                    placeholder = stringResource(R.string.custom_opencellid_token_hint),
                    state = openCellIdApiTest,
                    onTest = {
                        val tokenBeingTested = localOpencellidToken
                        openCellIdApiTest = ApiTestUiState(isTesting = true)
                        apiTestScope.launch {
                            openCellIdApiTest = ApiTestUiState(
                                result = ExternalApiTester.testOpenCellId(tokenBeingTested)
                            )
                        }
                    }
                )
                SaveSettingsButton {
                    viewModel.setGoogleApiKey(localGoogleApiKey)
                    viewModel.setWigleApiToken(localWigleToken)
                    viewModel.setOpencellidApiToken(localOpencellidToken)
                    Toast.makeText(context, context.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                }
            }

            ExpandableSettingsRow(
                title = stringResource(R.string.runtime_recovery),
                summary = stringResource(R.string.cleanup_runtime_env),
                expanded = expandedSection == "runtime",
                onClick = { toggleSection("runtime") }
            ) {
                Text(
                    stringResource(R.string.runtime_recovery_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showCleanupDialog = true },
                    enabled = !isCleaningRuntime,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        if (isCleaningRuntime) stringResource(R.string.cleaning_runtime_env) else stringResource(R.string.cleanup_runtime_env),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCleaningRuntime) showCleanupDialog = false },
            title = { Text(stringResource(R.string.cleanup_runtime_env_confirm_title)) },
            text = { Text(stringResource(R.string.cleanup_runtime_env_confirm_msg)) },
            confirmButton = {
                TextButton(
                    enabled = !isCleaningRuntime,
                    onClick = {
                        isCleaningRuntime = true
                        viewModel.cleanupRuntimeEnvironment { ok ->
                            isCleaningRuntime = false
                            showCleanupDialog = false
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.cleanup_runtime_env_done else R.string.cleanup_runtime_env_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCleaningRuntime,
                    onClick = { showCleanupDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ExpandableSettingsRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.56f)
                    )
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.56f)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun CopyableReadOnlyField(
    value: String,
    label: String,
    onCopy: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
        },
        colors = settingsTextFieldColors()
    )
}

private data class ApiTestUiState(
    val isTesting: Boolean = false,
    val result: ExternalApiTester.Result? = null
)

@Composable
private fun ApiCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    state: ApiTestUiState,
    onTest: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !state.isTesting,
                label = { Text(label, maxLines = 1) },
                placeholder = {
                    Text(
                        placeholder,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = settingsTextFieldColors()
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTest,
                enabled = !state.isTesting,
                modifier = Modifier.widthIn(min = 68.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.api_test_button))
                }
            }
        }

        val message = when {
            state.isTesting -> stringResource(R.string.api_test_testing)
            state.result != null -> apiTestResultMessage(state.result)
            else -> null
        }
        if (message != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 2.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = when {
                    state.isTesting -> MaterialTheme.colorScheme.primary
                    state.result?.isSuccess == true -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun apiTestResultMessage(result: ExternalApiTester.Result): String {
    return when (result.code) {
        ExternalApiTester.ResultCode.SUCCESS -> stringResource(R.string.api_test_success)
        ExternalApiTester.ResultCode.MISSING_CREDENTIAL -> stringResource(R.string.api_test_missing_credential)
        ExternalApiTester.ResultCode.INVALID_CREDENTIAL -> stringResource(R.string.api_test_invalid_credential)
        ExternalApiTester.ResultCode.ACCESS_RESTRICTED -> stringResource(R.string.api_test_access_restricted)
        ExternalApiTester.ResultCode.SERVICE_NOT_ENABLED -> stringResource(R.string.api_test_service_not_enabled)
        ExternalApiTester.ResultCode.BILLING_REQUIRED -> stringResource(R.string.api_test_billing_required)
        ExternalApiTester.ResultCode.QUOTA_EXCEEDED -> stringResource(R.string.api_test_quota_exceeded)
        ExternalApiTester.ResultCode.NETWORK_UNAVAILABLE -> stringResource(R.string.api_test_network_unavailable)
        ExternalApiTester.ResultCode.TIMEOUT -> stringResource(R.string.api_test_timeout)
        ExternalApiTester.ResultCode.SERVER_UNAVAILABLE -> result.httpStatus?.let {
            stringResource(R.string.api_test_server_unavailable_http, it)
        } ?: stringResource(R.string.api_test_server_unavailable)
        ExternalApiTester.ResultCode.INVALID_RESPONSE -> stringResource(R.string.api_test_invalid_response)
        ExternalApiTester.ResultCode.UNKNOWN_ERROR -> result.httpStatus?.let {
            stringResource(R.string.api_test_unknown_http, it)
        } ?: stringResource(R.string.api_test_unknown)
    }
}

@Composable
private fun SaveSettingsButton(onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
    ) {
        Text(stringResource(R.string.save), modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
)
