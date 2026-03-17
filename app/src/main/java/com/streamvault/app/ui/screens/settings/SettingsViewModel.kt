package com.streamvault.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProviderWarningAction {
    EPG,
    MOVIES,
    SERIES
}

private data class SettingsPreferenceSnapshot(
    val providers: List<Provider>,
    val activeProviderId: Long?,
    val parentalControlLevel: Int,
    val hasParentalPin: Boolean,
    val appLanguage: String,
    val isIncognitoMode: Boolean,
    val liveTvChannelMode: LiveTvChannelMode
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupManager: BackupManager,
    private val recordingManager: RecordingManager,
    private val syncManager: SyncManager,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val playbackHistoryRepository: com.streamvault.domain.repository.PlaybackHistoryRepository
) : ViewModel() {
    private val appContext = application

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                providerRepository.getProviders(),
                preferencesRepository.lastActiveProviderId,
                preferencesRepository.parentalControlLevel,
                preferencesRepository.hasParentalPin
            ) { providers, activeId, level, hasParentalPin ->
                SettingsPreferenceSnapshot(
                    providers = providers,
                    activeProviderId = activeId,
                    parentalControlLevel = level,
                    hasParentalPin = hasParentalPin,
                    appLanguage = "system",
                    isIncognitoMode = false,
                    liveTvChannelMode = LiveTvChannelMode.COMFORTABLE
                )
            }.combine(preferencesRepository.appLanguage) { snapshot, language ->
                snapshot.copy(appLanguage = language)
            }.combine(preferencesRepository.isIncognitoMode) { snapshot, incognito ->
                snapshot.copy(isIncognitoMode = incognito)
            }.combine(preferencesRepository.liveTvChannelMode) { snapshot, liveTvChannelMode ->
                snapshot.copy(liveTvChannelMode = LiveTvChannelMode.fromStorage(liveTvChannelMode))
            }.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        providers = snapshot.providers,
                        activeProviderId = snapshot.activeProviderId,
                        parentalControlLevel = snapshot.parentalControlLevel,
                        hasParentalPin = snapshot.hasParentalPin,
                        appLanguage = snapshot.appLanguage,
                        isIncognitoMode = snapshot.isIncognitoMode,
                        liveTvChannelMode = snapshot.liveTvChannelMode
                    )
                }
            }
        }

        viewModelScope.launch {
            providerRepository.getProviders()
                .flatMapLatest { providers ->
                    if (providers.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            providers.map { provider ->
                                syncMetadataRepository.observeMetadata(provider.id).map { metadata ->
                                    provider.id to ProviderDiagnosticsUiModel(
                                        lastSyncStatus = metadata?.lastSyncStatus ?: "NONE",
                                        lastLiveSync = metadata?.lastLiveSync ?: 0L,
                                        lastMovieSync = metadata?.lastMovieSync ?: 0L,
                                        lastSeriesSync = metadata?.lastSeriesSync ?: 0L,
                                        lastEpgSync = metadata?.lastEpgSync ?: 0L,
                                        liveCount = metadata?.liveCount ?: 0,
                                        movieCount = metadata?.movieCount ?: 0,
                                        seriesCount = metadata?.seriesCount ?: 0,
                                        epgCount = metadata?.epgCount ?: 0,
                                        capabilitySummary = buildCapabilitySummary(provider),
                                        sourceLabel = when (provider.type) {
                                            ProviderType.XTREAM_CODES -> "Xtream Codes"
                                            ProviderType.M3U -> "M3U Playlist"
                                        },
                                        expirySummary = run {
                                            val expirationDate = provider.expirationDate
                                            when {
                                                expirationDate == null -> "Expiry unknown"
                                                expirationDate == Long.MAX_VALUE -> "No expiry reported"
                                                expirationDate < System.currentTimeMillis() -> "Expired"
                                                else -> "Active until ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expirationDate))}"
                                            }
                                        },
                                        connectionSummary = "${provider.maxConnections} connection(s)",
                                        archiveSummary = when (provider.type) {
                                            ProviderType.XTREAM_CODES -> "Catch-up depends on provider archive flags and replay stream ids."
                                            ProviderType.M3U -> if (provider.epgUrl.isBlank()) {
                                                "M3U replay is limited without guide coverage."
                                            } else {
                                                "M3U replay depends on channel templates and guide alignment."
                                            }
                                        }
                                    )
                                }
                            }
                        ) { pairs ->
                            pairs.toMap()
                        }
                    }
                }
                .collect { diagnosticsByProvider ->
                    _uiState.update { it.copy(diagnosticsByProvider = diagnosticsByProvider) }
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
    }

    fun setActiveProvider(providerId: Long) {
        viewModelScope.launch {
            preferencesRepository.setLastActiveProviderId(providerId)
            providerRepository.setActiveProvider(providerId)
            // Force sync on connect
            refreshProvider(providerId)
        }
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

    fun toggleIncognitoMode() {
        viewModelScope.launch {
            val current = _uiState.value.isIncognitoMode
            preferencesRepository.setIncognitoMode(!current)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            playbackHistoryRepository.clearAllHistory()
            preferencesRepository.clearAllRecentData()
            _uiState.update { it.copy(isSyncing = false, userMessage = appContext.getString(R.string.settings_history_cleared)) }
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun changePin(newPin: String) {
        viewModelScope.launch {
            preferencesRepository.setParentalPin(newPin)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_pin_changed)) }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshProvider(providerId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = providerRepository.refreshProviderData(providerId, force = true)
            val refreshedProvider = providerRepository.getProvider(providerId)
            _uiState.update { state ->
                val partialWarnings = (syncManager.syncState.value as? SyncState.Partial)?.warnings.orEmpty()
                val warningsMessage = partialWarnings
                    .take(3)
                    .joinToString(separator = ", ")
                    .ifBlank { "Some sections are incomplete." }
                state.copy(
                    isSyncing = false,
                    userMessage = when {
                        result is Result.Error -> "Refresh failed: ${result.message}"
                        refreshedProvider?.status == ProviderStatus.PARTIAL -> "Refresh completed with warnings: $warningsMessage"
                        else -> "Provider refreshed successfully"
                    },
                    syncWarningsByProvider = when {
                        result is Result.Error -> state.syncWarningsByProvider - providerId
                        refreshedProvider?.status == ProviderStatus.PARTIAL -> state.syncWarningsByProvider + (providerId to partialWarnings)
                        else -> state.syncWarningsByProvider - providerId
                    }
                )
            }
        }
    }

    fun retryWarningAction(providerId: Long, action: ProviderWarningAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val section = when (action) {
                ProviderWarningAction.EPG -> SyncRepairSection.EPG
                ProviderWarningAction.MOVIES -> SyncRepairSection.MOVIES
                ProviderWarningAction.SERIES -> SyncRepairSection.SERIES
            }
            val result = syncManager.retrySection(providerId, section)
            _uiState.update { state ->
                if (result is Result.Error) {
                    state.copy(
                        isSyncing = false,
                        userMessage = "Retry failed: ${result.message}"
                    )
                } else {
                    val currentWarnings = state.syncWarningsByProvider[providerId].orEmpty()
                    val updatedWarnings = currentWarnings.filterNot { warning ->
                        when (action) {
                            ProviderWarningAction.EPG -> warning.contains("EPG", ignoreCase = true)
                            ProviderWarningAction.MOVIES -> warning.contains("Movies", ignoreCase = true)
                            ProviderWarningAction.SERIES -> warning.contains("Series", ignoreCase = true)
                        }
                    }
                    state.copy(
                        isSyncing = false,
                        userMessage = if (updatedWarnings.isEmpty()) {
                            "Section retry succeeded. All current warnings cleared."
                        } else {
                            "Section retry succeeded."
                        },
                        syncWarningsByProvider = if (updatedWarnings.isEmpty()) {
                            state.syncWarningsByProvider - providerId
                        } else {
                            state.syncWarningsByProvider + (providerId to updatedWarnings)
                        }
                    )
                }
            }
        }
    }

    fun deleteProvider(providerId: Long) {
        viewModelScope.launch {
            providerRepository.deleteProvider(providerId)
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun exportConfig(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = backupManager.exportConfig(uriString)
            _uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    userMessage = if (result is Result.Error)
                        "Export failed: ${result.message}"
                    else "Configuration exported successfully"
                )
            }
        }
    }

    fun inspectBackup(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = backupManager.inspectBackup(uriString)
            _uiState.update { state ->
                when (result) {
                    is Result.Error -> state.copy(
                        isSyncing = false,
                        userMessage = "Import failed: ${result.message}"
                    )
                    is Result.Success -> state.copy(
                        isSyncing = false,
                        pendingBackupUri = uriString,
                        backupPreview = result.data,
                        backupImportPlan = BackupImportPlan()
                    )
                    else -> state.copy(isSyncing = false)
                }
            }
        }
    }

    fun dismissBackupPreview() {
        _uiState.update {
            it.copy(
                backupPreview = null,
                pendingBackupUri = null,
                backupImportPlan = BackupImportPlan()
            )
        }
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(conflictStrategy = strategy)) }
    }

    fun setImportPreferences(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPreferences = enabled)) }
    }

    fun setImportProviders(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importProviders = enabled)) }
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importSavedLibrary = enabled)) }
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPlaybackHistory = enabled)) }
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importMultiViewPresets = enabled)) }
    }

    fun confirmBackupImport() {
        val uriString = _uiState.value.pendingBackupUri ?: return
        val plan = _uiState.value.backupImportPlan
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = backupManager.importConfig(uriString, plan)
            _uiState.update { state ->
                val importedSummary = if (result is Result.Success) {
                    result.data.importedSections.joinToString().ifBlank { "Nothing imported" }
                } else {
                    null
                }
                state.copy(
                    isSyncing = false,
                    userMessage = if (result is Result.Error)
                        "Import failed: ${result.message}"
                    else "Configuration imported: $importedSummary",
                    backupPreview = null,
                    pendingBackupUri = null,
                    backupImportPlan = BackupImportPlan()
                )
            }
        }
    }

    fun stopRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.stopRecording(recordingId)
            _uiState.update {
                it.copy(userMessage = if (result is Result.Error) "Stop failed: ${result.message}" else "Recording stopped")
            }
        }
    }

    fun cancelRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.cancelRecording(recordingId)
            _uiState.update {
                it.copy(userMessage = if (result is Result.Error) "Cancel failed: ${result.message}" else "Recording cancelled")
            }
        }
    }

    fun deleteRecording(recordingId: String) {
        viewModelScope.launch {
            val result = recordingManager.deleteRecording(recordingId)
            _uiState.update {
                it.copy(userMessage = if (result is Result.Error) "Delete failed: ${result.message}" else "Recording deleted")
            }
        }
    }

    private fun buildCapabilitySummary(provider: Provider): String {
        return when (provider.type) {
            ProviderType.XTREAM_CODES -> {
                if (provider.epgUrl.isNotBlank()) {
                    "Xtream source with guide support. Catch-up is available when the provider exposes replay streams."
                } else {
                    "Xtream source. Catch-up depends on provider replay support."
                }
            }
            ProviderType.M3U -> {
                if (provider.epgUrl.isNotBlank()) {
                    "M3U source with XMLTV guide. Archive support depends on provider stream templates."
                } else {
                    "M3U source without guide URL. Guide and archive coverage may be limited."
                }
            }
        }
    }
}

