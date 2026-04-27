package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.*
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@Composable
internal fun InternetSpeedTestCard(
    valueLabel: String,
    summary: String,
    recommendationLabel: String,
    isRunning: Boolean,
    canApplyRecommendation: Boolean,
    onRunTest: () -> Unit,
    onApplyWifi: () -> Unit,
    onApplyEthernet: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onRunTest,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.18f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onRunTest
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnBackground
                    )
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isRunning) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary
                    )
                }
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )

            Text(
                text = stringResource(R.string.settings_speed_test_recommendation, recommendationLabel),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvClickableSurface(
                    onClick = onRunTest,
                    enabled = !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.18f),
                        focusedContainerColor = Primary.copy(alpha = 0.32f)
                    )
                ) {
                    Text(
                        text = stringResource(if (isRunning) R.string.settings_speed_test_running_action else R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                TvClickableSurface(
                    onClick = onApplyWifi,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_wifi),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                TvClickableSurface(
                    onClick = onApplyEthernet,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_ethernet),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun BackupImportPreviewDialog(
    preview: com.streamvault.domain.manager.BackupPreview,
    plan: com.streamvault.domain.manager.BackupImportPlan,
    onDismiss: () -> Unit,
    onStrategySelected: (BackupConflictStrategy) -> Unit,
    onImportPreferencesChanged: (Boolean) -> Unit,
    onImportProvidersChanged: (Boolean) -> Unit,
    onImportSavedLibraryChanged: (Boolean) -> Unit,
    onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    onImportMultiViewChanged: (Boolean) -> Unit,
    onImportRecordingSchedulesChanged: (Boolean) -> Unit,
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
            BackupPreviewRow(stringResource(R.string.settings_backup_section_saved), preview.favoriteCount + preview.groupCount + preview.protectedCategoryCount, preview.favoriteConflicts + preview.groupConflicts + preview.protectedCategoryConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_history), preview.playbackHistoryCount, preview.historyConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_multiview), preview.multiViewPresetCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_recordings), preview.scheduledRecordingCount, preview.recordingConflicts)
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
            BackupToggleRow(stringResource(R.string.settings_backup_section_recordings), plan.importRecordingSchedules, onImportRecordingSchedulesChanged)
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
    TvClickableSurface(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingInfoCard(
    treeLabel: String?,
    outputDirectory: String?,
    availableBytes: Long?,
    isWritable: Boolean,
    activeCount: Int,
    scheduledCount: Int,
    fileNamePattern: String,
    retentionDays: Int?,
    maxSimultaneousRecordings: Int,
    paddingBeforeMinutes: Int,
    paddingAfterMinutes: Int
) {
    TvClickableSurface(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceElevated
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_recording_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary
                    )
                    treeLabel?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = outputDirectory ?: stringResource(R.string.settings_recording_storage_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusTonePill(
                    label = if (isWritable) {
                        stringResource(R.string.settings_recording_storage_ready)
                    } else {
                        stringResource(R.string.settings_recording_storage_unavailable)
                    },
                    accent = if (isWritable) OnSurface else ErrorColor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_pattern_title),
                    value = fileNamePattern
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_retention_title),
                    value = retentionDays?.let {
                        stringResource(R.string.settings_recording_retention_days, it)
                    } ?: stringResource(R.string.settings_recording_retention_keep_all)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_concurrency_title),
                    value = maxSimultaneousRecordings.toString()
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_padding_before),
                    value = if (paddingBeforeMinutes > 0) {
                        stringResource(R.string.settings_recording_padding_minutes, paddingBeforeMinutes)
                    } else {
                        stringResource(R.string.settings_recording_padding_none)
                    }
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_padding_after),
                    value = if (paddingAfterMinutes > 0) {
                        stringResource(R.string.settings_recording_padding_minutes, paddingAfterMinutes)
                    } else {
                        stringResource(R.string.settings_recording_padding_none)
                    }
                )
            }
        }
    }
}

