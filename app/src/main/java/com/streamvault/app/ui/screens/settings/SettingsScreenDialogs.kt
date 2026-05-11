package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun SettingsScreenDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    scope: CoroutineScope,
    dialogState: SettingsScreenDialogState
) {
    val providerState = rememberSettingsProviderSectionState(dialogState)

    SyncingOverlay(
        isSyncing = uiState.isSyncing,
        providerName = uiState.syncingProviderName,
        progress = uiState.syncProgress
    )

    if (dialogState.showLiveTvModeDialog) {
        LiveTvChannelModeDialog(
            selectedMode = uiState.liveTvChannelMode,
            onDismiss = { dialogState.showLiveTvModeDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveTvChannelMode(mode)
                dialogState.showLiveTvModeDialog = false
            }
        )
    }

    if (dialogState.showLiveTvQuickFilterVisibilityDialog) {
        LiveTvQuickFilterVisibilityDialog(
            selectedMode = uiState.liveTvQuickFilterVisibilityMode,
            onDismiss = { dialogState.showLiveTvQuickFilterVisibilityDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveTvQuickFilterVisibilityMode(mode)
                dialogState.showLiveTvQuickFilterVisibilityDialog = false
            }
        )
    }

    if (dialogState.showLiveChannelNumberingDialog) {
        LiveChannelNumberingModeDialog(
            selectedMode = uiState.liveChannelNumberingMode,
            onDismiss = { dialogState.showLiveChannelNumberingDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveChannelNumberingMode(mode)
                dialogState.showLiveChannelNumberingDialog = false
            }
        )
    }

    if (dialogState.showLiveChannelGroupingDialog) {
        LiveChannelGroupingModeDialog(
            selectedMode = uiState.liveChannelGroupingMode,
            onDismiss = { dialogState.showLiveChannelGroupingDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveChannelGroupingMode(mode)
                dialogState.showLiveChannelGroupingDialog = false
            }
        )
    }

    if (dialogState.showGroupedChannelLabelDialog) {
        GroupedChannelLabelModeDialog(
            selectedMode = uiState.groupedChannelLabelMode,
            onDismiss = { dialogState.showGroupedChannelLabelDialog = false },
            onModeSelected = { mode ->
                viewModel.setGroupedChannelLabelMode(mode)
                dialogState.showGroupedChannelLabelDialog = false
            }
        )
    }

    if (dialogState.showLiveVariantPreferenceDialog) {
        LiveVariantPreferenceModeDialog(
            selectedMode = uiState.liveVariantPreferenceMode,
            onDismiss = { dialogState.showLiveVariantPreferenceDialog = false },
            onModeSelected = { mode ->
                viewModel.setLiveVariantPreferenceMode(mode)
                dialogState.showLiveVariantPreferenceDialog = false
            }
        )
    }

    if (dialogState.showVodViewModeDialog) {
        VodViewModeDialog(
            selectedMode = uiState.vodViewMode,
            onDismiss = { dialogState.showVodViewModeDialog = false },
            onModeSelected = { mode ->
                viewModel.setVodViewMode(mode)
                dialogState.showVodViewModeDialog = false
            }
        )
    }

    SettingsPreferenceDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        showGuideDefaultCategoryDialog = dialogState.showGuideDefaultCategoryDialog,
        onShowGuideDefaultCategoryDialogChange = { dialogState.showGuideDefaultCategoryDialog = it },
        showPlaybackSpeedDialog = dialogState.showPlaybackSpeedDialog,
        onShowPlaybackSpeedDialogChange = { dialogState.showPlaybackSpeedDialog = it },
        showTimeFormatDialog = dialogState.showTimeFormatDialog,
        onShowTimeFormatDialogChange = { dialogState.showTimeFormatDialog = it },
        showAudioVideoOffsetDialog = dialogState.showAudioVideoOffsetDialog,
        onShowAudioVideoOffsetDialogChange = { dialogState.showAudioVideoOffsetDialog = it },
        showDecoderModeDialog = dialogState.showDecoderModeDialog,
        onShowDecoderModeDialogChange = { dialogState.showDecoderModeDialog = it },
        showSurfaceModeDialog = dialogState.showSurfaceModeDialog,
        onShowSurfaceModeDialogChange = { dialogState.showSurfaceModeDialog = it },
        showTimeshiftDepthDialog = dialogState.showTimeshiftDepthDialog,
        onShowTimeshiftDepthDialogChange = { dialogState.showTimeshiftDepthDialog = it },
        showDefaultStopTimerDialog = dialogState.showDefaultStopTimerDialog,
        onShowDefaultStopTimerDialogChange = { dialogState.showDefaultStopTimerDialog = it },
        showDefaultIdleTimerDialog = dialogState.showDefaultIdleTimerDialog,
        onShowDefaultIdleTimerDialogChange = { dialogState.showDefaultIdleTimerDialog = it },
        showControlsTimeoutDialog = dialogState.showControlsTimeoutDialog,
        onShowControlsTimeoutDialogChange = { dialogState.showControlsTimeoutDialog = it },
        showLiveOverlayTimeoutDialog = dialogState.showLiveOverlayTimeoutDialog,
        onShowLiveOverlayTimeoutDialogChange = { dialogState.showLiveOverlayTimeoutDialog = it },
        showNoticeTimeoutDialog = dialogState.showNoticeTimeoutDialog,
        onShowNoticeTimeoutDialogChange = { dialogState.showNoticeTimeoutDialog = it },
        showDiagnosticsTimeoutDialog = dialogState.showDiagnosticsTimeoutDialog,
        onShowDiagnosticsTimeoutDialogChange = { dialogState.showDiagnosticsTimeoutDialog = it },
        showLiveTvFiltersDialog = dialogState.showLiveTvFiltersDialog,
        onShowLiveTvFiltersDialogChange = { dialogState.showLiveTvFiltersDialog = it },
        showAudioLanguageDialog = dialogState.showAudioLanguageDialog,
        onShowAudioLanguageDialogChange = { dialogState.showAudioLanguageDialog = it },
        showSubtitleSizeDialog = dialogState.showSubtitleSizeDialog,
        onShowSubtitleSizeDialogChange = { dialogState.showSubtitleSizeDialog = it },
        showSubtitleTextColorDialog = dialogState.showSubtitleTextColorDialog,
        onShowSubtitleTextColorDialogChange = { dialogState.showSubtitleTextColorDialog = it },
        showSubtitleBackgroundDialog = dialogState.showSubtitleBackgroundDialog,
        onShowSubtitleBackgroundDialogChange = { dialogState.showSubtitleBackgroundDialog = it },
        showWifiQualityDialog = dialogState.showWifiQualityDialog,
        onShowWifiQualityDialogChange = { dialogState.showWifiQualityDialog = it },
        showEthernetQualityDialog = dialogState.showEthernetQualityDialog,
        onShowEthernetQualityDialogChange = { dialogState.showEthernetQualityDialog = it },
        showLanguageDialog = dialogState.showLanguageDialog,
        onShowLanguageDialogChange = { dialogState.showLanguageDialog = it },
        categorySortDialogType = dialogState.categorySortDialogType,
        onCategorySortDialogTypeChange = { dialogState.categorySortDialogType = it }
    )

    SettingsProtectionDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        showPinDialog = dialogState.showPinDialog,
        onShowPinDialogChange = { dialogState.showPinDialog = it },
        showLevelDialog = dialogState.showLevelDialog,
        onShowLevelDialogChange = { dialogState.showLevelDialog = it },
        pinError = dialogState.pinError,
        onPinErrorChange = { dialogState.pinError = it },
        pendingAction = dialogState.pendingAction,
        onPendingActionChange = { dialogState.pendingAction = it },
        pendingProtectionLevel = dialogState.pendingProtectionLevel,
        onPendingProtectionLevelChange = { dialogState.pendingProtectionLevel = it }
    )

    SettingsRecordingDialogs(
        uiState = uiState,
        viewModel = viewModel,
        showRecordingPatternDialog = dialogState.showRecordingPatternDialog,
        onShowRecordingPatternDialogChange = { dialogState.showRecordingPatternDialog = it },
        showRecordingRetentionDialog = dialogState.showRecordingRetentionDialog,
        onShowRecordingRetentionDialogChange = { dialogState.showRecordingRetentionDialog = it },
        showRecordingConcurrencyDialog = dialogState.showRecordingConcurrencyDialog,
        onShowRecordingConcurrencyDialogChange = { dialogState.showRecordingConcurrencyDialog = it },
        showRecordingPaddingDialog = dialogState.showRecordingPaddingDialog,
        onShowRecordingPaddingDialogChange = { dialogState.showRecordingPaddingDialog = it }
    )

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
            onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
            isImporting = uiState.isImportingBackup,
            onConfirm = { viewModel.confirmBackupImport() }
        )
    }

    if (dialogState.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { dialogState.showClearHistoryDialog = false },
            title = { Text(text = stringResource(R.string.settings_clear_history_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_clear_history_dialog_body),
                    color = OnSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        dialogState.showClearHistoryDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.settings_clear_history_confirm), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState.showClearHistoryDialog = false }) {
                    Text(text = stringResource(R.string.settings_cancel), color = OnSurface)
                }
            },
            containerColor = SurfaceElevated,
            titleContentColor = OnSurface,
            textContentColor = TextSecondary
        )
    }

    uiState.viewedCrashReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCrashReport,
            title = { Text(text = stringResource(R.string.settings_crash_report_view_title)) },
            text = {
                Text(
                    text = report.content,
                    color = OnSurface,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissCrashReport) {
                    Text(text = stringResource(R.string.player_close), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::deleteCrashReport) {
                    Text(text = stringResource(R.string.settings_crash_report_delete), color = OnSurface)
                }
            },
            containerColor = SurfaceElevated,
            titleContentColor = OnSurface,
            textContentColor = TextSecondary
        )
    }

    SettingsProviderManagementDialogs(
        uiState = uiState,
        viewModel = viewModel,
        providerState = providerState
    )
}
