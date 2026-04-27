package com.streamvault.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.BuildConfig
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.model.applyProviderCategoryDisplayPreferences
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.update.AppUpdateDownloadState
import com.streamvault.app.update.AppUpdateDownloadStatus
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.update.GitHubReleaseInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ExportBackupCommand
import com.streamvault.domain.usecase.ExportBackupResult
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.max
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val categoryRepository: CategoryRepository,
    private val channelRepository: ChannelRepository,
    private val preferencesRepository: PreferencesRepository,
    private val internetSpeedTestRunner: InternetSpeedTestRunner,
    private val backupManager: BackupManager,
    private val recordingManager: RecordingManager,
    private val parentalControlManager: ParentalControlManager,
    private val syncManager: SyncManager,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val playbackHistoryRepository: com.streamvault.domain.repository.PlaybackHistoryRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val syncProvider: SyncProvider,
    private val epgSourceRepository: com.streamvault.domain.repository.EpgSourceRepository,
    private val gitHubReleaseChecker: GitHubReleaseChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val getCustomCategories: GetCustomCategories
) : ViewModel() {
    private val appContext = application
    private val exportBackup = ExportBackup(backupManager)
    private val importBackup = ImportBackup(backupManager)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val activeProviderIdFlow = providerRepository.getActiveProvider().map { it?.id }
    private val appUpdateActions = SettingsAppUpdateActions(
        appContext = application,
        preferencesRepository = preferencesRepository,
        gitHubReleaseChecker = gitHubReleaseChecker,
        appUpdateInstaller = appUpdateInstaller,
        uiState = _uiState
    )
    private val backupActions = SettingsBackupActions(
        exportBackup = exportBackup,
        importBackup = importBackup,
        uiState = _uiState
    )
    private val recordingActions = SettingsRecordingActions(
        appContext = application,
        recordingManager = recordingManager,
        uiState = _uiState
    )
    private val providerActions = SettingsProviderActions(
        providerRepository = providerRepository,
        combinedM3uRepository = combinedM3uRepository,
        preferencesRepository = preferencesRepository,
        syncProvider = syncProvider,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        tvInputChannelSyncManager = tvInputChannelSyncManager,
        uiState = _uiState
    )
    private val syncActions = SettingsSyncActions(
        appContext = application,
        syncManager = syncManager,
        tvInputChannelSyncManager = tvInputChannelSyncManager,
        uiState = _uiState,
        refreshProvider = { scope, providerId, syncMode -> providerActions.refreshProvider(scope, providerId, syncMode) }
    )
    private val epgActions = SettingsEpgActions(
        epgSourceRepository = epgSourceRepository,
        uiState = _uiState
    )

    init {
        viewModelScope.launch {
            observeSettingsPreferenceSnapshot(
                providerRepository = providerRepository,
                activeProviderIdFlow = activeProviderIdFlow,
                preferencesRepository = preferencesRepository
            ).collect { snapshot ->
                _uiState.update { it.applyPreferenceSnapshot(snapshot) }
            }
        }

        viewModelScope.launch {
            combine(
                preferencesRepository.autoCheckAppUpdates,
                preferencesRepository.lastAppUpdateCheckTimestamp,
                preferencesRepository.autoDownloadAppUpdates
            ) { autoCheckEnabled, lastCheckedAt, autoDownload ->
                Triple(autoCheckEnabled, lastCheckedAt, autoDownload)
            }.distinctUntilChanged().collect { (autoCheckEnabled, lastCheckedAt, autoDownload) ->
                if (autoCheckEnabled && appUpdateActions.shouldAutoCheckForUpdates(lastCheckedAt)) {
                    appUpdateActions.checkForAppUpdates(
                        scope = viewModelScope,
                        manual = false,
                        isRemoteVersionNewer = ::isRemoteVersionNewer,
                        autoDownload = autoDownload
                    )
                }
            }
        }

        viewModelScope.launch {
            appUpdateInstaller.downloadState.collect { downloadState ->
                _uiState.update {
                    it.copy(appUpdate = it.appUpdate.withDownloadState(downloadState))
                }
            }
        }

        viewModelScope.launch {
            appUpdateInstaller.refreshState()
        }

        viewModelScope.launch {
            combinedM3uRepository.getProfiles().collect { profiles ->
                _uiState.update { it.copy(combinedProfiles = profiles) }
            }
        }

        viewModelScope.launch {
            combinedM3uRepository.getAvailableM3uProviders().collect { providers ->
                _uiState.update { it.copy(availableM3uProviders = providers) }
            }
        }

        viewModelScope.launch {
            combinedM3uRepository.getActiveLiveSource().collect { activeSource ->
                _uiState.update { it.copy(activeLiveSource = activeSource) }
            }
        }

        viewModelScope.launch {
            observeProviderDiagnostics(
                providerRepository = providerRepository,
                syncMetadataRepository = syncMetadataRepository,
                application = appContext
            ).collect { diagnosticsByProvider ->
                _uiState.update { it.copy(diagnosticsByProvider = diagnosticsByProvider) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.lastMaintenanceSnapshot.collect { snapshot ->
                _uiState.update { it.copy(databaseMaintenance = snapshot?.toUiModel()) }
            }
        }

        viewModelScope.launch {
            observeCategoryManagement(
                activeProviderIdFlow = activeProviderIdFlow,
                preferencesRepository = preferencesRepository,
                categoryRepository = categoryRepository
            ).collect { snapshot ->
                _uiState.update {
                    it.copy(
                        categorySortModes = snapshot.categorySortModes,
                        hiddenCategories = snapshot.hiddenCategories
                    )
                }
            }
        }

        viewModelScope.launch {
            observeGuideDefaultCategoryOptions().collect { categories ->
                _uiState.update { it.copy(guideDefaultCategoryOptions = categories) }
            }
        }

        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _uiState.update { it.copy(recordingItems = items.sortedByDescending(RecordingItem::scheduledStartMs)) }
            }
        }

        viewModelScope.launch {
            recordingManager.observeStorageState().collect { storage ->
                _uiState.update { it.copy(recordingStorageState = storage) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.recordingWifiOnly.collect { wifiOnly ->
                _uiState.update { it.copy(wifiOnlyRecording = wifiOnly) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.recordingPaddingBeforeMinutes.collect { minutes ->
                _uiState.update { it.copy(recordingPaddingBeforeMinutes = minutes) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.recordingPaddingAfterMinutes.collect { minutes ->
                _uiState.update { it.copy(recordingPaddingAfterMinutes = minutes) }
            }
        }

        viewModelScope.launch {
            epgSourceRepository.getAllSources().collect { sources ->
                _uiState.update { it.copy(epgSources = sources) }
            }
        }
    }

    fun setActiveProvider(providerId: Long) {
        providerActions.setActiveProvider(viewModelScope, providerId)
    }

    fun setActiveCombinedProfile(profileId: Long) {
        providerActions.setActiveCombinedProfile(viewModelScope, profileId)
    }

    fun createCombinedProfile(name: String, providerIds: List<Long>) {
        providerActions.createCombinedProfile(viewModelScope, name, providerIds)
    }

    fun deleteCombinedProfile(profileId: Long) {
        providerActions.deleteCombinedProfile(viewModelScope, profileId)
    }

    fun addProviderToCombinedProfile(profileId: Long, providerId: Long) {
        providerActions.addProviderToCombinedProfile(viewModelScope, profileId, providerId)
    }

    fun renameCombinedProfile(profileId: Long, name: String) {
        providerActions.renameCombinedProfile(viewModelScope, profileId, name)
    }

    fun removeProviderFromCombinedProfile(profileId: Long, providerId: Long) {
        providerActions.removeProviderFromCombinedProfile(viewModelScope, profileId, providerId)
    }

    fun moveCombinedProvider(profileId: Long, providerId: Long, moveUp: Boolean) {
        providerActions.moveCombinedProvider(viewModelScope, profileId, providerId, moveUp)
    }

    fun setCombinedProviderEnabled(profileId: Long, providerId: Long, enabled: Boolean) {
        providerActions.setCombinedProviderEnabled(viewModelScope, profileId, providerId, enabled)
    }

    fun setM3uVodClassificationEnabled(providerId: Long, enabled: Boolean) {
        providerActions.setM3uVodClassificationEnabled(viewModelScope, providerId, enabled)
    }

    fun refreshProviderClassification(providerId: Long) {
        refreshProvider(providerId)
    }

    fun setParentalControlLevel(level: Int) {
        viewModelScope.launch {
            preferencesRepository.setParentalControlLevel(level)
        }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            preferencesRepository.setAppLanguage(language)
        }
    }

    fun setLiveTvChannelMode(mode: LiveTvChannelMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvChannelMode(mode.name)
        }
    }

    fun setShowLiveSourceSwitcher(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowLiveSourceSwitcher(enabled)
        }
    }

    fun setShowAllChannelsCategory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowAllChannelsCategory(enabled)
        }
    }

    fun setShowRecentChannelsCategory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowRecentChannelsCategory(enabled)
        }
    }

    fun setLiveTvQuickFilterVisibilityMode(mode: LiveTvQuickFilterVisibilityMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveTvQuickFilterVisibility(mode.storageValue)
        }
    }

    fun addLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            val normalized = filter.trim()
            when {
                normalized.isBlank() -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_blank))
                    }
                }
                _uiState.value.liveTvCategoryFilters.any { existing ->
                    existing.equals(normalized, ignoreCase = true)
                } -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_duplicate, normalized))
                    }
                }
                preferencesRepository.addLiveTvCategoryFilter(normalized) -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_added, normalized))
                    }
                }
            }
        }
    }

    fun removeLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            if (preferencesRepository.removeLiveTvCategoryFilter(filter)) {
                _uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_removed, filter))
                }
            }
        }
    }

    fun setLiveChannelNumberingMode(mode: ChannelNumberingMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveChannelNumberingMode(mode)
        }
    }

    fun setLiveChannelGroupingMode(mode: LiveChannelGroupingMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveChannelGroupingMode(mode)
        }
    }

    fun setGroupedChannelLabelMode(mode: GroupedChannelLabelMode) {
        viewModelScope.launch {
            preferencesRepository.setGroupedChannelLabelMode(mode)
        }
    }

    fun setLiveVariantPreferenceMode(mode: LiveVariantPreferenceMode) {
        viewModelScope.launch {
            preferencesRepository.setLiveVariantPreferenceMode(mode)
        }
    }

    fun setVodViewMode(mode: VodViewMode) {
        viewModelScope.launch {
            preferencesRepository.setVodViewMode(mode.storageValue)
        }
    }

    fun setVodInfiniteScroll(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setVodInfiniteScroll(enabled)
        }
    }

    fun setGuideDefaultCategory(categoryId: Long) {
        viewModelScope.launch {
            preferencesRepository.setGuideDefaultCategoryId(categoryId)
        }
    }

    fun setAppTimeFormat(format: AppTimeFormat) {
        viewModelScope.launch {
            preferencesRepository.setAppTimeFormat(format)
        }
    }

    fun setPreventStandbyDuringPlayback(prevent: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreventStandbyDuringPlayback(prevent)
        }
    }

    fun setAutoPlayNextEpisode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoPlayNextEpisode(enabled)
        }
    }

    fun setAutoCheckAppUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoCheckAppUpdates(enabled)
        }
    }

    fun setAutoDownloadAppUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoDownloadAppUpdates(enabled)
        }
    }

    fun refreshDownloadState() {
        viewModelScope.launch {
            appUpdateInstaller.refreshState()
        }
    }

    fun checkForAppUpdates() {
        appUpdateActions.checkForAppUpdates(
            scope = viewModelScope,
            manual = true,
            isRemoteVersionNewer = ::isRemoteVersionNewer
        )
    }

    fun downloadLatestUpdate() {
        appUpdateActions.downloadLatestUpdate(viewModelScope)
    }

    fun installDownloadedUpdate() {
        appUpdateActions.installDownloadedUpdate(viewModelScope)
    }

    fun setCategorySortMode(type: ContentType, mode: CategorySortMode) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategorySortMode(providerId, type, mode)
        }
    }

    fun unhideCategory(category: Category) {
        val providerId = _uiState.value.activeProviderId ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = category.type,
                categoryId = category.id,
                hidden = false
            )
            _uiState.update { it.copy(userMessage = "Unhid ${category.name}") }
        }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackSpeed(speed)
        }
    }

    fun setPlayerAudioVideoOffsetMs(offsetMs: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerAudioVideoOffsetMs(offsetMs)
        }
    }

    fun setPlayerMediaSessionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerMediaSessionEnabled(enabled)
        }
    }

    fun setPlayerTimeshiftEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPlayerTimeshiftEnabled(enabled)
        }
    }

    fun setPlayerTimeshiftDepthMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerTimeshiftDepthMinutes(minutes)
        }
    }

    fun setDefaultStopPlaybackTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setDefaultStopPlaybackTimerMinutes(minutes)
        }
    }

    fun setDefaultIdleStandbyTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setDefaultIdleStandbyTimerMinutes(minutes)
        }
    }

    fun setZapAutoRevert(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setZapAutoRevert(enabled)
        }
    }

    fun setRecordingWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRecordingWifiOnly(enabled)
        }
    }

    fun setRecordingPaddingBeforeMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setRecordingPaddingBeforeMinutes(minutes)
        }
    }

    fun setRecordingPaddingAfterMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.setRecordingPaddingAfterMinutes(minutes)
        }
    }

    fun setPlayerDecoderMode(mode: DecoderMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerDecoderMode(mode)
        }
    }

    fun setPlayerSurfaceMode(mode: com.streamvault.domain.model.PlayerSurfaceMode) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSurfaceMode(mode)
        }
    }

    fun setPlayerControlsTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerControlsTimeoutSeconds(seconds)
        }
    }

    fun setPlayerLiveOverlayTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerLiveOverlayTimeoutSeconds(seconds)
        }
    }

    fun setPlayerNoticeTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerNoticeTimeoutSeconds(seconds)
        }
    }

    fun setPlayerDiagnosticsTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerDiagnosticsTimeoutSeconds(seconds)
        }
    }

    fun setPreferredAudioLanguage(languageTag: String?) {
        viewModelScope.launch {
            preferencesRepository.setPreferredAudioLanguage(languageTag)
        }
    }

    fun setSubtitleTextScale(scale: Float) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextScale(scale)
        }
    }

    fun setSubtitleTextColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleTextColor(colorArgb)
        }
    }

    fun setSubtitleBackgroundColor(colorArgb: Int) {
        viewModelScope.launch {
            preferencesRepository.setPlayerSubtitleBackgroundColor(colorArgb)
        }
    }

    fun setWifiQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerWifiMaxVideoHeight(maxHeight)
        }
    }

    fun setEthernetQualityCap(maxHeight: Int?) {
        viewModelScope.launch {
            preferencesRepository.setPlayerEthernetMaxVideoHeight(maxHeight)
        }
    }

    fun runInternetSpeedTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningInternetSpeedTest = true) }
            when (val result = internetSpeedTestRunner.run()) {
                is InternetSpeedTestResult.Success -> {
                    val snapshot = result.snapshot
                    preferencesRepository.setLastSpeedTestResult(
                        megabitsPerSecond = snapshot.megabitsPerSecond,
                        measuredAtMs = snapshot.measuredAtMs,
                        transport = snapshot.transport.name,
                        recommendedMaxHeight = snapshot.recommendedMaxVideoHeight,
                        estimated = snapshot.isEstimated
                    )
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = if (snapshot.isEstimated) {
                                appContext.getString(R.string.settings_speed_test_estimate_complete)
                            } else {
                                appContext.getString(R.string.settings_speed_test_complete)
                            }
                        )
                    }
                }
                is InternetSpeedTestResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRunningInternetSpeedTest = false,
                            userMessage = appContext.getString(R.string.settings_speed_test_failed, result.message)
                        )
                    }
                }
            }
        }
    }

    fun applySpeedTestRecommendationToWifi() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerWifiMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_wifi_applied)) }
        }
    }

    fun applySpeedTestRecommendationToEthernet() {
        viewModelScope.launch {
            val recommendation = _uiState.value.lastSpeedTest?.recommendedMaxVideoHeight
            preferencesRepository.setPlayerEthernetMaxVideoHeight(recommendation)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_speed_test_ethernet_applied)) }
        }
    }

    fun toggleIncognitoMode() {
        viewModelScope.launch {
            val current = _uiState.value.isIncognitoMode
            preferencesRepository.setIncognitoMode(!current)
        }
    }

    fun toggleXtreamTextClassification() {
        viewModelScope.launch {
            val current = _uiState.value.useXtreamTextClassification
            preferencesRepository.setUseXtreamTextClassification(!current)
        }
    }

    fun toggleXtreamBase64TextCompatibility() {
        viewModelScope.launch {
            val current = _uiState.value.xtreamBase64TextCompatibility
            preferencesRepository.setXtreamBase64TextCompatibility(!current)
            preferencesRepository.bumpXtreamTextImportGeneration()
            _uiState.update {
                it.copy(userMessage = "Xtream text decoding changed. Refresh each Xtream provider once to re-import titles.")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            when (val result = playbackHistoryRepository.clearAllHistory()) {
                is Result.Success -> {
                    preferencesRepository.clearAllRecentData()
                    _uiState.update { it.copy(isSyncing = false, userMessage = appContext.getString(R.string.settings_history_cleared)) }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isSyncing = false, userMessage = "Failed to clear history: ${result.message}") }
                }
                Result.Loading -> Unit
            }
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun changePin(newPin: String) {
        viewModelScope.launch {
            preferencesRepository.setParentalPin(newPin)
            parentalControlManager.clearUnlockedCategories()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_pin_changed)) }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshProvider(
        providerId: Long,
        syncMode: SettingsProviderSyncMode = SettingsProviderSyncMode.QUICK
    ) {
        providerActions.refreshProvider(viewModelScope, providerId, syncMode)
    }

    fun syncProviderSection(providerId: Long, selection: ProviderSyncSelection) {
        syncActions.syncProviderSection(viewModelScope, providerId, selection)
    }

    fun syncProviderCustom(providerId: Long, selections: Set<ProviderSyncSelection>) {
        syncActions.syncProviderCustom(viewModelScope, providerId, selections)
    }

    fun retryWarningAction(providerId: Long, action: ProviderWarningAction) {
        syncActions.retryWarningAction(viewModelScope, providerId, action)
    }

    fun deleteProvider(providerId: Long) {
        providerActions.deleteProvider(viewModelScope, providerId)
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun isRemoteVersionNewer(remoteVersionCode: Int?, remoteVersionName: String): Boolean {
        if (remoteVersionCode != null && remoteVersionCode > BuildConfig.VERSION_CODE) {
            return true
        }
        return compareVersionNames(remoteVersionName, BuildConfig.VERSION_NAME) > 0
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.removePrefix("v").split('.')
        val rightParts = right.removePrefix("v").split('.')
        val length = max(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val leftValue = leftParts.getOrNull(index)?.toIntOrNull() ?: 0
            val rightValue = rightParts.getOrNull(index)?.toIntOrNull() ?: 0
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }

    fun exportConfig(uriString: String) {
        backupActions.exportConfig(viewModelScope, uriString)
    }

    fun inspectBackup(uriString: String) {
        backupActions.inspectBackup(viewModelScope, uriString)
    }

    fun dismissBackupPreview() {
        backupActions.dismissBackupPreview()
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        backupActions.setBackupConflictStrategy(strategy)
    }

    fun setImportPreferences(enabled: Boolean) {
        backupActions.setImportPreferences(enabled)
    }

    fun setImportProviders(enabled: Boolean) {
        backupActions.setImportProviders(enabled)
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        backupActions.setImportSavedLibrary(enabled)
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        backupActions.setImportPlaybackHistory(enabled)
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        backupActions.setImportMultiViewPresets(enabled)
    }

    fun setImportRecordingSchedules(enabled: Boolean) {
        backupActions.setImportRecordingSchedules(enabled)
    }

    fun confirmBackupImport() {
        backupActions.confirmBackupImport(viewModelScope)
    }

    fun stopRecording(recordingId: String) {
        recordingActions.stopRecording(viewModelScope, recordingId)
    }

    fun cancelRecording(recordingId: String) {
        recordingActions.cancelRecording(viewModelScope, recordingId)
    }

    fun skipOccurrence(recordingId: String) {
        recordingActions.skipOccurrence(viewModelScope, recordingId)
    }

    fun deleteRecording(recordingId: String) {
        recordingActions.deleteRecording(viewModelScope, recordingId)
    }

    fun retryRecording(recordingId: String) {
        recordingActions.retryRecording(viewModelScope, recordingId)
    }

    fun setRecordingScheduleEnabled(recordingId: String, enabled: Boolean) {
        recordingActions.setRecordingScheduleEnabled(viewModelScope, recordingId, enabled)
    }

    fun reconcileRecordings() {
        recordingActions.reconcileRecordings(viewModelScope)
    }

    fun updateRecordingFolder(treeUri: String?, displayName: String?) {
        recordingActions.updateRecordingFolder(viewModelScope, treeUri, displayName)
    }

    fun updateRecordingFileNamePattern(pattern: String) {
        recordingActions.updateRecordingFileNamePattern(viewModelScope, pattern)
    }

    fun updateRecordingRetentionDays(retentionDays: Int?) {
        recordingActions.updateRecordingRetentionDays(viewModelScope, retentionDays)
    }

    fun updateRecordingMaxSimultaneous(maxSimultaneousRecordings: Int) {
        recordingActions.updateRecordingMaxSimultaneous(viewModelScope, maxSimultaneousRecordings)
    }

    // ── EPG Source Management ────────────────────────────────────────

    fun loadEpgAssignments(providerId: Long) {
        epgActions.loadEpgAssignments(viewModelScope, providerId)
    }

    fun addEpgSource(name: String, url: String) {
        epgActions.addEpgSource(viewModelScope, name, url)
    }

    fun setPendingDeleteEpgSource(id: Long?) {
        _uiState.update { it.copy(epgPendingDeleteSourceId = id) }
    }

    fun deleteEpgSource(sourceId: Long) {
        epgActions.deleteEpgSource(viewModelScope, sourceId)
    }

    fun toggleEpgSourceEnabled(sourceId: Long, enabled: Boolean) {
        epgActions.toggleEpgSourceEnabled(viewModelScope, sourceId, enabled)
    }

    fun refreshEpgSource(sourceId: Long) {
        epgActions.refreshEpgSource(viewModelScope, sourceId)
    }

    fun assignEpgSourceToProvider(providerId: Long, epgSourceId: Long) {
        epgActions.assignEpgSourceToProvider(viewModelScope, providerId, epgSourceId)
    }

    fun unassignEpgSourceFromProvider(providerId: Long, epgSourceId: Long) {
        epgActions.unassignEpgSourceFromProvider(viewModelScope, providerId, epgSourceId)
    }

    fun moveEpgSourceAssignmentUp(providerId: Long, epgSourceId: Long) {
        epgActions.moveEpgSourceAssignmentUp(viewModelScope, providerId, epgSourceId)
    }

    fun moveEpgSourceAssignmentDown(providerId: Long, epgSourceId: Long) {
        epgActions.moveEpgSourceAssignmentDown(viewModelScope, providerId, epgSourceId)
    }

    private fun observeGuideDefaultCategoryOptions(): Flow<List<Category>> {
        return combinedM3uRepository.getActiveLiveSource().flatMapLatest { activeSource ->
            when (activeSource) {
                is ActiveLiveSource.CombinedM3uSource -> {
                    combine(
                        combinedM3uRepository.getCombinedCategories(activeSource.profileId),
                        flow {
                            emit(combinedM3uRepository.getProfile(activeSource.profileId)?.members.orEmpty())
                        }.flatMapLatest { members ->
                            getCustomCategories(
                                members.filter { it.enabled }.map { it.providerId },
                                ContentType.LIVE
                            )
                        }
                    ) { combinedCategories, customCategories ->
                        buildGuideDefaultCategoryOptions(
                            physicalCategories = combinedCategories.map { it.category },
                            customCategories = customCategories
                        )
                    }
                }
                is ActiveLiveSource.ProviderSource -> {
                    combine(
                        channelRepository.getCategories(activeSource.providerId),
                        getCustomCategories(activeSource.providerId, ContentType.LIVE),
                        preferencesRepository.getHiddenCategoryIds(activeSource.providerId, ContentType.LIVE),
                        preferencesRepository.getCategorySortMode(activeSource.providerId, ContentType.LIVE)
                    ) { categories, customCategories, hiddenCategoryIds, sortMode ->
                        val visibleProviderCategories = applyProviderCategoryDisplayPreferences(
                            categories = categories.filter { it.id != ChannelRepository.ALL_CHANNELS_ID },
                            hiddenCategoryIds = hiddenCategoryIds,
                            sortMode = sortMode
                        )
                        buildGuideDefaultCategoryOptions(
                            physicalCategories = visibleProviderCategories,
                            customCategories = customCategories
                        )
                    }
                }
                null -> flowOf(
                    listOf(
                        Category(
                            id = VirtualCategoryIds.FAVORITES,
                            name = "Favorites",
                            type = ContentType.LIVE,
                            isVirtual = true
                        ),
                        Category(
                            id = ChannelRepository.ALL_CHANNELS_ID,
                            name = "All Channels",
                            type = ContentType.LIVE
                        )
                    )
                )
            }
        }
    }

    private fun buildGuideDefaultCategoryOptions(
        physicalCategories: List<Category>,
        customCategories: List<Category>
    ): List<Category> {
        val favorites = customCategories.find { it.id == VirtualCategoryIds.FAVORITES }
        return buildList {
            if (favorites != null) {
                add(favorites)
            }
            addAll(customCategories.filter { it.id != VirtualCategoryIds.FAVORITES })
            add(
                Category(
                    id = ChannelRepository.ALL_CHANNELS_ID,
                    name = "All Channels",
                    type = ContentType.LIVE,
                    count = physicalCategories.sumOf(Category::count)
                )
            )
            addAll(physicalCategories)
        }
    }
}
