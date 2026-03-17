package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.app.ui.screens.settings.ProviderDiagnosticsUiModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import com.streamvault.app.R
import java.util.Locale
import com.streamvault.app.BuildConfig


@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    currentRoute: String,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLanguageLabel = remember(uiState.appLanguage, context) {
        displayLanguageLabel(uiState.appLanguage, context.getString(R.string.settings_system_default))
    }
    val protectionSummary = remember(uiState.parentalControlLevel, context) {
        when (uiState.parentalControlLevel) {
            0 -> context.getString(R.string.settings_level_off)
            1 -> context.getString(R.string.settings_level_locked)
            2 -> context.getString(R.string.settings_level_hidden)
            else -> context.getString(R.string.settings_level_unknown)
        }
    }

    // Parental Control State
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showLevelDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTvModeDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ParentalAction?>(null) }
    var pendingProtectionLevel by rememberSaveable { mutableStateOf<Int?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportConfig(it.toString()) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectBackup(it.toString()) }
    }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = { if (!uiState.isSyncing) onNavigate(it) },
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_providers_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, top = 80.dp, end = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = !uiState.isSyncing
            ) {
            // Providers section
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_providers),
                    subtitle = stringResource(R.string.settings_providers_subtitle)
                )
            }

            if (uiState.providers.isEmpty()) {
                item {
                    TvEmptyState(
                        title = stringResource(R.string.settings_no_providers),
                        subtitle = stringResource(R.string.settings_no_providers_subtitle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp)
                    )
                }
            } else {
                item {
                    var selectedProviderId by rememberSaveable(uiState.providers, uiState.activeProviderId) {
                        mutableStateOf(uiState.activeProviderId ?: uiState.providers.first().id)
                    }
                    LaunchedEffect(uiState.providers, uiState.activeProviderId) {
                        val availableIds = uiState.providers.map { it.id }.toSet()
                        if (selectedProviderId !in availableIds) {
                            selectedProviderId = uiState.activeProviderId ?: uiState.providers.first().id
                        }
                    }

                    val selectedProvider = uiState.providers.firstOrNull { it.id == selectedProviderId }
                        ?: uiState.providers.first()

                    Text(
                        text = stringResource(R.string.settings_provider_selector_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 14.dp)
                    ) {
                        items(uiState.providers, key = { it.id }) { provider ->
                            ProviderSelectorTab(
                                provider = provider,
                                isSelected = provider.id == selectedProvider.id,
                                isActive = provider.id == uiState.activeProviderId,
                                onClick = { selectedProviderId = provider.id }
                            )
                        }
                    }

                    ProviderSettingsCard(
                        provider = selectedProvider,
                        isActive = selectedProvider.id == uiState.activeProviderId,
                        isSyncing = uiState.isSyncing,
                        diagnostics = uiState.diagnosticsByProvider[selectedProvider.id],
                        syncWarnings = uiState.syncWarningsByProvider[selectedProvider.id].orEmpty(),
                        onRetryWarningAction = { action -> viewModel.retryWarningAction(selectedProvider.id, action) },
                        onConnect = { viewModel.setActiveProvider(selectedProvider.id) },
                        onRefresh = { viewModel.refreshProvider(selectedProvider.id) },
                        onDelete = { viewModel.deleteProvider(selectedProvider.id) },
                        onEdit = { onEditProvider(selectedProvider) },
                        onParentalControl = { onNavigateToParentalControl(selectedProvider.id) }
                    )
                }
            }

            // Add Provider button
            item {
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onAddProvider,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_add_provider),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Primary
                        )
                    }
                }
            }

            // Parental Control
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_parental_control),
                    subtitle = stringResource(R.string.settings_parental_subtitle)
                )
                ParentalControlCard(
                    level = uiState.parentalControlLevel,
                    hasParentalPin = uiState.hasParentalPin,
                    hasActiveProvider = uiState.activeProviderId != null,
                    onChangeLevel = {
                        pendingProtectionLevel = null
                        if (uiState.hasParentalPin) {
                            pendingAction = ParentalAction.ChangeLevel
                            showPinDialog = true
                        } else {
                            showLevelDialog = true
                        }
                    },
                    onChangePin = {
                        pendingProtectionLevel = null
                        pendingAction = if (uiState.hasParentalPin) {
                            ParentalAction.ChangePin
                        } else {
                            ParentalAction.SetNewPin
                        }
                        showPinDialog = true
                    },
                    onManageProtectedGroups = {
                        uiState.activeProviderId?.let(onNavigateToParentalControl)
                    }
                )
            }

            // Language Settings
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_language),
                    subtitle = stringResource(R.string.settings_language_subtitle)
                )

                Surface(
                    onClick = { showLanguageDialog = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.2f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_app_language),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                        Text(
                            text = appLanguageLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Primary
                        )
                    }
                }
            }

            // Backup & Restore settings
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_subtitle)
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        onClick = { createDocumentLauncher.launch("streamvault_backup.json") },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_backup_data),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    Surface(
                        onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_restore_data),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_recording_title),
                    subtitle = stringResource(R.string.settings_recording_subtitle)
                )
                RecordingOverviewCard(
                    outputDirectory = uiState.recordingStorageState.outputDirectory,
                    availableBytes = uiState.recordingStorageState.availableBytes,
                    isWritable = uiState.recordingStorageState.isWritable,
                    activeCount = uiState.recordingItems.count { it.status == RecordingStatus.RECORDING },
                    scheduledCount = uiState.recordingItems.count { it.status == RecordingStatus.SCHEDULED }
                )
            }

            if (uiState.recordingItems.isEmpty()) {
                item {
                    Surface(
                        onClick = {},
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Primary.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp)
                    ) {
                        TvEmptyState(
                            title = stringResource(R.string.settings_recording_empty_title),
                            subtitle = stringResource(R.string.settings_recording_empty_subtitle)
                        )
                    }
                }
            } else {
                items(uiState.recordingItems, key = { it.id }) { item ->
                    RecordingItemCard(
                        item = item,
                        onStop = { viewModel.stopRecording(item.id) },
                        onCancel = { viewModel.cancelRecording(item.id) },
                        onDelete = { viewModel.deleteRecording(item.id) }
                    )
                }
            }

            // Decoder settings
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_playback),
                    subtitle = stringResource(R.string.settings_playback_subtitle)
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_tv_channel_mode),
                    value = stringResource(uiState.liveTvChannelMode.labelResId()),
                    onClick = { showLiveTvModeDialog = true }
                )
                SettingsRow(label = stringResource(R.string.settings_decoder_mode), value = stringResource(R.string.settings_decoder_auto))
                SettingsRow(label = stringResource(R.string.settings_buffer_duration), value = stringResource(R.string.settings_buffer_5s))
            }

            // Privacy section
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_privacy),
                    subtitle = stringResource(R.string.settings_privacy_subtitle)
                )
                
                Surface(
                    onClick = { viewModel.toggleIncognitoMode() },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.2f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_incognito_mode),
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnBackground
                            )
                            Text(
                                text = stringResource(R.string.settings_incognito_mode_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBackground.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = uiState.isIncognitoMode,
                            onCheckedChange = { viewModel.toggleIncognitoMode() }
                        )
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                Surface(
                    onClick = { showClearHistoryDialog = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.2f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_clear_history),
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnBackground
                            )
                            Text(
                                text = stringResource(R.string.settings_clear_history_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBackground.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_clear_history_confirm),
                            style = MaterialTheme.typography.labelLarge,
                            color = Primary
                        )
                    }
                }
            }

            // About section
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.settings_about_subtitle)
                )
                SettingsRow(label = stringResource(R.string.settings_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
                SettingsRow(label = stringResource(R.string.settings_developed_by), value = stringResource(R.string.settings_developer_name))
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_github),
                    value = stringResource(R.string.settings_github_url),
                    onClick = { uriHandler.openUri(context.getString(R.string.settings_github_url)) }
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_donate),
                    value = stringResource(R.string.settings_donate_url),
                    onClick = { uriHandler.openUri(context.getString(R.string.settings_donate_url)) }
                )
            }
        }
        }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )

        // Blocking Loading Overlay
        if (uiState.isSyncing) {
            // Block Back Press
            androidx.activity.compose.BackHandler(enabled = true) {
                // Do nothing, effectively blocking back
            }
            
            // Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = true, onClick = {}) // Consume clicks
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Primary)
                    Text(
                        text = stringResource(R.string.settings_syncing_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_syncing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
            }
        }


        if (showLiveTvModeDialog) {
            LiveTvChannelModeDialog(
                selectedMode = uiState.liveTvChannelMode,
                onDismiss = { showLiveTvModeDialog = false },
                onModeSelected = { mode ->
                    viewModel.setLiveTvChannelMode(mode)
                    showLiveTvModeDialog = false
                }
            )
        }

        if (showPinDialog) {
            PinDialog(
                onDismissRequest = { 
                    showPinDialog = false
                    pinError = null
                    if (pendingAction == ParentalAction.SetNewPin) {
                        pendingAction = null
                        pendingProtectionLevel = null
                    }
                },
                onPinEntered = { pin ->
                    scope.launch {
                        if (pendingAction == ParentalAction.SetNewPin) {
                            viewModel.changePin(pin)
                            pendingProtectionLevel?.let(viewModel::setParentalControlLevel)
                            pendingProtectionLevel = null
                            showPinDialog = false
                            pendingAction = null
                        } else {
                            if (viewModel.verifyPin(pin)) {
                                showPinDialog = false
                                pinError = null
                                when (pendingAction) {
                                    ParentalAction.ChangeLevel -> showLevelDialog = true
                                    ParentalAction.ChangePin -> {
                                        pendingAction = ParentalAction.SetNewPin
                                        showPinDialog = true 
                                    }
                                    else -> pendingAction = null
                                }
                            } else {
                                pinError = context.getString(R.string.home_incorrect_pin)
                            }
                        }
                    }
                },
                title = if (pendingAction == ParentalAction.SetNewPin) stringResource(R.string.settings_enter_new_pin) else stringResource(R.string.settings_enter_pin),
                error = pinError
            )
        }

        if (showLevelDialog) {
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_level),
                onDismiss = { showLevelDialog = false }
            ) {
                LevelOption(0, stringResource(R.string.settings_level_off_desc), uiState.parentalControlLevel) {
                    viewModel.setParentalControlLevel(0)
                    showLevelDialog = false
                }
                LevelOption(1, stringResource(R.string.settings_level_locked_desc), uiState.parentalControlLevel) {
                    if (uiState.hasParentalPin) {
                        viewModel.setParentalControlLevel(1)
                    } else {
                        pendingProtectionLevel = 1
                        pendingAction = ParentalAction.SetNewPin
                        showPinDialog = true
                    }
                    showLevelDialog = false
                }
                LevelOption(2, stringResource(R.string.settings_level_hidden_desc), uiState.parentalControlLevel) {
                    if (uiState.hasParentalPin) {
                        viewModel.setParentalControlLevel(2)
                    } else {
                        pendingProtectionLevel = 2
                        pendingAction = ParentalAction.SetNewPin
                        showPinDialog = true
                    }
                    showLevelDialog = false
                }
            }
        }

        if (showLanguageDialog) {
            val systemDefaultLabel = stringResource(R.string.settings_system_default)
            val languageOptions = remember(systemDefaultLabel) { supportedAppLanguages(systemDefaultLabel) }
            PremiumSelectionDialog(
                title = stringResource(R.string.settings_select_language),
                onDismiss = { showLanguageDialog = false }
            ) {
                languageOptions.forEachIndexed { index, option ->
                    LevelOption(
                        level = index,
                        text = option.label,
                        currentLevel = if (uiState.appLanguage == option.tag) index else -1,
                        onSelect = {
                            viewModel.setAppLanguage(option.tag)
                            showLanguageDialog = false
                        }
                    )
                }
            }
        }

        val backupPreview = uiState.backupPreview
        if (backupPreview != null && uiState.pendingBackupUri != null) {
            BackupImportPreviewDialog(
                preview = backupPreview,
                plan = uiState.backupImportPlan,
                onDismiss = { viewModel.dismissBackupPreview() },
                onStrategySelected = { viewModel.setBackupConflictStrategy(it) },
                onImportPreferencesChanged = { viewModel.setImportPreferences(it) },
                onImportProvidersChanged = { viewModel.setImportProviders(it) },
                onImportSavedLibraryChanged = { viewModel.setImportSavedLibrary(it) },
                onImportPlaybackHistoryChanged = { viewModel.setImportPlaybackHistory(it) },
                onImportMultiViewChanged = { viewModel.setImportMultiViewPresets(it) },
                onConfirm = { viewModel.confirmBackupImport() }
            )
        }

        if (showClearHistoryDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = { Text(text = stringResource(R.string.settings_clear_history_dialog_title)) },
                text = { Text(text = stringResource(R.string.settings_clear_history_dialog_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearHistory()
                            showClearHistoryDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.settings_clear_history_confirm), color = Primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearHistoryDialog = false }) {
                        Text(text = stringResource(R.string.settings_cancel))
                    }
                },
                containerColor = SurfaceElevated,
                titleContentColor = OnSurface,
                textContentColor = TextSecondary
            )
        }
    }
}

