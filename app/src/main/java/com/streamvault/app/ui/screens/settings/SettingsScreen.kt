package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.app.util.OfficialBuildStatus
import com.streamvault.app.util.OfficialBuildVerifier

import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.localization.localeForLanguageTag
import com.streamvault.app.localization.supportedAppLanguageTags
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.app.ui.screens.settings.ProviderDiagnosticsUiModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import com.streamvault.app.R
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.Routes
import java.util.Locale
import com.streamvault.app.BuildConfig
import com.streamvault.app.ui.screens.settings.AppUpdateUiModel
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.interaction.mouseClickable
import java.text.DateFormat
import com.streamvault.app.ui.design.FocusSpec
import kotlinx.coroutines.delay


@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    currentRoute: String,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsNavFocusRequester = remember { FocusRequester() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainActivity = context.findMainActivity()
    val officialBuildVerification = remember(context.packageName) { OfficialBuildVerifier.verify(context) }
    val buildVerificationLabel = remember(officialBuildVerification.status, context) {
        formatOfficialBuildStatusLabel(officialBuildVerification.status, context)
    }
    val appLanguageLabel = remember(uiState.appLanguage, context) {
        displayLanguageLabel(uiState.appLanguage, context.getString(R.string.settings_system_default))
    }
    val timeFormatLabel = remember(uiState.appTimeFormat, context) {
        formatAppTimeFormatLabel(uiState.appTimeFormat, context)
    }
    val dateTimeFormat = remember(uiState.appTimeFormat) { uiState.appTimeFormat.createDateTimeFormat() }
    val preferredAudioLanguageLabel = remember(uiState.preferredAudioLanguage, context) {
        displayLanguageLabel(uiState.preferredAudioLanguage, context.getString(R.string.settings_audio_language_auto))
    }
    val playbackSpeedLabel = remember(uiState.playerPlaybackSpeed) {
        formatPlaybackSpeedLabel(uiState.playerPlaybackSpeed)
    }
    val audioVideoOffsetLabel = remember(uiState.playerAudioVideoOffsetMs) {
        formatAudioVideoOffsetLabel(uiState.playerAudioVideoOffsetMs)
    }
    val decoderModeLabel = remember(uiState.playerDecoderMode, context) {
        formatDecoderModeLabel(uiState.playerDecoderMode, context)
    }
    val surfaceModeLabel = remember(uiState.playerSurfaceMode, context) {
        formatSurfaceModeLabel(uiState.playerSurfaceMode, context)
    }
    val controlsTimeoutLabel = remember(uiState.playerControlsTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerControlsTimeoutSeconds, context)
    }
    val liveOverlayTimeoutLabel = remember(uiState.playerLiveOverlayTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerLiveOverlayTimeoutSeconds, context)
    }
    val noticeTimeoutLabel = remember(uiState.playerNoticeTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerNoticeTimeoutSeconds, context)
    }
    val diagnosticsTimeoutLabel = remember(uiState.playerDiagnosticsTimeoutSeconds, context) {
        formatTimeoutSecondsLabel(uiState.playerDiagnosticsTimeoutSeconds, context)
    }
    val subtitleSizeLabel = remember(uiState.subtitleTextScale, context) {
        formatSubtitleSizeLabel(uiState.subtitleTextScale, context)
    }
    val subtitleTextColorLabel = remember(uiState.subtitleTextColor, context) {
        formatSubtitleColorLabel(uiState.subtitleTextColor, subtitleTextColorOptions(context))
    }
    val subtitleBackgroundLabel = remember(uiState.subtitleBackgroundColor, context) {
        formatSubtitleColorLabel(uiState.subtitleBackgroundColor, subtitleBackgroundColorOptions(context))
    }
    val wifiQualityLabel = remember(uiState.wifiMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.wifiMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val ethernetQualityLabel = remember(uiState.ethernetMaxVideoHeight, context) {
        formatQualityCapLabel(uiState.ethernetMaxVideoHeight, context.getString(R.string.settings_quality_cap_auto))
    }
    val timeshiftDepthLabel = remember(uiState.playerTimeshiftDepthMinutes, context) {
        formatTimeshiftDepthLabel(uiState.playerTimeshiftDepthMinutes, context)
    }
    val defaultStopTimerLabel = remember(uiState.defaultStopPlaybackTimerMinutes, context) {
        formatPlaybackTimerMinutesLabel(uiState.defaultStopPlaybackTimerMinutes, context)
    }
    val defaultIdleTimerLabel = remember(uiState.defaultIdleStandbyTimerMinutes, context) {
        formatPlaybackTimerMinutesLabel(uiState.defaultIdleStandbyTimerMinutes, context)
    }
    val lastSpeedTestLabel = remember(uiState.lastSpeedTest) {
        uiState.lastSpeedTest?.let(::formatSpeedTestValueLabel)
            ?: context.getString(R.string.settings_speed_test_not_run)
    }
    val lastSpeedTestSummary = remember(uiState.lastSpeedTest, context, dateTimeFormat) {
        uiState.lastSpeedTest?.let { formatSpeedTestSummary(it, context, dateTimeFormat) }
            ?: context.getString(R.string.settings_speed_test_summary_default)
    }
    val speedTestRecommendationLabel = remember(uiState.lastSpeedTest, context) {
        formatQualityCapLabel(
            uiState.lastSpeedTest?.recommendedMaxVideoHeight,
            context.getString(R.string.settings_quality_cap_auto)
        )
    }
    val protectionSummary = remember(uiState.parentalControlLevel, context) {
        when (uiState.parentalControlLevel) {
            0 -> context.getString(R.string.settings_level_off)
            1 -> context.getString(R.string.settings_level_locked)
            2 -> context.getString(R.string.settings_level_private)
            3 -> context.getString(R.string.settings_level_hidden)
            else -> context.getString(R.string.settings_level_unknown)
        }
    }
    val guideDefaultCategoryLabel = remember(
        uiState.guideDefaultCategoryId,
        uiState.guideDefaultCategoryOptions,
        context
    ) {
        uiState.guideDefaultCategoryOptions
            .firstOrNull { it.id == uiState.guideDefaultCategoryId }
            ?.name
            ?: context.getString(R.string.settings_guide_default_category_fallback)
    }

    // Parental Control State
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showLevelDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showTimeFormatDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTvModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTvQuickFilterVisibilityDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveChannelNumberingDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveChannelGroupingDialog by rememberSaveable { mutableStateOf(false) }
    var showGroupedChannelLabelDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveVariantPreferenceDialog by rememberSaveable { mutableStateOf(false) }
    var showVodViewModeDialog by rememberSaveable { mutableStateOf(false) }
    var showGuideDefaultCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var showPlaybackSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioVideoOffsetDialog by rememberSaveable { mutableStateOf(false) }
    var showDecoderModeDialog by rememberSaveable { mutableStateOf(false) }
    var showSurfaceModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTimeshiftDepthDialog by rememberSaveable { mutableStateOf(false) }
    var showDefaultStopTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showDefaultIdleTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showControlsTimeoutDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveOverlayTimeoutDialog by rememberSaveable { mutableStateOf(false) }
    var showNoticeTimeoutDialog by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticsTimeoutDialog by rememberSaveable { mutableStateOf(false) }
    var showLiveTvFiltersDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleSizeDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleTextColorDialog by rememberSaveable { mutableStateOf(false) }
    var showSubtitleBackgroundDialog by rememberSaveable { mutableStateOf(false) }
    var showWifiQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomProviderSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateCombinedDialog by rememberSaveable { mutableStateOf(false) }
    var showAddCombinedMemberDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameCombinedDialog by rememberSaveable { mutableStateOf(false) }
    var selectedCombinedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSyncProviderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var customSyncSelections by rememberSaveable {
        mutableStateOf(
            setOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.EPG
            )
        )
    }
    var showEthernetQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showRecordingPatternDialog by rememberSaveable { mutableStateOf(false) }
    var showRecordingRetentionDialog by rememberSaveable { mutableStateOf(false) }
    var showRecordingConcurrencyDialog by rememberSaveable { mutableStateOf(false) }
    var showRecordingPaddingDialog by rememberSaveable { mutableStateOf(false) }
    var showRecordingBrowserDialog by rememberSaveable { mutableStateOf(false) }
    var selectedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var categorySortDialogType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf(0) }
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

    val recordingFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = DocumentFile.fromTreeUri(context, it)?.name
            viewModel.updateRecordingFolder(it.toString(), displayName)
        }
    }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    LaunchedEffect(uiState.recordingItems) {
        selectedRecordingId = when {
            uiState.recordingItems.isEmpty() -> null
            selectedRecordingId == null -> uiState.recordingItems.first().id
            uiState.recordingItems.any { item -> item.id == selectedRecordingId } -> selectedRecordingId
            else -> uiState.recordingItems.first().id
        }
    }

    LaunchedEffect(currentRoute, selectedCategory) {
        delay(80)
        settingsNavFocusRequester.requestFocusSafely(tag = "SettingsScreen", target = "Selected settings section")
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
            Row(modifier = Modifier.fillMaxSize()) {
                // ג”€ג”€ Left navigation rail ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                LazyColumn(
                    modifier = Modifier
                        .width(236.dp)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentPadding = PaddingValues(top = 76.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_providers),
                            "P",
                            Primary,
                            selectedCategory == 0,
                            modifier = if (selectedCategory == 0) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 0 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_playback),
                            ">",
                            Color(0xFF9E8FFF),
                            selectedCategory == 1,
                            modifier = if (selectedCategory == 1) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 1 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_browsing),
                            "#",
                            Color(0xFF26A69A),
                            selectedCategory == 2,
                            modifier = if (selectedCategory == 2) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 2 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_privacy),
                            "L",
                            Color(0xFFFFB74D),
                            selectedCategory == 3,
                            modifier = if (selectedCategory == 3) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 3 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_recording_title),
                            "R",
                            Color(0xFFEF5350),
                            selectedCategory == 4,
                            modifier = if (selectedCategory == 4) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 4 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_backup_restore),
                            "B",
                            Color(0xFF42A5F5),
                            selectedCategory == 5,
                            modifier = if (selectedCategory == 5) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 5 }
                    }
                    item {
                        SettingsNavItem(
                            "EPG Sources",
                            "E",
                            Color(0xFF66BB6A),
                            selectedCategory == 6,
                            modifier = if (selectedCategory == 6) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 6 }
                    }
                    item {
                        SettingsNavItem(
                            stringResource(R.string.settings_about),
                            "i",
                            Color(0xFF78909C),
                            selectedCategory == 7,
                            modifier = if (selectedCategory == 7) Modifier.focusRequester(settingsNavFocusRequester) else Modifier
                        ) { selectedCategory = 7 }
                    }
                }

                // Thin vertical separator
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.07f))
                )

                // ג”€ג”€ Right content pane ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .imePadding(),
                    contentPadding = PaddingValues(start = 20.dp, top = 76.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = !uiState.isSyncing
                ) {
                    // ג”€ג”€ 0: Providers ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    if (selectedCategory == 0) {
                        providerSection(
                            uiState = uiState,
                            onAddProvider = onAddProvider,
                            onEditProvider = onEditProvider,
                            onNavigateToParentalControl = onNavigateToParentalControl,
                            viewModel = viewModel,
                            selectedCombinedProfileId = selectedCombinedProfileId,
                            onSelectedCombinedProfileIdChange = { selectedCombinedProfileId = it },
                            onPendingSyncProviderIdChange = { pendingSyncProviderId = it },
                            onCustomSyncSelectionsChange = { customSyncSelections = it },
                            onShowProviderSyncDialogChange = { showProviderSyncDialog = it },
                            onShowCreateCombinedDialogChange = { showCreateCombinedDialog = it },
                            onShowRenameCombinedDialogChange = { showRenameCombinedDialog = it },
                            onShowAddCombinedMemberDialogChange = { showAddCombinedMemberDialog = it }
                        )
                    }

                    // ג”€ג”€ 1: Playback ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 1) {
                        item {
                            TvClickableSurface(
                                onClick = { viewModel.setPreventStandbyDuringPlayback(!uiState.preventStandbyDuringPlayback) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_prevent_standby), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_prevent_standby_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.preventStandbyDuringPlayback, onCheckedChange = { viewModel.setPreventStandbyDuringPlayback(it) })
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { viewModel.setAutoPlayNextEpisode(!uiState.autoPlayNextEpisode) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_auto_play_next_episode), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_auto_play_next_episode_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.autoPlayNextEpisode, onCheckedChange = { viewModel.setAutoPlayNextEpisode(it) })
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { viewModel.setPlayerMediaSessionEnabled(!uiState.playerMediaSessionEnabled) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_media_session), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_media_session_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.playerMediaSessionEnabled, onCheckedChange = { viewModel.setPlayerMediaSessionEnabled(it) })
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { viewModel.setPlayerTimeshiftEnabled(!uiState.playerTimeshiftEnabled) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_live_timeshift), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_live_timeshift_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.playerTimeshiftEnabled, onCheckedChange = { viewModel.setPlayerTimeshiftEnabled(it) })
                                }
                            }
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_timeshift_depth),
                                value = timeshiftDepthLabel,
                                onClick = { showTimeshiftDepthDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_timeshift_backend),
                                value = stringResource(R.string.settings_live_timeshift_backend_value),
                                onClick = {}
                            )
                            Text(
                                text = stringResource(R.string.settings_live_timeshift_backend_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { viewModel.setZapAutoRevert(!uiState.zapAutoRevert) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_zap_auto_revert), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_zap_auto_revert_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.zapAutoRevert, onCheckedChange = { viewModel.setZapAutoRevert(it) })
                                }
                            }
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_decoder_mode),
                                value = decoderModeLabel,
                                onClick = { showDecoderModeDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_surface_mode),
                                value = surfaceModeLabel,
                                onClick = { showSurfaceModeDialog = true }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_default_playback_speed),
                                value = playbackSpeedLabel,
                                onClick = { showPlaybackSpeedDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_default_stop_timer),
                                value = defaultStopTimerLabel,
                                onClick = { showDefaultStopTimerDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_default_idle_standby_timer),
                                value = defaultIdleTimerLabel,
                                onClick = { showDefaultIdleTimerDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_audio_video_sync_default),
                                value = audioVideoOffsetLabel,
                                onClick = { showAudioVideoOffsetDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_player_controls_timeout),
                                value = controlsTimeoutLabel,
                                onClick = { showControlsTimeoutDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_overlay_timeout),
                                value = liveOverlayTimeoutLabel,
                                onClick = { showLiveOverlayTimeoutDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_player_notice_timeout),
                                value = noticeTimeoutLabel,
                                onClick = { showNoticeTimeoutDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_player_diagnostics_timeout),
                                value = diagnosticsTimeoutLabel,
                                onClick = { showDiagnosticsTimeoutDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_preferred_audio_language),
                                value = preferredAudioLanguageLabel,
                                onClick = { showAudioLanguageDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_size),
                                value = subtitleSizeLabel,
                                onClick = { showSubtitleSizeDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_text_color),
                                value = subtitleTextColorLabel,
                                onClick = { showSubtitleTextColorDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_subtitle_background),
                                value = subtitleBackgroundLabel,
                                onClick = { showSubtitleBackgroundDialog = true }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_wifi_quality_cap),
                                value = wifiQualityLabel,
                                onClick = { showWifiQualityDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_ethernet_quality_cap),
                                value = ethernetQualityLabel,
                                onClick = { showEthernetQualityDialog = true }
                            )
                        }
                        item {
                            InternetSpeedTestCard(
                                valueLabel = lastSpeedTestLabel,
                                summary = lastSpeedTestSummary,
                                recommendationLabel = speedTestRecommendationLabel,
                                isRunning = uiState.isRunningInternetSpeedTest,
                                canApplyRecommendation = uiState.lastSpeedTest != null,
                                onRunTest = viewModel::runInternetSpeedTest,
                                onApplyWifi = viewModel::applySpeedTestRecommendationToWifi,
                                onApplyEthernet = viewModel::applySpeedTestRecommendationToEthernet
                            )
                        }
                    }

                    // ג”€ג”€ 2: Privacy & Parental ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 2) {
                        item {
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_tv_channel_mode),
                                value = stringResource(uiState.liveTvChannelMode.labelResId()),
                                onClick = { showLiveTvModeDialog = true }
                            )
                            TvClickableSurface(
                                onClick = { viewModel.setShowLiveSourceSwitcher(!uiState.showLiveSourceSwitcher) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_show_live_source_switcher), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_show_live_source_switcher_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.showLiveSourceSwitcher, onCheckedChange = { viewModel.setShowLiveSourceSwitcher(it) })
                                }
                            }
                            TvClickableSurface(
                                onClick = { viewModel.setShowAllChannelsCategory(!uiState.showAllChannelsCategory) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_show_all_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_show_all_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.showAllChannelsCategory, onCheckedChange = { viewModel.setShowAllChannelsCategory(it) })
                                }
                            }
                            TvClickableSurface(
                                onClick = { viewModel.setShowRecentChannelsCategory(!uiState.showRecentChannelsCategory) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_show_recent_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_show_recent_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.showRecentChannelsCategory, onCheckedChange = { viewModel.setShowRecentChannelsCategory(it) })
                                }
                            }
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_tv_quick_filters),
                                value = formatLiveTvQuickFiltersValue(uiState.liveTvCategoryFilters, context),
                                onClick = { showLiveTvFiltersDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_tv_quick_filter_visibility),
                                value = stringResource(uiState.liveTvQuickFilterVisibilityMode.labelResId()),
                                onClick = { showLiveTvQuickFilterVisibilityDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_channel_numbering_mode),
                                value = stringResource(uiState.liveChannelNumberingMode.labelResId()),
                                onClick = { showLiveChannelNumberingDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_channel_grouping_mode),
                                value = stringResource(uiState.liveChannelGroupingMode.labelResId()),
                                onClick = { showLiveChannelGroupingDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_grouped_channel_label_mode),
                                value = stringResource(uiState.groupedChannelLabelMode.labelResId()),
                                onClick = { showGroupedChannelLabelDialog = true },
                                enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
                                indent = 24.dp
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_live_variant_preference_mode),
                                value = stringResource(uiState.liveVariantPreferenceMode.labelResId()),
                                onClick = { showLiveVariantPreferenceDialog = true },
                                enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
                                indent = 24.dp
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_guide_default_category),
                                value = guideDefaultCategoryLabel,
                                onClick = { showGuideDefaultCategoryDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_time_format),
                                value = timeFormatLabel,
                                onClick = { showTimeFormatDialog = true }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_vod_view_mode),
                                value = stringResource(uiState.vodViewMode.labelResId()),
                                onClick = { showVodViewModeDialog = true }
                            )
                            SwitchSettingsRow(
                                label = stringResource(R.string.settings_vod_infinite_scroll),
                                value = stringResource(
                                    if (uiState.vodInfiniteScroll) R.string.settings_vod_infinite_scroll_on
                                    else R.string.settings_vod_infinite_scroll_off
                                ),
                                checked = uiState.vodInfiniteScroll,
                                onCheckedChange = { viewModel.setVodInfiniteScroll(it) },
                                enabled = uiState.vodViewMode == VodViewMode.MODERN,
                                indent = 24.dp
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_live),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.LIVE] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.LIVE.name }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_movies),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.MOVIE] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.MOVIE.name }
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_category_sort_series),
                                value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.SERIES] ?: CategorySortMode.DEFAULT, context),
                                onClick = { categorySortDialogType = ContentType.SERIES.name }
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { showLanguageDialog = true },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                    Text(text = appLanguageLabel, style = MaterialTheme.typography.bodyMedium, color = Primary)
                                }
                            }
                        }
                    }

                    else if (selectedCategory == 3) {
                        item {
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
                                }
                            )
                        }
                        item {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
                            TvClickableSurface(
                                onClick = { viewModel.toggleIncognitoMode() },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_incognito_mode), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_incognito_mode_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.isIncognitoMode, onCheckedChange = { viewModel.toggleIncognitoMode() })
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            TvClickableSurface(
                                onClick = { viewModel.toggleXtreamTextClassification() },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_xtream_text_classification), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_xtream_text_classification_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.useXtreamTextClassification, onCheckedChange = { viewModel.toggleXtreamTextClassification() })
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            TvClickableSurface(
                                onClick = { viewModel.toggleXtreamBase64TextCompatibility() },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_xtream_base64_compatibility), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_xtream_base64_compatibility_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = uiState.xtreamBase64TextCompatibility, onCheckedChange = { viewModel.toggleXtreamBase64TextCompatibility() })
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            TvClickableSurface(
                                onClick = { showClearHistoryDialog = true },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = Primary.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = stringResource(R.string.settings_clear_history), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                        Text(text = stringResource(R.string.settings_clear_history_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                                    }
                                    Text(text = stringResource(R.string.settings_clear_history_confirm), style = MaterialTheme.typography.labelLarge, color = Primary)
                                }
                            }
                        }
                    }

                    // ג”€ג”€ 3: Recordings ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 4) {
                        item {
                            RecordingInfoCard(
                                treeLabel = uiState.recordingStorageState.displayName,
                                outputDirectory = uiState.recordingStorageState.outputDirectory,
                                availableBytes = uiState.recordingStorageState.availableBytes,
                                isWritable = uiState.recordingStorageState.isWritable,
                                activeCount = uiState.recordingItems.count { it.status == RecordingStatus.RECORDING },
                                scheduledCount = uiState.recordingItems.count { it.status == RecordingStatus.SCHEDULED },
                                fileNamePattern = uiState.recordingStorageState.fileNamePattern,
                                retentionDays = uiState.recordingStorageState.retentionDays,
                                maxSimultaneousRecordings = uiState.recordingStorageState.maxSimultaneousRecordings,
                                paddingBeforeMinutes = uiState.recordingPaddingBeforeMinutes,
                                paddingAfterMinutes = uiState.recordingPaddingAfterMinutes
                            )
                        }
                        item {
                            RecordingActionsCard(
                                wifiOnlyRecording = uiState.wifiOnlyRecording,
                                onWifiOnlyRecordingChange = { viewModel.setRecordingWifiOnly(it) },
                                onChooseFolder = { recordingFolderLauncher.launch(null) },
                                onUseAppStorage = { viewModel.updateRecordingFolder(null, null) },
                                onChangePattern = { showRecordingPatternDialog = true },
                                onChangeRetention = { showRecordingRetentionDialog = true },
                                onChangeConcurrency = { showRecordingConcurrencyDialog = true },
                                onChangePadding = { showRecordingPaddingDialog = true },
                                onRepairSchedule = { viewModel.reconcileRecordings() },
                                onOpenBrowser = { showRecordingBrowserDialog = true }
                            )
                        }
                    }

                    // ג”€ג”€ 4: Backup ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 5) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TvClickableSurface(
                                    onClick = { createDocumentLauncher.launch("streamvault_backup.json") },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Primary.copy(alpha = 0.12f),
                                        focusedContainerColor = Primary.copy(alpha = 0.28f)
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(text = "\u2191", style = MaterialTheme.typography.titleLarge, color = Primary, fontWeight = FontWeight.Bold)
                                        Text(text = stringResource(R.string.settings_backup_data), style = MaterialTheme.typography.titleSmall, color = Primary, textAlign = TextAlign.Center)
                                        Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                                    }
                                }
                                TvClickableSurface(
                                    onClick = { openDocumentLauncher.launch(arrayOf("application/json")) },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Secondary.copy(alpha = 0.12f),
                                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(text = "\u2193", style = MaterialTheme.typography.titleLarge, color = Secondary, fontWeight = FontWeight.Bold)
                                        Text(text = stringResource(R.string.settings_restore_data), style = MaterialTheme.typography.titleSmall, color = Secondary, textAlign = TextAlign.Center)
                                        Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }

                    // ג”€ג”€ 5: EPG Sources ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 6) {
                        epgSourcesSection(
                            uiState = uiState,
                            viewModel = viewModel
                        )
                    }

                    // ג”€ג”€ 6: About ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    else if (selectedCategory == 7) {
                        item {
                            val downloadStatus = uiState.appUpdate.downloadStatus
                            LaunchedEffect(downloadStatus) {
                                if (downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloading) {
                                    while (true) {
                                        delay(2000L)
                                        viewModel.refreshDownloadState()
                                    }
                                }
                            }
                            SettingsSectionHeader(
                                title = stringResource(R.string.settings_updates_title),
                                subtitle = stringResource(R.string.settings_updates_subtitle)
                            )
                            SettingsRow(label = stringResource(R.string.settings_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            SwitchSettingsRow(
                                label = stringResource(R.string.settings_update_auto_check),
                                value = stringResource(
                                    if (uiState.autoCheckAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                                ),
                                checked = uiState.autoCheckAppUpdates,
                                onCheckedChange = viewModel::setAutoCheckAppUpdates
                            )
                            if (uiState.autoCheckAppUpdates) {
                                SwitchSettingsRow(
                                    label = stringResource(R.string.settings_update_auto_download),
                                    value = stringResource(
                                        if (uiState.autoDownloadAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                                    ),
                                    checked = uiState.autoDownloadAppUpdates,
                                    onCheckedChange = viewModel::setAutoDownloadAppUpdates
                                )
                            }
                            SettingsRow(
                                label = stringResource(R.string.settings_update_latest_release),
                                value = formatLatestReleaseLabel(uiState.appUpdate, context)
                            )
                            SettingsRow(
                                label = stringResource(R.string.settings_update_status),
                                value = formatUpdateStatusLabel(uiState.appUpdate, context)
                            )
                            SettingsRow(
                                label = stringResource(R.string.settings_update_last_checked),
                                value = formatUpdateCheckTimeLabel(uiState.appUpdate.lastCheckedAt, context)
                            )
                            ClickableSettingsRow(
                                label = stringResource(R.string.settings_update_check_now),
                                value = stringResource(
                                    if (uiState.isCheckingForUpdates) R.string.settings_update_checking else R.string.settings_update_check_action
                                ),
                                onClick = {
                                    if (!uiState.isCheckingForUpdates) {
                                        viewModel.checkForAppUpdates()
                                    }
                                }
                            )
                            if (shouldShowUpdateDownloadAction(uiState.appUpdate)) {
                                ClickableSettingsRow(
                                    label = stringResource(R.string.settings_update_download),
                                    value = formatUpdateDownloadLabel(uiState.appUpdate, context),
                                    onClick = {
                                        if (uiState.appUpdate.downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded) {
                                            viewModel.installDownloadedUpdate()
                                        } else if (uiState.appUpdate.downloadStatus != com.streamvault.app.update.AppUpdateDownloadStatus.Downloading) {
                                            viewModel.downloadLatestUpdate()
                                        }
                                    }
                                )
                            }
                            if (!uiState.appUpdate.releaseUrl.isNullOrBlank()) {
                                ClickableSettingsRow(
                                    label = stringResource(R.string.settings_update_view_release),
                                    value = uiState.appUpdate.latestVersionName ?: stringResource(R.string.settings_update_release_notes),
                                    onClick = { uriHandler.openUri(uiState.appUpdate.releaseUrl.orEmpty()) }
                                )
                            }
                            if (!uiState.appUpdate.errorMessage.isNullOrBlank()) {
                                SettingsRow(
                                    label = stringResource(R.string.settings_update_error),
                                    value = uiState.appUpdate.errorMessage.orEmpty()
                                )
                            }
                        }

                        item {
                            SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
                            SettingsRow(label = stringResource(R.string.settings_build_verification), value = buildVerificationLabel)
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
            }
        }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )

    if (showRecordingBrowserDialog) {
        RecordingBrowserDialog(
            recordingItems = uiState.recordingItems,
            selectedRecordingId = selectedRecordingId,
            onSelectedRecordingChange = { selectedRecordingId = it },
            onDismiss = { showRecordingBrowserDialog = false },
            onPlay = { item ->
                val playbackUrl = item.playbackUrl()
                if (!playbackUrl.isNullOrBlank()) {
                    mainActivity?.openPlayer(
                        Routes.player(
                            streamUrl = playbackUrl,
                            title = item.programTitle ?: item.channelName,
                            internalId = item.id.hashCode().toLong().and(0x7FFFFFFFL),
                            providerId = item.providerId,
                            contentType = "MOVIE",
                            returnRoute = currentRoute
                        )
                    )
                }
            },
            onStop = { item -> viewModel.stopRecording(item.id) },
            onCancel = { item -> viewModel.cancelRecording(item.id) },
            onSkipOccurrence = { item -> viewModel.skipOccurrence(item.id) },
            onDelete = { item -> viewModel.deleteRecording(item.id) },
            onRetry = { item -> viewModel.retryRecording(item.id) },
            onToggleSchedule = { item, enabled ->
                viewModel.setRecordingScheduleEnabled(item.id, enabled)
            }
        )
    }

    SettingsScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        showLiveTvModeDialog = showLiveTvModeDialog,
        onShowLiveTvModeDialogChange = { showLiveTvModeDialog = it },
        showLiveTvQuickFilterVisibilityDialog = showLiveTvQuickFilterVisibilityDialog,
        onShowLiveTvQuickFilterVisibilityDialogChange = { showLiveTvQuickFilterVisibilityDialog = it },
        showLiveChannelNumberingDialog = showLiveChannelNumberingDialog,
        onShowLiveChannelNumberingDialogChange = { showLiveChannelNumberingDialog = it },
        showLiveChannelGroupingDialog = showLiveChannelGroupingDialog,
        onShowLiveChannelGroupingDialogChange = { showLiveChannelGroupingDialog = it },
        showGroupedChannelLabelDialog = showGroupedChannelLabelDialog,
        onShowGroupedChannelLabelDialogChange = { showGroupedChannelLabelDialog = it },
        showLiveVariantPreferenceDialog = showLiveVariantPreferenceDialog,
        onShowLiveVariantPreferenceDialogChange = { showLiveVariantPreferenceDialog = it },
        showVodViewModeDialog = showVodViewModeDialog,
        onShowVodViewModeDialogChange = { showVodViewModeDialog = it },
        showGuideDefaultCategoryDialog = showGuideDefaultCategoryDialog,
        onShowGuideDefaultCategoryDialogChange = { showGuideDefaultCategoryDialog = it },
        showPlaybackSpeedDialog = showPlaybackSpeedDialog,
        onShowPlaybackSpeedDialogChange = { showPlaybackSpeedDialog = it },
        showAudioVideoOffsetDialog = showAudioVideoOffsetDialog,
        onShowAudioVideoOffsetDialogChange = { showAudioVideoOffsetDialog = it },
        showDecoderModeDialog = showDecoderModeDialog,
        onShowDecoderModeDialogChange = { showDecoderModeDialog = it },
        showSurfaceModeDialog = showSurfaceModeDialog,
        onShowSurfaceModeDialogChange = { showSurfaceModeDialog = it },
        showTimeshiftDepthDialog = showTimeshiftDepthDialog,
        onShowTimeshiftDepthDialogChange = { showTimeshiftDepthDialog = it },
        showDefaultStopTimerDialog = showDefaultStopTimerDialog,
        onShowDefaultStopTimerDialogChange = { showDefaultStopTimerDialog = it },
        showDefaultIdleTimerDialog = showDefaultIdleTimerDialog,
        onShowDefaultIdleTimerDialogChange = { showDefaultIdleTimerDialog = it },
        showControlsTimeoutDialog = showControlsTimeoutDialog,
        onShowControlsTimeoutDialogChange = { showControlsTimeoutDialog = it },
        showLiveOverlayTimeoutDialog = showLiveOverlayTimeoutDialog,
        onShowLiveOverlayTimeoutDialogChange = { showLiveOverlayTimeoutDialog = it },
        showNoticeTimeoutDialog = showNoticeTimeoutDialog,
        onShowNoticeTimeoutDialogChange = { showNoticeTimeoutDialog = it },
        showDiagnosticsTimeoutDialog = showDiagnosticsTimeoutDialog,
        onShowDiagnosticsTimeoutDialogChange = { showDiagnosticsTimeoutDialog = it },
        showLiveTvFiltersDialog = showLiveTvFiltersDialog,
        onShowLiveTvFiltersDialogChange = { showLiveTvFiltersDialog = it },
        showAudioLanguageDialog = showAudioLanguageDialog,
        onShowAudioLanguageDialogChange = { showAudioLanguageDialog = it },
        showSubtitleSizeDialog = showSubtitleSizeDialog,
        onShowSubtitleSizeDialogChange = { showSubtitleSizeDialog = it },
        showSubtitleTextColorDialog = showSubtitleTextColorDialog,
        onShowSubtitleTextColorDialogChange = { showSubtitleTextColorDialog = it },
        showSubtitleBackgroundDialog = showSubtitleBackgroundDialog,
        onShowSubtitleBackgroundDialogChange = { showSubtitleBackgroundDialog = it },
        showWifiQualityDialog = showWifiQualityDialog,
        onShowWifiQualityDialogChange = { showWifiQualityDialog = it },
        showEthernetQualityDialog = showEthernetQualityDialog,
        onShowEthernetQualityDialogChange = { showEthernetQualityDialog = it },
        categorySortDialogType = categorySortDialogType,
        onCategorySortDialogTypeChange = { categorySortDialogType = it },
        showPinDialog = showPinDialog,
        onShowPinDialogChange = { showPinDialog = it },
        showLevelDialog = showLevelDialog,
        onShowLevelDialogChange = { showLevelDialog = it },
        pinError = pinError,
        onPinErrorChange = { pinError = it },
        pendingAction = pendingAction,
        onPendingActionChange = { pendingAction = it },
        pendingProtectionLevel = pendingProtectionLevel,
        onPendingProtectionLevelChange = { pendingProtectionLevel = it },
        showLanguageDialog = showLanguageDialog,
        onShowLanguageDialogChange = { showLanguageDialog = it },
        showTimeFormatDialog = showTimeFormatDialog,
        onShowTimeFormatDialogChange = { showTimeFormatDialog = it },
        showRecordingPatternDialog = showRecordingPatternDialog,
        onShowRecordingPatternDialogChange = { showRecordingPatternDialog = it },
        showRecordingRetentionDialog = showRecordingRetentionDialog,
        onShowRecordingRetentionDialogChange = { showRecordingRetentionDialog = it },
        showRecordingConcurrencyDialog = showRecordingConcurrencyDialog,
        onShowRecordingConcurrencyDialogChange = { showRecordingConcurrencyDialog = it },
        showRecordingPaddingDialog = showRecordingPaddingDialog,
        onShowRecordingPaddingDialogChange = { showRecordingPaddingDialog = it },
        showClearHistoryDialog = showClearHistoryDialog,
        onShowClearHistoryDialogChange = { showClearHistoryDialog = it },
        showCreateCombinedDialog = showCreateCombinedDialog,
        onShowCreateCombinedDialogChange = { showCreateCombinedDialog = it },
        showAddCombinedMemberDialog = showAddCombinedMemberDialog,
        onShowAddCombinedMemberDialogChange = { showAddCombinedMemberDialog = it },
        showRenameCombinedDialog = showRenameCombinedDialog,
        onShowRenameCombinedDialogChange = { showRenameCombinedDialog = it },
        selectedCombinedProfileId = selectedCombinedProfileId,
        showProviderSyncDialog = showProviderSyncDialog,
        onShowProviderSyncDialogChange = { showProviderSyncDialog = it },
        showCustomProviderSyncDialog = showCustomProviderSyncDialog,
        onShowCustomProviderSyncDialogChange = { showCustomProviderSyncDialog = it },
        pendingSyncProviderId = pendingSyncProviderId,
        onPendingSyncProviderIdChange = { pendingSyncProviderId = it },
        customSyncSelections = customSyncSelections,
        onCustomSyncSelectionsChange = { customSyncSelections = it }
    )
}
}

private fun formatOfficialBuildStatusLabel(
    status: OfficialBuildStatus,
    context: android.content.Context
): String = when (status) {
    OfficialBuildStatus.OFFICIAL -> context.getString(R.string.settings_build_verification_official)
    OfficialBuildStatus.UNOFFICIAL -> context.getString(R.string.settings_build_verification_unofficial)
    OfficialBuildStatus.VERIFICATION_UNAVAILABLE -> context.getString(R.string.settings_build_verification_unavailable)
}

private fun android.content.Context.findMainActivity(): MainActivity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}

private fun formatAppTimeFormatLabel(
    format: AppTimeFormat,
    context: android.content.Context
): String = context.getString(
    when (format) {
        AppTimeFormat.SYSTEM -> R.string.settings_time_format_system
        AppTimeFormat.TWELVE_HOUR -> R.string.settings_time_format_12h
        AppTimeFormat.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
    }
)

private fun formatTimeshiftDepthLabel(
    depthMinutes: Int,
    context: android.content.Context
): String = when (depthMinutes) {
    15 -> context.getString(R.string.settings_live_timeshift_depth_15)
    60 -> context.getString(R.string.settings_live_timeshift_depth_60)
    else -> context.getString(R.string.settings_live_timeshift_depth_30)
}