@Composable
internal fun RecordingActionsCard(
    wifiOnlyRecording: Boolean,
    onWifiOnlyRecordingChange: (Boolean) -> Unit,
    onChooseFolder: () -> Unit,
    onUseAppStorage: () -> Unit,
    onChangePattern: () -> Unit,
    onChangeRetention: () -> Unit,
    onChangeConcurrency: () -> Unit,
    onChangePadding: () -> Unit,
    onOpenBrowser: () -> Unit,
    onRepairSchedule: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_choose_folder),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChooseFolder
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_use_app_storage),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onUseAppStorage
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_pattern_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangePattern
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_retention_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangeRetention
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_concurrency_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangeConcurrency
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_open_browser),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBrowser
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_reconcile),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onRepairSchedule
                )
                RecordingActionButton(
                    label = stringResource(R.string.settings_recording_padding_title),
                    accent = OnBackground,
                    modifier = Modifier.weight(1f),
                    onClick = onChangePadding
                )
            }
            TvClickableSurface(
                onClick = { onWifiOnlyRecordingChange(!wifiOnlyRecording) },
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Primary.copy(alpha = 0.12f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, Color.White),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_recording_wifi_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface
                    )
                    Switch(
                        checked = wifiOnlyRecording,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Primary,
                            checkedTrackColor = Primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = OnSurfaceDim,
                            uncheckedTrackColor = OnSurfaceDim.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingBrowserDialog(
    recordingItems: List<RecordingItem>,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onStop: (RecordingItem) -> Unit,
    onCancel: (RecordingItem) -> Unit,
    onSkipOccurrence: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onRetry: (RecordingItem) -> Unit,
    onToggleSchedule: (RecordingItem, Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.93f)
                    .fillMaxHeight(0.9f),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_recording_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = OnBackground
                            )
                            Text(
                                text = if (recordingItems.isNotEmpty()) {
                                    stringResource(R.string.settings_recording_item_count, recordingItems.size)
                                } else {
                                    stringResource(R.string.settings_recording_empty_title)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_cancel),
                            accent = OnBackground,
                            onClick = onDismiss
                        )
                    }

                    if (recordingItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TvEmptyState(
                                title = stringResource(R.string.settings_recording_empty_title),
                                subtitle = stringResource(R.string.settings_recording_empty_subtitle)
                            )
                        }
                    } else {
                        RecordingBrowserPanel(
                            recordingItems = recordingItems,
                            selectedRecordingId = selectedRecordingId,
                            onSelectedRecordingChange = onSelectedRecordingChange,
                            onPlay = onPlay,
                            onStop = onStop,
                            onCancel = onCancel,
                            onSkipOccurrence = onSkipOccurrence,
                            onDelete = onDelete,
                            onRetry = onRetry,
                            onToggleSchedule = onToggleSchedule
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingBrowserPanel(
    recordingItems: List<RecordingItem>,
    selectedRecordingId: String?,
    onSelectedRecordingChange: (String) -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onStop: (RecordingItem) -> Unit,
    onCancel: (RecordingItem) -> Unit,
    onSkipOccurrence: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onRetry: (RecordingItem) -> Unit,
    onToggleSchedule: (RecordingItem, Boolean) -> Unit
) {
    val selectedItem = recordingItems.firstOrNull { it.id == selectedRecordingId } ?: recordingItems.first()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<RecordingStatus?>(null) }
    val filteredItems = remember(recordingItems, searchQuery, statusFilter) {
        recordingItems.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                item.channelName.contains(searchQuery, ignoreCase = true) ||
                item.programTitle?.contains(searchQuery, ignoreCase = true) == true
            val matchesStatus = statusFilter == null || item.status == statusFilter
            matchesSearch && matchesStatus
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.settings_recording_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = Primary
                    )
                    Text(
                        text = "${filteredItems.size} of ${recordingItems.size} items",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactRecordingActionChip(
                        label = "All",
                        accent = if (statusFilter == null) Primary else OnSurfaceDim,
                        onClick = { statusFilter = null }
                    )
                    RecordingStatus.entries.forEach { status ->
                        CompactRecordingActionChip(
                            label = recordingStatusLabel(status),
                            accent = if (statusFilter == status) recordingStatusAccent(status) else OnSurfaceDim,
                            onClick = { statusFilter = if (statusFilter == status) null else status }
                        )
                    }
                }
                val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
                val searchFocusRequester = remember { FocusRequester() }
                val inputFocusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
                TvClickableSurface(
                    onClick = {
                        acceptsInput = true
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(
                            border = BorderStroke(1.dp, OnSurfaceDim.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp)
                        ),
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(8.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search recordings…",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = OnBackground),
                            singleLine = true,
                            cursorBrush = SolidColor(Primary),
                            readOnly = isTelevisionDevice && !acceptsInput,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                acceptsInput = false
                                keyboardController?.hide()
                                searchFocusRequester.requestFocus()
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester)
                                .onFocusChanged {
                                    if (!it.isFocused && isTelevisionDevice) {
                                        acceptsInput = false
                                        keyboardController?.hide()
                                    }
                                }
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(filteredItems, key = { item -> item.id }) { item ->
                        RecordingPickerRow(
                            item = item,
                            selected = item.id == selectedItem.id,
                            onSelected = { onSelectedRecordingChange(item.id) }
                        )
                    }
                }
            }
        }

        RecordingDetailPanel(
            item = selectedItem,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onPlay = { onPlay(selectedItem) },
            onStop = { onStop(selectedItem) },
            onCancel = { onCancel(selectedItem) },
            onSkipOccurrence = { onSkipOccurrence(selectedItem) },
            onDelete = { onDelete(selectedItem) },
            onRetry = { onRetry(selectedItem) },
            onToggleSchedule = { enabled -> onToggleSchedule(selectedItem, enabled) }
        )
    }
}

