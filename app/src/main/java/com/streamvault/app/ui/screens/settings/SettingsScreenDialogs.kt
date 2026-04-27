package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.AppTimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreenDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    scope: CoroutineScope,
    showLiveTvModeDialog: Boolean,
    onShowLiveTvModeDialogChange: (Boolean) -> Unit,
    showLiveTvQuickFilterVisibilityDialog: Boolean,
    onShowLiveTvQuickFilterVisibilityDialogChange: (Boolean) -> Unit,
    showLiveChannelNumberingDialog: Boolean,
    onShowLiveChannelNumberingDialogChange: (Boolean) -> Unit,
    showLiveChannelGroupingDialog: Boolean,
    onShowLiveChannelGroupingDialogChange: (Boolean) -> Unit,
    showGroupedChannelLabelDialog: Boolean,
    onShowGroupedChannelLabelDialogChange: (Boolean) -> Unit,
    showLiveVariantPreferenceDialog: Boolean,
    onShowLiveVariantPreferenceDialogChange: (Boolean) -> Unit,
    showVodViewModeDialog: Boolean,
    onShowVodViewModeDialogChange: (Boolean) -> Unit,
    showGuideDefaultCategoryDialog: Boolean,
    onShowGuideDefaultCategoryDialogChange: (Boolean) -> Unit,
    showPlaybackSpeedDialog: Boolean,
    onShowPlaybackSpeedDialogChange: (Boolean) -> Unit,
    showAudioVideoOffsetDialog: Boolean,
    onShowAudioVideoOffsetDialogChange: (Boolean) -> Unit,
    showDecoderModeDialog: Boolean,
    onShowDecoderModeDialogChange: (Boolean) -> Unit,
    showSurfaceModeDialog: Boolean,
    onShowSurfaceModeDialogChange: (Boolean) -> Unit,
    showTimeshiftDepthDialog: Boolean,
    onShowTimeshiftDepthDialogChange: (Boolean) -> Unit,
    showDefaultStopTimerDialog: Boolean,
    onShowDefaultStopTimerDialogChange: (Boolean) -> Unit,
    showDefaultIdleTimerDialog: Boolean,
    onShowDefaultIdleTimerDialogChange: (Boolean) -> Unit,
    showControlsTimeoutDialog: Boolean,
    onShowControlsTimeoutDialogChange: (Boolean) -> Unit,
    showLiveOverlayTimeoutDialog: Boolean,
    onShowLiveOverlayTimeoutDialogChange: (Boolean) -> Unit,
    showNoticeTimeoutDialog: Boolean,
    onShowNoticeTimeoutDialogChange: (Boolean) -> Unit,
    showDiagnosticsTimeoutDialog: Boolean,
    onShowDiagnosticsTimeoutDialogChange: (Boolean) -> Unit,
    showLiveTvFiltersDialog: Boolean,
    onShowLiveTvFiltersDialogChange: (Boolean) -> Unit,
    showAudioLanguageDialog: Boolean,
    onShowAudioLanguageDialogChange: (Boolean) -> Unit,
    showSubtitleSizeDialog: Boolean,
    onShowSubtitleSizeDialogChange: (Boolean) -> Unit,
    showSubtitleTextColorDialog: Boolean,
    onShowSubtitleTextColorDialogChange: (Boolean) -> Unit,
    showSubtitleBackgroundDialog: Boolean,
    onShowSubtitleBackgroundDialogChange: (Boolean) -> Unit,
    showWifiQualityDialog: Boolean,
    onShowWifiQualityDialogChange: (Boolean) -> Unit,
    showEthernetQualityDialog: Boolean,
    onShowEthernetQualityDialogChange: (Boolean) -> Unit,
    categorySortDialogType: String?,
    onCategorySortDialogTypeChange: (String?) -> Unit,
    showPinDialog: Boolean,
    onShowPinDialogChange: (Boolean) -> Unit,
    showLevelDialog: Boolean,
    onShowLevelDialogChange: (Boolean) -> Unit,
    pinError: String?,
    onPinErrorChange: (String?) -> Unit,
    pendingAction: ParentalAction?,
    onPendingActionChange: (ParentalAction?) -> Unit,
    pendingProtectionLevel: Int?,
    onPendingProtectionLevelChange: (Int?) -> Unit,
    showLanguageDialog: Boolean,
    onShowLanguageDialogChange: (Boolean) -> Unit,
    showTimeFormatDialog: Boolean,
    onShowTimeFormatDialogChange: (Boolean) -> Unit,
    showRecordingPatternDialog: Boolean,
    onShowRecordingPatternDialogChange: (Boolean) -> Unit,
    showRecordingRetentionDialog: Boolean,
    onShowRecordingRetentionDialogChange: (Boolean) -> Unit,
    showRecordingConcurrencyDialog: Boolean,
    onShowRecordingConcurrencyDialogChange: (Boolean) -> Unit,
    showRecordingPaddingDialog: Boolean,
    onShowRecordingPaddingDialogChange: (Boolean) -> Unit,
    showClearHistoryDialog: Boolean,
    onShowClearHistoryDialogChange: (Boolean) -> Unit,
    showCreateCombinedDialog: Boolean,
    onShowCreateCombinedDialogChange: (Boolean) -> Unit,
    showAddCombinedMemberDialog: Boolean,
    onShowAddCombinedMemberDialogChange: (Boolean) -> Unit,
    showRenameCombinedDialog: Boolean,
    onShowRenameCombinedDialogChange: (Boolean) -> Unit,
    selectedCombinedProfileId: Long?,
    showProviderSyncDialog: Boolean,
    onShowProviderSyncDialogChange: (Boolean) -> Unit,
    showCustomProviderSyncDialog: Boolean,
    onShowCustomProviderSyncDialogChange: (Boolean) -> Unit,
    pendingSyncProviderId: Long?,
    onPendingSyncProviderIdChange: (Long?) -> Unit,
    customSyncSelections: Set<ProviderSyncSelection>,
    onCustomSyncSelectionsChange: (Set<ProviderSyncSelection>) -> Unit
) {
    SyncingOverlay(
        isSyncing = uiState.isSyncing,
        providerName = uiState.syncingProviderName,
        progress = uiState.syncProgress
    )

    if (showLiveTvModeDialog) {
        LiveTvChannelModeDialog(
            selectedMode = uiState.liveTvChannelMode,
            onDismiss = { onShowLiveTvModeDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setLiveTvChannelMode(mode)
                onShowLiveTvModeDialogChange(false)
            }
        )
    }

    if (showLiveTvQuickFilterVisibilityDialog) {
        LiveTvQuickFilterVisibilityDialog(
            selectedMode = uiState.liveTvQuickFilterVisibilityMode,
            onDismiss = { onShowLiveTvQuickFilterVisibilityDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setLiveTvQuickFilterVisibilityMode(mode)
                onShowLiveTvQuickFilterVisibilityDialogChange(false)
            }
        )
    }

    if (showLiveChannelNumberingDialog) {
        LiveChannelNumberingModeDialog(
            selectedMode = uiState.liveChannelNumberingMode,
            onDismiss = { onShowLiveChannelNumberingDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setLiveChannelNumberingMode(mode)
                onShowLiveChannelNumberingDialogChange(false)
            }
        )
    }

    if (showLiveChannelGroupingDialog) {
        LiveChannelGroupingModeDialog(
            selectedMode = uiState.liveChannelGroupingMode,
            onDismiss = { onShowLiveChannelGroupingDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setLiveChannelGroupingMode(mode)
                onShowLiveChannelGroupingDialogChange(false)
            }
        )
    }

    if (showGroupedChannelLabelDialog) {
        GroupedChannelLabelModeDialog(
            selectedMode = uiState.groupedChannelLabelMode,
            onDismiss = { onShowGroupedChannelLabelDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setGroupedChannelLabelMode(mode)
                onShowGroupedChannelLabelDialogChange(false)
            }
        )
    }

    if (showLiveVariantPreferenceDialog) {
        LiveVariantPreferenceModeDialog(
            selectedMode = uiState.liveVariantPreferenceMode,
            onDismiss = { onShowLiveVariantPreferenceDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setLiveVariantPreferenceMode(mode)
                onShowLiveVariantPreferenceDialogChange(false)
            }
        )
    }

    if (showVodViewModeDialog) {
        VodViewModeDialog(
            selectedMode = uiState.vodViewMode,
            onDismiss = { onShowVodViewModeDialogChange(false) },
            onModeSelected = { mode ->
                viewModel.setVodViewMode(mode)
                onShowVodViewModeDialogChange(false)
            }
        )
    }

    if (showGuideDefaultCategoryDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_guide_default_category),
            onDismiss = { onShowGuideDefaultCategoryDialogChange(false) }
        ) {
            uiState.guideDefaultCategoryOptions.forEachIndexed { index, category ->
                LevelOption(
                    level = index,
                    text = category.name,
                    currentLevel = if (uiState.guideDefaultCategoryId == category.id) index else -1,
                    onSelect = {
                        viewModel.setGuideDefaultCategory(category.id)
                        onShowGuideDefaultCategoryDialogChange(false)
                    }
                )
            }
        }
    }

    if (showPlaybackSpeedDialog) {
        val speedOptions = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_playback_speed),
            onDismiss = { onShowPlaybackSpeedDialogChange(false) }
        ) {
            speedOptions.forEachIndexed { index, speed ->
                LevelOption(
                    level = index,
                    text = formatPlaybackSpeedLabel(speed),
                    currentLevel = if (speed == uiState.playerPlaybackSpeed) index else -1,
                    onSelect = {
                        viewModel.setDefaultPlaybackSpeed(speed)
                        onShowPlaybackSpeedDialogChange(false)
                    }
                )
            }
        }
    }

    if (showTimeFormatDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_time_format),
            onDismiss = { onShowTimeFormatDialogChange(false) }
        ) {
            AppTimeFormat.entries.forEachIndexed { index, format ->
                LevelOption(
                    level = index,
                    text = context.getString(format.labelResId()),
                    currentLevel = if (uiState.appTimeFormat == format) index else -1,
                    onSelect = {
                        viewModel.setAppTimeFormat(format)
                        onShowTimeFormatDialogChange(false)
                    }
                )
            }
        }
    }

    if (showAudioVideoOffsetDialog) {
        AudioVideoOffsetValueDialog(
            title = stringResource(R.string.settings_audio_video_sync_default),
            subtitle = stringResource(R.string.settings_audio_video_sync_default_subtitle),
            initialValue = uiState.playerAudioVideoOffsetMs,
            onDismiss = { onShowAudioVideoOffsetDialogChange(false) },
            onConfirm = { offsetMs ->
                viewModel.setPlayerAudioVideoOffsetMs(offsetMs)
                onShowAudioVideoOffsetDialogChange(false)
            }
        )
    }

    if (showDecoderModeDialog) {
        val decoderOptions = remember(context) {
            listOf(
                DecoderMode.AUTO to context.getString(R.string.settings_decoder_auto),
                DecoderMode.HARDWARE to context.getString(R.string.settings_decoder_hardware),
                DecoderMode.SOFTWARE to context.getString(R.string.settings_decoder_software),
                DecoderMode.COMPATIBILITY to context.getString(R.string.settings_decoder_compatibility)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_decoder_mode),
            onDismiss = { onShowDecoderModeDialogChange(false) }
        ) {
            decoderOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerDecoderMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerDecoderMode(option.first)
                        onShowDecoderModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSurfaceModeDialog) {
        val surfaceOptions = remember(context) {
            listOf(
                com.streamvault.domain.model.PlayerSurfaceMode.AUTO to context.getString(R.string.settings_surface_auto),
                com.streamvault.domain.model.PlayerSurfaceMode.SURFACE_VIEW to context.getString(R.string.settings_surface_surface_view),
                com.streamvault.domain.model.PlayerSurfaceMode.TEXTURE_VIEW to context.getString(R.string.settings_surface_texture_view)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_surface_mode),
            onDismiss = { onShowSurfaceModeDialogChange(false) }
        ) {
            surfaceOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerSurfaceMode == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerSurfaceMode(option.first)
                        onShowSurfaceModeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showControlsTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_controls_timeout),
            subtitle = stringResource(R.string.settings_timeout_vod_controls_subtitle),
            initialValue = uiState.playerControlsTimeoutSeconds,
            onDismiss = { onShowControlsTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerControlsTimeoutSeconds(seconds)
            onShowControlsTimeoutDialogChange(false)
        }
    }

    if (showLiveOverlayTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_live_overlay_timeout),
            subtitle = stringResource(R.string.settings_timeout_live_overlays_subtitle),
            initialValue = uiState.playerLiveOverlayTimeoutSeconds,
            onDismiss = { onShowLiveOverlayTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerLiveOverlayTimeoutSeconds(seconds)
            onShowLiveOverlayTimeoutDialogChange(false)
        }
    }

    if (showNoticeTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_notice_timeout),
            subtitle = stringResource(R.string.settings_timeout_notices_subtitle),
            initialValue = uiState.playerNoticeTimeoutSeconds,
            onDismiss = { onShowNoticeTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerNoticeTimeoutSeconds(seconds)
            onShowNoticeTimeoutDialogChange(false)
        }
    }

    if (showDiagnosticsTimeoutDialog) {
        TimeoutValueDialog(
            title = stringResource(R.string.settings_player_diagnostics_timeout),
            subtitle = stringResource(R.string.settings_timeout_diagnostics_subtitle),
            initialValue = uiState.playerDiagnosticsTimeoutSeconds,
            onDismiss = { onShowDiagnosticsTimeoutDialogChange(false) }
        ) { seconds ->
            viewModel.setPlayerDiagnosticsTimeoutSeconds(seconds)
            onShowDiagnosticsTimeoutDialogChange(false)
        }
    }

    if (showTimeshiftDepthDialog) {
        val depthOptions = remember(context) {
            listOf(
                15 to context.getString(R.string.settings_live_timeshift_depth_15),
                30 to context.getString(R.string.settings_live_timeshift_depth_30),
                60 to context.getString(R.string.settings_live_timeshift_depth_60)
            )
        }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_live_timeshift_depth),
            onDismiss = { onShowTimeshiftDepthDialogChange(false) }
        ) {
            depthOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.second,
                    currentLevel = if (uiState.playerTimeshiftDepthMinutes == option.first) index else -1,
                    onSelect = {
                        viewModel.setPlayerTimeshiftDepthMinutes(option.first)
                        onShowTimeshiftDepthDialogChange(false)
                    }
                )
            }
        }
    }

    if (showDefaultStopTimerDialog) {
        PlaybackTimerPresetDialog(
            title = stringResource(R.string.settings_default_stop_timer),
            selectedMinutes = uiState.defaultStopPlaybackTimerMinutes,
            onDismiss = { onShowDefaultStopTimerDialogChange(false) },
            onSelect = { minutes ->
                viewModel.setDefaultStopPlaybackTimerMinutes(minutes)
                onShowDefaultStopTimerDialogChange(false)
            }
        )
    }

    if (showDefaultIdleTimerDialog) {
        PlaybackTimerPresetDialog(
            title = stringResource(R.string.settings_default_idle_standby_timer),
            selectedMinutes = uiState.defaultIdleStandbyTimerMinutes,
            onDismiss = { onShowDefaultIdleTimerDialogChange(false) },
            onSelect = { minutes ->
                viewModel.setDefaultIdleStandbyTimerMinutes(minutes)
                onShowDefaultIdleTimerDialogChange(false)
            }
        )
    }

    if (showLiveTvFiltersDialog) {
        LiveTvQuickFiltersDialog(
            filters = uiState.liveTvCategoryFilters,
            onDismiss = { onShowLiveTvFiltersDialogChange(false) },
            onAddFilter = viewModel::addLiveTvCategoryFilter,
            onRemoveFilter = viewModel::removeLiveTvCategoryFilter
        )
    }

    if (showAudioLanguageDialog) {
        val autoLabel = stringResource(R.string.settings_audio_language_auto)
        val audioLanguageOptions = remember(autoLabel) { supportedAudioLanguages(autoLabel) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_audio_language),
            onDismiss = { onShowAudioLanguageDialogChange(false) }
        ) {
            audioLanguageOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.preferredAudioLanguage == option.tag) index else -1,
                    onSelect = {
                        viewModel.setPreferredAudioLanguage(option.tag)
                        onShowAudioLanguageDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleSizeDialog) {
        val subtitleSizeOptions = remember { subtitleSizeOptions() }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_size),
            onDismiss = { onShowSubtitleSizeDialogChange(false) }
        ) {
            subtitleSizeOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label(context),
                    currentLevel = if (uiState.subtitleTextScale == option.scale) index else -1,
                    onSelect = {
                        viewModel.setSubtitleTextScale(option.scale)
                        onShowSubtitleSizeDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleTextColorDialog) {
        val options = remember(context) { subtitleTextColorOptions(context) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_text_color),
            onDismiss = { onShowSubtitleTextColorDialogChange(false) }
        ) {
            options.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.subtitleTextColor == option.colorArgb) index else -1,
                    onSelect = {
                        viewModel.setSubtitleTextColor(option.colorArgb)
                        onShowSubtitleTextColorDialogChange(false)
                    }
                )
            }
        }
    }

    if (showSubtitleBackgroundDialog) {
        val options = remember(context) { subtitleBackgroundColorOptions(context) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_subtitle_background),
            onDismiss = { onShowSubtitleBackgroundDialogChange(false) }
        ) {
            options.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.subtitleBackgroundColor == option.colorArgb) index else -1,
                    onSelect = {
                        viewModel.setSubtitleBackgroundColor(option.colorArgb)
                        onShowSubtitleBackgroundDialogChange(false)
                    }
                )
            }
        }
    }

    if (showWifiQualityDialog) {
        QualityCapSelectionDialog(
            title = stringResource(R.string.settings_select_wifi_quality_cap),
            currentValue = uiState.wifiMaxVideoHeight,
            onDismiss = { onShowWifiQualityDialogChange(false) },
            onSelect = {
                viewModel.setWifiQualityCap(it)
                onShowWifiQualityDialogChange(false)
            }
        )
    }

    if (showEthernetQualityDialog) {
        QualityCapSelectionDialog(
            title = stringResource(R.string.settings_select_ethernet_quality_cap),
            currentValue = uiState.ethernetMaxVideoHeight,
            onDismiss = { onShowEthernetQualityDialogChange(false) },
            onSelect = {
                viewModel.setEthernetQualityCap(it)
                onShowEthernetQualityDialogChange(false)
            }
        )
    }

    categorySortDialogType?.let { typeName ->
        val type = ContentType.entries.firstOrNull { it.name == typeName }
        if (type != null) {
            CategorySortModeDialog(
                type = type,
                currentMode = uiState.categorySortModes[type] ?: CategorySortMode.DEFAULT,
                onDismiss = { onCategorySortDialogTypeChange(null) },
                onModeSelected = { mode ->
                    viewModel.setCategorySortMode(type, mode)
                    onCategorySortDialogTypeChange(null)
                }
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismissRequest = {
                onShowPinDialogChange(false)
                onPinErrorChange(null)
                if (pendingAction == ParentalAction.SetNewPin) {
                    onPendingActionChange(null)
                    onPendingProtectionLevelChange(null)
                }
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (pendingAction == ParentalAction.SetNewPin) {
                        viewModel.changePin(pin)
                        pendingProtectionLevel?.let(viewModel::setParentalControlLevel)
                        onPendingProtectionLevelChange(null)
                        onShowPinDialogChange(false)
                        onPendingActionChange(null)
                    } else {
                        if (viewModel.verifyPin(pin)) {
                            onShowPinDialogChange(false)
                            onPinErrorChange(null)
                            when (pendingAction) {
                                ParentalAction.ChangeLevel -> onShowLevelDialogChange(true)
                                ParentalAction.ChangePin -> {
                                    onPendingActionChange(ParentalAction.SetNewPin)
                                    onShowPinDialogChange(true)
                                }
                                else -> onPendingActionChange(null)
                            }
                        } else {
                            onPinErrorChange(context.getString(R.string.home_incorrect_pin))
                        }
                    }
                }
            },
            title = if (pendingAction == ParentalAction.SetNewPin) {
                stringResource(R.string.settings_enter_new_pin)
            } else {
                stringResource(R.string.settings_enter_pin)
            },
            error = pinError
        )
    }

    if (showLevelDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_level),
            onDismiss = { onShowLevelDialogChange(false) }
        ) {
            LevelOption(
                level = 0,
                text = stringResource(R.string.settings_level_off_desc),
                subtitle = stringResource(R.string.settings_level_off_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                viewModel.setParentalControlLevel(0)
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 1,
                text = stringResource(R.string.settings_level_locked_desc),
                subtitle = stringResource(R.string.settings_level_locked_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(1)
                } else {
                    onPendingProtectionLevelChange(1)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 2,
                text = stringResource(R.string.settings_level_private_desc),
                subtitle = stringResource(R.string.settings_level_private_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(2)
                } else {
                    onPendingProtectionLevelChange(2)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 3,
                text = stringResource(R.string.settings_level_hidden_desc),
                subtitle = stringResource(R.string.settings_level_hidden_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(3)
                } else {
                    onPendingProtectionLevelChange(3)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
        }
    }

    if (showLanguageDialog) {
        val systemDefaultLabel = stringResource(R.string.settings_system_default)
        val languageOptions = remember(systemDefaultLabel) { supportedAppLanguages(systemDefaultLabel) }
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_language),
            onDismiss = { onShowLanguageDialogChange(false) }
        ) {
            languageOptions.forEachIndexed { index, option ->
                LevelOption(
                    level = index,
                    text = option.label,
                    currentLevel = if (uiState.appLanguage == option.tag) index else -1,
                    onSelect = {
                        viewModel.setAppLanguage(option.tag)
                        onShowLanguageDialogChange(false)
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
            onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
            onConfirm = { viewModel.confirmBackupImport() }
        )
    }

    if (showRecordingPatternDialog) {
        SimpleTextValueDialog(
            title = stringResource(R.string.settings_recording_pattern_title),
            subtitle = stringResource(R.string.settings_recording_pattern_hint),
            initialValue = uiState.recordingStorageState.fileNamePattern,
            onDismiss = { onShowRecordingPatternDialogChange(false) },
            onConfirm = { pattern ->
                viewModel.updateRecordingFileNamePattern(pattern)
                onShowRecordingPatternDialogChange(false)
            }
        )
    }

    if (showRecordingRetentionDialog) {
        val retentionOptions = listOf<Int?>(null, 7, 14, 30, 60, 90)
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_retention_title),
            onDismiss = { onShowRecordingRetentionDialogChange(false) }
        ) {
            retentionOptions.forEachIndexed { index, days ->
                LevelOption(
                    level = index,
                    text = if (days == null) {
                        stringResource(R.string.settings_recording_retention_keep_all)
                    } else {
                        stringResource(R.string.settings_recording_retention_days, days)
                    },
                    currentLevel = if (days == uiState.recordingStorageState.retentionDays) index else -1,
                    onSelect = {
                        viewModel.updateRecordingRetentionDays(days)
                        onShowRecordingRetentionDialogChange(false)
                    }
                )
            }
        }
    }

    if (showRecordingConcurrencyDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_concurrency_title),
            onDismiss = { onShowRecordingConcurrencyDialogChange(false) }
        ) {
            (1..4).forEach { value ->
                LevelOption(
                    level = value,
                    text = value.toString(),
                    currentLevel = if (value == uiState.recordingStorageState.maxSimultaneousRecordings) value else -1,
                    onSelect = {
                        viewModel.updateRecordingMaxSimultaneous(value)
                        onShowRecordingConcurrencyDialogChange(false)
                    }
                )
            }
        }
    }

    if (showRecordingPaddingDialog) {
        val paddingOptions = listOf(0, 1, 2, 3, 5, 10, 15, 30)
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_recording_padding_title),
            onDismiss = { onShowRecordingPaddingDialogChange(false) }
        ) {
            Text(
                text = stringResource(R.string.settings_recording_padding_before),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            paddingOptions.forEach { minutes ->
                LevelOption(
                    level = minutes,
                    text = if (minutes == 0) {
                        stringResource(R.string.settings_recording_padding_none)
                    } else {
                        stringResource(R.string.settings_recording_padding_minutes, minutes)
                    },
                    currentLevel = uiState.recordingPaddingBeforeMinutes,
                    onSelect = {
                        viewModel.setRecordingPaddingBeforeMinutes(minutes)
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_recording_padding_after),
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            paddingOptions.forEach { minutes ->
                LevelOption(
                    level = minutes,
                    text = if (minutes == 0) {
                        stringResource(R.string.settings_recording_padding_none)
                    } else {
                        stringResource(R.string.settings_recording_padding_minutes, minutes)
                    },
                    currentLevel = uiState.recordingPaddingAfterMinutes,
                    onSelect = {
                        viewModel.setRecordingPaddingAfterMinutes(minutes)
                    }
                )
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { onShowClearHistoryDialogChange(false) },
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
                        onShowClearHistoryDialogChange(false)
                    }
                ) {
                    Text(text = stringResource(R.string.settings_clear_history_confirm), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowClearHistoryDialogChange(false) }) {
                    Text(text = stringResource(R.string.settings_cancel), color = OnSurface)
                }
            },
            containerColor = SurfaceElevated,
            titleContentColor = OnSurface,
            textContentColor = TextSecondary
        )
    }

    val pendingSyncProvider = pendingSyncProviderId?.let { providerId ->
        uiState.providers.firstOrNull { it.id == providerId }
    }
    if (showCreateCombinedDialog) {
        CreateCombinedM3uDialog(
            providers = uiState.availableM3uProviders,
            onDismiss = { onShowCreateCombinedDialogChange(false) },
            onCreate = { name, providerIds ->
                onShowCreateCombinedDialogChange(false)
                viewModel.createCombinedProfile(name, providerIds)
            }
        )
    }
    if (showRenameCombinedDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == selectedCombinedProfileId }
        if (selectedProfile != null) {
            RenameCombinedM3uDialog(
                profile = selectedProfile,
                onDismiss = { onShowRenameCombinedDialogChange(false) },
                onRename = { name ->
                    onShowRenameCombinedDialogChange(false)
                    viewModel.renameCombinedProfile(selectedProfile.id, name)
                }
            )
        }
    }
    if (showAddCombinedMemberDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == selectedCombinedProfileId }
        if (selectedProfile != null) {
            AddCombinedProviderDialog(
                profile = selectedProfile,
                availableProviders = uiState.availableM3uProviders,
                onDismiss = { onShowAddCombinedMemberDialogChange(false) },
                onAddProvider = { providerId ->
                    onShowAddCombinedMemberDialogChange(false)
                    viewModel.addProviderToCombinedProfile(selectedProfile.id, providerId)
                }
            )
        }
    }
    if (showProviderSyncDialog && pendingSyncProvider != null) {
        ProviderSyncOptionsDialog(
            provider = pendingSyncProvider,
            onDismiss = {
                onShowProviderSyncDialogChange(false)
                onPendingSyncProviderIdChange(null)
            },
            onSelect = { selection ->
                onShowProviderSyncDialogChange(false)
                if (selection == null) {
                    onShowCustomProviderSyncDialogChange(true)
                } else {
                    viewModel.syncProviderSection(pendingSyncProvider.id, selection)
                    onPendingSyncProviderIdChange(null)
                }
            }
        )
    }
    if (showCustomProviderSyncDialog && pendingSyncProvider != null) {
        ProviderCustomSyncDialog(
            provider = pendingSyncProvider,
            selected = customSyncSelections,
            onToggle = { option ->
                onCustomSyncSelectionsChange(
                    if (option in customSyncSelections) {
                        customSyncSelections - option
                    } else {
                        customSyncSelections + option
                    }
                )
            },
            onDismiss = {
                onShowCustomProviderSyncDialogChange(false)
                onPendingSyncProviderIdChange(null)
            },
            onConfirm = {
                onShowCustomProviderSyncDialogChange(false)
                viewModel.syncProviderCustom(pendingSyncProvider.id, customSyncSelections)
                onPendingSyncProviderIdChange(null)
            }
        )
    }
}

private fun AppTimeFormat.labelResId(): Int = when (this) {
    AppTimeFormat.SYSTEM -> R.string.settings_time_format_system
    AppTimeFormat.TWELVE_HOUR -> R.string.settings_time_format_12h
    AppTimeFormat.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
}

@Composable
private fun SyncingOverlay(
    isSyncing: Boolean,
    providerName: String? = null,
    progress: String? = null
) {
    if (!isSyncing) return

    BackHandler(enabled = true) {}

    val overlayFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { overlayFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = true, onClick = {})
            .focusRequester(overlayFocusRequester)
            .focusable()
            .onKeyEvent { true },
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
                text = providerName ?: stringResource(R.string.settings_syncing_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            progress?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}