private enum class ParentalAction {
    ChangeLevel, ChangePin, SetNewPin
}

@Composable
private fun PremiumSelectionDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = SurfaceElevated,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .border(1.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Primary
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    content()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        onClick = onDismiss,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Primary.copy(alpha = 0.2f),
                            focusedContainerColor = Primary.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_cancel),
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelOption(level: Int, text: String, currentLevel: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = level == currentLevel,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
    }
}

@Composable
private fun ParentalControlCard(
    level: Int,
    hasParentalPin: Boolean,
    hasActiveProvider: Boolean,
    onChangeLevel: () -> Unit,
    onChangePin: () -> Unit,
    onManageProtectedGroups: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.settings_protection_level), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                Text(
                    text = when(level) {
                        0 -> stringResource(R.string.settings_level_off)
                        1 -> stringResource(R.string.settings_level_locked)
                        2 -> stringResource(R.string.settings_level_hidden)
                        else -> stringResource(R.string.settings_level_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level == 0) ErrorColor else Primary
                )
            }
            
            // Custom Focusable Button for "Change"
            Surface(
                onClick = onChangeLevel,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f),
                    contentColor = Secondary,
                    focusedContentColor = Secondary
                )
            ) {
                Text(
                    text = stringResource(R.string.settings_change),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_parental_pin), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            
            // Custom Focusable Button for "Change PIN"
            Surface(
                onClick = onChangePin,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.2f),
                    focusedContainerColor = Primary.copy(alpha = 0.5f),
                    contentColor = Primary,
                    focusedContentColor = Primary
                )
            ) {
                Text(
                    text = stringResource(if (hasParentalPin) R.string.settings_change_pin else R.string.settings_set_pin),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ProviderSettingsCard(
    provider: Provider,
    isActive: Boolean,
    isSyncing: Boolean,
    diagnostics: ProviderDiagnosticsUiModel?,
    syncWarnings: List<String>,
    onRetryWarningAction: (ProviderWarningAction) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onParentalControl: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Use Column layout - provider info + buttons below as separate focusable items
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isActive) SurfaceHighlight else SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) Primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            ProviderStatusBadge(status = provider.status)
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Expiration Date
        val expDate = provider.expirationDate
        val expirationText = remember(expDate) {
            when (expDate) {
                null -> context.getString(R.string.settings_expiration_unknown)
                Long.MAX_VALUE -> context.getString(R.string.settings_expiration_never)
                else -> context.getString(R.string.settings_expires, java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expDate)))
            }
        }
        Text(
            text = expirationText,
            style = MaterialTheme.typography.bodySmall,
            color = if (expDate != null && expDate < System.currentTimeMillis() && expDate != Long.MAX_VALUE) ErrorColor else OnSurfaceDim
        )

        diagnostics?.let { model ->
            Text(
                text = listOf(model.sourceLabel, model.connectionSummary, model.expirySummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_live), count = model.liveCount)
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_movies), count = model.movieCount)
                if (provider.type == ProviderType.XTREAM_CODES) {
                    ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_series), count = model.seriesCount)
                }
                ProviderCompactStat(title = stringResource(R.string.settings_diagnostic_epg), count = model.epgCount)
            }
        }

        if (syncWarnings.isNotEmpty()) {
            val hasEpgWarning = syncWarnings.any { it.contains("EPG", ignoreCase = true) }
            val hasMoviesWarning = syncWarnings.any { it.contains("Movies", ignoreCase = true) }
            val hasSeriesWarning = syncWarnings.any { it.contains("Series", ignoreCase = true) }

            Text(
                text = stringResource(R.string.settings_provider_warnings, syncWarnings.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = Secondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasEpgWarning) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.EPG) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_epg),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasMoviesWarning) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.MOVIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_movies),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (hasSeriesWarning && provider.type == ProviderType.XTREAM_CODES) {
                    Surface(
                        onClick = { onRetryWarningAction(ProviderWarningAction.SERIES) },
                        enabled = !isSyncing,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Secondary.copy(alpha = 0.16f),
                            focusedContainerColor = Secondary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_retry_series),
                            style = MaterialTheme.typography.labelSmall,
                            color = Secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Action buttons - each independently focusable for d-pad
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isActive) {
                Surface(
                    onClick = onConnect,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = Primary.copy(alpha = 0.8f),
                        contentColor = Color.White
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_connect),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            } else {
                Surface(
                    onClick = onRefresh,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.2f),
                        focusedContainerColor = Primary.copy(alpha = 0.5f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = if (isSyncing) stringResource(R.string.settings_syncing_btn) else stringResource(R.string.settings_sync_btn),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            Surface(
                onClick = onEdit,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = Secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Surface(
                onClick = onDelete,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = ErrorColor.copy(alpha = 0.2f),
                    focusedContainerColor = ErrorColor.copy(alpha = 0.5f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = ErrorColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (isActive) {
                Surface(
                    onClick = onParentalControl,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.settings_parental_groups_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

    }
}

@Composable
private fun ProviderSelectorTab(
    provider: Provider,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.34f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) Primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f)
                )
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 2.dp,
                    color = Primary
                )
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ProviderCompactStat(
    title: String,
    count: Int
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = OnBackground
            )
        }
    }
}