@Composable
private fun RecordingPickerRow(
    item: RecordingItem,
    selected: Boolean,
    onSelected: () -> Unit
) {
    val accent = recordingStatusAccent(item.status)
    TvClickableSurface(
        onClick = onSelected,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = OnBackground,
            focusedContainerColor = SurfaceHighlight.copy(alpha = 0.48f),
            focusedContentColor = OnBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onSelected()
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 36.dp)
                    .background(accent.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = recordingDisplayTitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = recordingListSecondaryLine(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = recordingStatusLabel(item.status),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingDetailPanel(
    item: RecordingItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSkipOccurrence: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onToggleSchedule: (Boolean) -> Unit
) {
    val accent = recordingStatusAccent(item.status)
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    Surface(
        modifier = modifier,
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = recordingDisplayTitle(item),
                    style = MaterialTheme.typography.titleLarge,
                    color = OnBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                recordingDisplaySubtitle(item)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusTonePill(
                        label = recordingStatusLabel(item.status),
                        accent = accent
                    )
                    if (item.recurrence != RecordingRecurrence.NONE) {
                        StatusTonePill(
                            label = when (item.recurrence) {
                                RecordingRecurrence.DAILY -> stringResource(R.string.settings_recording_recurrence_daily)
                                RecordingRecurrence.WEEKLY -> stringResource(R.string.settings_recording_recurrence_weekly)
                                RecordingRecurrence.NONE -> stringResource(R.string.settings_recording_recurrence_none)
                            },
                            accent = Secondary
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.settings_recording_time_window,
                        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
                        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_recording_source_label),
                    value = formatRecordingSourceType(item.sourceType)
                )
                RecordingMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_recording_bytes_label),
                    value = formatBytes(item.bytesWritten)
                )
                RecordingMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_recording_speed_label),
                    value = if (item.averageThroughputBytesPerSecond > 0L) {
                        "${formatBytes(item.averageThroughputBytesPerSecond)}/s"
                    } else {
                        "N/A"
                    }
                )
            }

            if (item.retryCount > 0) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_retry_count_label),
                    value = item.retryCount.toString()
                )
            }

            item.outputDisplayPath?.takeIf { output -> output.isNotBlank() }?.let { output ->
                Text(
                    text = "${stringResource(R.string.settings_recording_output_label)}: ${summarizeRecordingOutputPath(output)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            item.failureReason?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorColor
                )
            }

            if (item.failureCategory != RecordingFailureCategory.NONE) {
                Text(
                    text = "${stringResource(R.string.settings_recording_failure_label)}: ${formatRecordingFailureCategory(item.failureCategory)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorColor
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4
            ) {
                if (item.status == RecordingStatus.COMPLETED && (!item.outputUri.isNullOrBlank() || !item.outputPath.isNullOrBlank())) {
                    CompactRecordingActionChip(
                        label = "Play",
                        accent = Primary,
                        onClick = onPlay
                    )
                }
                if (item.status == RecordingStatus.RECORDING) {
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_stop),
                        accent = ErrorColor,
                        onClick = onStop
                    )
                }
                if (item.status == RecordingStatus.SCHEDULED) {
                    CompactRecordingActionChip(
                        label = stringResource(
                            if (item.scheduleEnabled) R.string.settings_recording_disable
                            else R.string.settings_recording_enable
                        ),
                        accent = Secondary,
                        onClick = { onToggleSchedule(!item.scheduleEnabled) }
                    )
                    if (item.recurringRuleId != null) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_skip),
                            accent = OnBackground,
                            onClick = onSkipOccurrence
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_cancel),
                        accent = OnBackground,
                        onClick = onCancel
                    )
                }
                if (item.status == RecordingStatus.COMPLETED || item.status == RecordingStatus.FAILED || item.status == RecordingStatus.CANCELLED) {
                    if (item.status == RecordingStatus.FAILED) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_retry),
                            accent = Primary,
                            onClick = onRetry
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_delete),
                        accent = OnBackground,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusTonePill(
    label: String,
    accent: Color
) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