data class ProviderDiagnosticsUiModel(
    val lastSyncStatus: String = "NONE",
    val lastLiveSync: Long = 0L,
    val lastMovieSync: Long = 0L,
    val lastSeriesSync: Long = 0L,
    val lastEpgSync: Long = 0L,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val epgCount: Int = 0,
    val capabilitySummary: String = "",
    val sourceLabel: String = "",
    val expirySummary: String = "",
    val connectionSummary: String = "",
    val archiveSummary: String = ""
)

data class SettingsUiState(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: Long? = null,
    val isSyncing: Boolean = false,
    val userMessage: String? = null,
    val syncWarningsByProvider: Map<Long, List<String>> = emptyMap(),
    val diagnosticsByProvider: Map<Long, ProviderDiagnosticsUiModel> = emptyMap(),
    val parentalControlLevel: Int = 0,
    val hasParentalPin: Boolean = false,
    val appLanguage: String = "system",
    val backupPreview: BackupPreview? = null,
    val pendingBackupUri: String? = null,
    val backupImportPlan: BackupImportPlan = BackupImportPlan(),
    val recordingItems: List<RecordingItem> = emptyList(),
    val recordingStorageState: RecordingStorageState = RecordingStorageState(),
    val isIncognitoMode: Boolean = false,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.COMFORTABLE
)