@Composable
private fun ProviderStatusBadge(status: ProviderStatus) {
    val (label, color) = when (status) {
        ProviderStatus.ACTIVE -> stringResource(R.string.settings_status_active) to Primary
        ProviderStatus.PARTIAL -> stringResource(R.string.settings_status_partial) to Secondary
        ProviderStatus.ERROR -> stringResource(R.string.settings_status_error) to ErrorColor
        ProviderStatus.EXPIRED -> stringResource(R.string.settings_status_expired) to ErrorColor
        ProviderStatus.DISABLED -> stringResource(R.string.settings_status_disabled) to OnSurfaceDim
        ProviderStatus.UNKNOWN -> stringResource(R.string.settings_status_unknown) to OnSurfaceDim
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsOverviewCard(
    activeProviderName: String,
    providerCount: Int,
    protectionSummary: String,
    languageLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_overview_title),
                style = MaterialTheme.typography.titleLarge,
                color = OnBackground
            )
            Text(
                text = stringResource(R.string.settings_overview_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_provider_label),
                    value = activeProviderName
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_count_label),
                    value = providerCount.toString()
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_protection_label),
                    value = protectionSummary
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_language_label),
                    value = languageLabel
                )
            }
        }

    }
}

@Composable
private fun ProviderDiagnosticsPanel(
    provider: Provider,
    diagnostics: ProviderDiagnosticsUiModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_provider_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = diagnostics.capabilitySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(R.string.diagnostics_summary_format, diagnostics.sourceLabel, diagnostics.connectionSummary),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.expirySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.archiveSummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_live),
                count = diagnostics.liveCount,
                timestamp = diagnostics.lastLiveSync
            )
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_movies),
                count = diagnostics.movieCount,
                timestamp = diagnostics.lastMovieSync
            )
            if (provider.type == ProviderType.XTREAM_CODES) {
                ProviderDiagnosticPill(
                    title = stringResource(R.string.settings_diagnostic_series),
                    count = diagnostics.seriesCount,
                    timestamp = diagnostics.lastSeriesSync
                )
            }
            ProviderDiagnosticPill(
                title = stringResource(R.string.settings_diagnostic_epg),
                count = diagnostics.epgCount,
                timestamp = diagnostics.lastEpgSync
            )
        }
        Text(
            text = stringResource(R.string.settings_diagnostic_status, diagnostics.lastSyncStatus),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface
        )
    }
}