private fun recordingDisplayTitle(item: RecordingItem): String {
    val title = item.programTitle?.trim().orEmpty()
    return if (title.isNotBlank()) title else item.channelName
}

private fun recordingDisplaySubtitle(item: RecordingItem): String? {
    val title = item.programTitle?.trim().orEmpty()
    return if (title.isNotBlank() && title != item.channelName) item.channelName else null
}

@Composable
private fun recordingListSecondaryLine(item: RecordingItem): String {
    val subtitle = recordingDisplaySubtitle(item)
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    return subtitle ?: stringResource(
        R.string.settings_recording_time_window,
        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
    )
}

@Composable
private fun recordingStatusLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.SCHEDULED -> stringResource(R.string.settings_recording_status_scheduled)
    RecordingStatus.RECORDING -> stringResource(R.string.settings_recording_status_recording)
    RecordingStatus.COMPLETED -> stringResource(R.string.settings_recording_status_completed)
    RecordingStatus.FAILED -> stringResource(R.string.settings_recording_status_failed)
    RecordingStatus.CANCELLED -> stringResource(R.string.settings_recording_status_cancelled)
}

private fun recordingStatusAccent(status: RecordingStatus): Color = when (status) {
    RecordingStatus.RECORDING -> Primary
    RecordingStatus.SCHEDULED -> Secondary
    RecordingStatus.COMPLETED -> Color(0xFF7BA7FF)
    RecordingStatus.FAILED -> ErrorColor
    RecordingStatus.CANCELLED -> OnSurfaceDim
}

@Composable
internal fun RecordingItemCard(
    item: RecordingItem,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSkipOccurrence: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onToggleSchedule: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.programTitle ?: item.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.programTitle != null && item.programTitle != item.channelName) {
                        Text(
                            item.channelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val appTimeFormat = LocalAppTimeFormat.current
                val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
                Text(
                    text = stringResource(
                        R.string.settings_recording_time_window,
                        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
                        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.recurrence != RecordingRecurrence.NONE) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (item.recurrence) {
                            RecordingRecurrence.DAILY -> stringResource(R.string.settings_recording_recurrence_daily)
                            RecordingRecurrence.WEEKLY -> stringResource(R.string.settings_recording_recurrence_weekly)
                            RecordingRecurrence.NONE -> stringResource(R.string.settings_recording_recurrence_none)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Secondary,
                        maxLines = 1
                    )
                }
            }
            item.failureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4
            ) {
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_source_label),
                    value = formatRecordingSourceType(item.sourceType)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_bytes_label),
                    value = formatBytes(item.bytesWritten)
                )
                RecordingMetaPill(
                    label = stringResource(R.string.settings_recording_speed_label),
                    value = if (item.averageThroughputBytesPerSecond > 0L) {
                        "${formatBytes(item.averageThroughputBytesPerSecond)}/s"
                    } else {
                        "N/A"
                    }
                )
                if (item.retryCount > 0) {
                    RecordingMetaPill(
                        label = stringResource(R.string.settings_recording_retry_count_label),
                        value = item.retryCount.toString()
                    )
                }
            }
            item.outputDisplayPath?.takeIf { it.isNotBlank() }?.let { output ->
                Text(
                    text = "${stringResource(R.string.settings_recording_output_label)}: ${summarizeRecordingOutputPath(output)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.failureCategory != RecordingFailureCategory.NONE) {
                Text(
                    text = "${stringResource(R.string.settings_recording_failure_label)}: ${formatRecordingFailureCategory(item.failureCategory)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ErrorColor
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4
            ) {
                if (item.status == RecordingStatus.COMPLETED && (!item.outputUri.isNullOrBlank() || !item.outputPath.isNullOrBlank())) {
                    CompactRecordingActionChip(
                        label = "Play",
                        accent = Primary,
                        onClick = onPlay
                    )
                }
                if (item.status == RecordingStatus.RECORDING) {
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_stop),
                        accent = ErrorColor,
                        onClick = onStop
                    )
                }
                if (item.status == RecordingStatus.SCHEDULED) {
                    CompactRecordingActionChip(
                        label = stringResource(
                            if (item.scheduleEnabled) R.string.settings_recording_disable
                            else R.string.settings_recording_enable
                        ),
                        accent = Secondary,
                        onClick = { onToggleSchedule(!item.scheduleEnabled) }
                    )
                    if (item.recurringRuleId != null) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_skip),
                            accent = OnBackground,
                            onClick = onSkipOccurrence
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_cancel),
                        accent = OnBackground,
                        onClick = onCancel
                    )
                }
                if (item.status == RecordingStatus.COMPLETED || item.status == RecordingStatus.FAILED || item.status == RecordingStatus.CANCELLED) {
                    if (item.status == RecordingStatus.FAILED) {
                        CompactRecordingActionChip(
                            label = stringResource(R.string.settings_recording_retry),
                            accent = Primary,
                            onClick = onRetry
                        )
                    }
                    CompactRecordingActionChip(
                        label = stringResource(R.string.settings_recording_delete),
                        accent = OnBackground,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
internal fun CompactRecordingActionChip(label: String, accent: Color, onClick: () -> Unit) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = 0.14f),
            focusedContainerColor = accent.copy(alpha = 0.3f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

internal fun RecordingItem.playbackUrl(): String? {
    val persistedUri = outputUri?.trim()?.takeIf { it.isNotBlank() }
    if (persistedUri != null) {
        return persistedUri
    }
    val localPath = outputPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { android.net.Uri.parse(localPath) }.getOrNull()
    return if (parsed?.scheme.isNullOrBlank()) {
        android.net.Uri.fromFile(java.io.File(localPath)).toString()
    } else {
        localPath
    }
}