@Composable
private fun ProviderDiagnosticPill(
    title: String,
    count: Int,
    timestamp: Long
) {
    val syncLabel = remember(timestamp) {
        if (timestamp <= 0L) {
            null
        } else {
            java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = stringResource(R.string.settings_diagnostic_items, count),
                style = MaterialTheme.typography.labelLarge,
                color = OnBackground
            )
            Text(
                text = syncLabel ?: stringResource(R.string.settings_diagnostic_never),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurface
            )
        }
    }
}

@Composable
private fun SettingsOverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SurfaceHighlight.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LiveTvChannelModeDialog(
    selectedMode: LiveTvChannelMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveTvChannelMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_tv_channel_mode),
        subtitle = stringResource(R.string.settings_live_tv_channel_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveTvChannelMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
        }
    }
}

@Composable
private fun ClickableSettingsRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Primary)
        }
    }
}

@Composable
private fun BackupImportPreviewDialog(
    preview: com.streamvault.domain.manager.BackupPreview,
    plan: com.streamvault.domain.manager.BackupImportPlan,
    onDismiss: () -> Unit,
    onStrategySelected: (BackupConflictStrategy) -> Unit,
    onImportPreferencesChanged: (Boolean) -> Unit,
    onImportProvidersChanged: (Boolean) -> Unit,
    onImportSavedLibraryChanged: (Boolean) -> Unit,
    onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    onImportMultiViewChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_backup_preview_title),
        subtitle = stringResource(R.string.settings_backup_preview_subtitle, preview.version),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            BackupPreviewRow(stringResource(R.string.settings_backup_section_preferences), preview.preferenceCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_providers), preview.providerCount, preview.providerConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_saved), preview.favoriteCount + preview.groupCount, preview.favoriteConflicts + preview.groupConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_history), preview.playbackHistoryCount, preview.historyConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_multiview), preview.multiViewPresetCount, 0)
            Text(
                text = stringResource(R.string.settings_backup_conflict_strategy),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_keep_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING,
                    onClick = { onStrategySelected(BackupConflictStrategy.KEEP_EXISTING) }
                )
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_replace_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING,
                    onClick = { onStrategySelected(BackupConflictStrategy.REPLACE_EXISTING) }
                )
            }
            Text(
                text = stringResource(R.string.settings_backup_import_sections),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            BackupToggleRow(stringResource(R.string.settings_backup_section_preferences), plan.importPreferences, onImportPreferencesChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_providers), plan.importProviders, onImportProvidersChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_saved), plan.importSavedLibrary, onImportSavedLibraryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_history), plan.importPlaybackHistory, onImportPlaybackHistoryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_multiview), plan.importMultiViewPresets, onImportMultiViewChanged)
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_backup_import_confirm),
                onClick = onConfirm,
                emphasized = true
            )
        }
    )
}

@Composable
private fun BackupPreviewRow(
    title: String,
    itemCount: Int,
    conflictCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            Text(
                text = if (conflictCount > 0) {
                    stringResource(R.string.settings_backup_conflict_count, conflictCount)
                } else {
                    stringResource(R.string.settings_backup_no_conflicts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflictCount > 0) Secondary else OnSurfaceDim
            )
        }
        Text(
            text = stringResource(R.string.settings_backup_item_count, itemCount),
            style = MaterialTheme.typography.labelLarge,
            color = OnBackground
        )
    }
}

@Composable
private fun BackupStrategyChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.2f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Primary else OnBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun BackupToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RecordingOverviewCard(
    outputDirectory: String?,
    availableBytes: Long?,
    isWritable: Boolean,
    activeCount: Int,
    scheduledCount: Int
) {
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceElevated,
            focusedContentColor = OnBackground
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_recording_storage_title),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Text(
                text = outputDirectory ?: stringResource(R.string.settings_recording_storage_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_active_label),
                    value = activeCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_scheduled_label),
                    value = scheduledCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_recording_space_label),
                    value = availableBytes?.let(::formatBytes) ?: stringResource(R.string.settings_recording_storage_unknown),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = if (isWritable) stringResource(R.string.settings_recording_storage_ready) else stringResource(R.string.settings_recording_storage_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = if (isWritable) Primary else ErrorColor
            )
        }
    }
}

@Composable
private fun RecordingItemCard(
    item: RecordingItem,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.programTitle ?: item.channelName, style = MaterialTheme.typography.titleSmall, color = OnBackground)
                    Text(item.channelName, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
                Text(
                    text = when (item.status) {
                        RecordingStatus.SCHEDULED -> stringResource(R.string.settings_recording_status_scheduled)
                        RecordingStatus.RECORDING -> stringResource(R.string.settings_recording_status_recording)
                        RecordingStatus.COMPLETED -> stringResource(R.string.settings_recording_status_completed)
                        RecordingStatus.FAILED -> stringResource(R.string.settings_recording_status_failed)
                        RecordingStatus.CANCELLED -> stringResource(R.string.settings_recording_status_cancelled)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (item.status) {
                        RecordingStatus.RECORDING -> Primary
                        RecordingStatus.COMPLETED -> OnBackground
                        RecordingStatus.FAILED -> ErrorColor
                        RecordingStatus.CANCELLED -> OnSurfaceDim
                        RecordingStatus.SCHEDULED -> Secondary
                    }
                )
            }
            Text(
                text = stringResource(
                    R.string.settings_recording_time_window,
                    formatTimestamp(item.scheduledStartMs),
                    formatTimestamp(item.scheduledEndMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            item.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.status == RecordingStatus.RECORDING) {
                    Surface(
                        onClick = onStop,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = ErrorColor.copy(alpha = 0.2f),
                            focusedContainerColor = ErrorColor.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_stop),
                            color = ErrorColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (item.status == RecordingStatus.SCHEDULED) {
                    Surface(
                        onClick = onCancel,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceHighlight,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_cancel),
                            color = OnBackground,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (item.status == RecordingStatus.COMPLETED || item.status == RecordingStatus.FAILED || item.status == RecordingStatus.CANCELLED) {
                    Surface(
                        onClick = onDelete,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceHighlight,
                            focusedContainerColor = Primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.settings_recording_delete),
                            color = OnBackground,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "--:--"
    return java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(java.util.Date(timestampMs))
}

private data class AppLanguageOption(
    val tag: String,
    val label: String
)

private fun supportedAppLanguages(systemDefaultLabel: String): List<AppLanguageOption> {
    val localeTags = listOf(
        "system",
        "en",
        "ar",
        "cs",
        "da",
        "de",
        "el",
        "es",
        "fi",
        "fr",
        "he",
        "hu",
        "id",
        "it",
        "ja",
        "ko",
        "nb",
        "nl",
        "pl",
        "pt",
        "ro",
        "ru",
        "sv",
        "tr",
        "uk",
        "vi",
        "zh"
    )

    return localeTags.map { tag ->
        AppLanguageOption(
            tag = tag,
            label = if (tag == "system") {
                systemDefaultLabel
            } else {
                val locale = Locale.forLanguageTag(tag)
                locale.getDisplayLanguage(locale)
                    .replaceFirstChar { character ->
                        if (character.isLowerCase()) {
                            character.titlecase(locale)
                        } else {
                            character.toString()
                        }
                    }
            }
        )
    }
}

private fun displayLanguageLabel(languageTag: String, defaultLabel: String): String {
    if (languageTag.isBlank()) return defaultLabel
    val locale = Locale.forLanguageTag(languageTag)
    if (locale.language.isBlank()) return defaultLabel
    return locale.getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
}

private fun LiveTvChannelMode.labelResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro
}

private fun LiveTvChannelMode.descriptionResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable_desc
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact_desc
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro_desc
}

