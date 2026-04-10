package com.streamvault.app.ui.screens.settings

import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

internal class SettingsProviderActions(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val preferencesRepository: PreferencesRepository,
    private val syncProvider: SyncProvider,
    private val syncManager: SyncManager,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    private companion object {
        val AUTO_SWITCH_SYNC_STALE_AFTER_MS = 24.hours.inWholeMilliseconds
    }

    fun setActiveProvider(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            preferencesRepository.setLastActiveProviderId(providerId)
            combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(providerId))
            providerRepository.setActiveProvider(providerId)
            val provider = providerRepository.getProvider(providerId)
            val shouldAutoSync = provider?.let { currentProvider ->
                val lastSyncedAt = currentProvider.lastSyncedAt
                lastSyncedAt <= 0L || System.currentTimeMillis() - lastSyncedAt >= AUTO_SWITCH_SYNC_STALE_AFTER_MS
            } ?: false
            if (shouldAutoSync) {
                refreshProvider(
                    scope = scope,
                    providerId = providerId,
                    syncMode = SettingsProviderSyncMode.QUICK,
                    progressPrefix = "Refreshing ${provider?.name ?: "provider"}..."
                )
            }
        }
    }

    fun setActiveCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            when (combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source activated") }
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not activate combined source") }
                Result.Loading -> Unit
            }
        }
    }

    fun createCombinedProfile(scope: CoroutineScope, name: String, providerIds: List<Long>) {
        scope.launch {
            when (val result = combinedM3uRepository.createProfile(name, providerIds)) {
                is Result.Success -> {
                    combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(result.data.id))
                    uiState.update { it.copy(userMessage = "Combined M3U source created") }
                }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun deleteCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.deleteProfile(profileId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source deleted") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun addProviderToCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.addProvider(profileId, providerId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Playlist added to combined source") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun renameCombinedProfile(scope: CoroutineScope, profileId: Long, name: String) {
        scope.launch {
            when (val result = combinedM3uRepository.updateProfileName(profileId, name)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source renamed") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun removeProviderFromCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.removeProvider(profileId, providerId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Playlist removed from combined source") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun moveCombinedProvider(scope: CoroutineScope, profileId: Long, providerId: Long, moveUp: Boolean) {
        scope.launch {
            val profile = uiState.value.combinedProfiles.firstOrNull { it.id == profileId } ?: return@launch
            val orderedProviderIds = profile.members.sortedBy { it.priority }.map { it.providerId }.toMutableList()
            val currentIndex = orderedProviderIds.indexOf(providerId)
            if (currentIndex == -1) return@launch
            val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex !in orderedProviderIds.indices) return@launch
            java.util.Collections.swap(orderedProviderIds, currentIndex, targetIndex)
            when (val result = combinedM3uRepository.reorderMembers(profileId, orderedProviderIds)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined playlist order updated") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setCombinedProviderEnabled(scope: CoroutineScope, profileId: Long, providerId: Long, enabled: Boolean) {
        scope.launch {
            when (val result = combinedM3uRepository.setMemberEnabled(profileId, providerId, enabled)) {
                is Result.Success -> uiState.update {
                    it.copy(userMessage = if (enabled) "Playlist enabled in combined source" else "Playlist disabled in combined source")
                }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setM3uVodClassificationEnabled(scope: CoroutineScope, providerId: Long, enabled: Boolean) {
        scope.launch {
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            if (provider.type != ProviderType.M3U) return@launch
            when (val result = providerRepository.updateProvider(provider.copy(m3uVodClassificationEnabled = enabled))) {
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not save provider setting: ${result.message}") }
                else -> uiState.update {
                    it.copy(
                        userMessage = if (enabled) {
                            "M3U VOD classification enabled. Refresh the playlist to reclassify content."
                        } else {
                            "M3U VOD classification disabled. Refresh the playlist to reclassify content."
                        }
                    )
                }
            }
        }
    }

    fun refreshProvider(
        scope: CoroutineScope,
        providerId: Long,
        syncMode: SettingsProviderSyncMode = SettingsProviderSyncMode.QUICK,
        progressPrefix: String? = null
    ) {
        scope.launch {
            val providerName = providerRepository.getProvider(providerId)?.name
            uiState.update {
                it.copy(
                    isSyncing = true,
                    syncingProviderName = providerName,
                    syncProgress = progressPrefix ?: "Preparing sync..."
                )
            }
            try {
                when (syncMode) {
                    SettingsProviderSyncMode.QUICK -> runQuickSync(providerId, providerName)
                    SettingsProviderSyncMode.FULL -> runFullSync(providerId, providerName)
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncProgress = null,
                        syncingProviderName = null,
                        userMessage = "Sync failed: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun runQuickSync(providerId: Long, providerName: String?) {
        val beforeMetadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        val result = syncProvider(
            SyncProviderCommand(
                providerId = providerId,
                force = false,
                movieFastSyncOverride = true,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
            ),
            onProgress = { message ->
                uiState.update { state ->
                    state.copy(
                        syncProgress = mapQuickSyncProgress(message),
                        syncingProviderName = providerName
                    )
                }
            }
        )

        if (result !is SyncProviderResult.Error) {
            val afterMetadata = syncMetadataRepository.getMetadata(providerId) ?: beforeMetadata
            val liveRefreshed = afterMetadata.lastLiveSync > beforeMetadata.lastLiveSync ||
                afterMetadata.liveCount != beforeMetadata.liveCount
            val catalogRefreshed = liveRefreshed ||
                afterMetadata.lastMovieSync > beforeMetadata.lastMovieSync ||
                afterMetadata.lastSeriesSync > beforeMetadata.lastSeriesSync

            if (liveRefreshed) {
                uiState.update { state ->
                    state.copy(
                        syncProgress = "Updating TV integration...",
                        syncingProviderName = providerName
                    )
                }
                tvInputChannelSyncManager.refreshTvInputCatalog()
            } else if (!catalogRefreshed) {
                uiState.update { state ->
                    state.copy(
                        syncProgress = "Library already up to date.",
                        syncingProviderName = providerName
                    )
                }
            }

            uiState.update { state ->
                state.copy(
                    syncProgress = "Scheduling EPG refresh...",
                    syncingProviderName = providerName
                )
            }
            syncManager.scheduleBackgroundEpgSync(providerId)
        }

        uiState.update { state ->
            val partialWarnings = (result as? SyncProviderResult.Success)?.warnings.orEmpty()
            val warningsMessage = partialWarnings.take(3).joinToString(separator = ", ").ifBlank { "Some sections are incomplete." }
            val afterMetadata = syncMetadataRepository.getMetadata(providerId) ?: beforeMetadata
            val catalogRefreshed = afterMetadata.lastLiveSync > beforeMetadata.lastLiveSync ||
                afterMetadata.lastMovieSync > beforeMetadata.lastMovieSync ||
                afterMetadata.lastSeriesSync > beforeMetadata.lastSeriesSync ||
                afterMetadata.liveCount != beforeMetadata.liveCount
            state.copy(
                isSyncing = false,
                syncProgress = null,
                syncingProviderName = null,
                userMessage = when {
                    result is SyncProviderResult.Error -> "Quick sync failed: ${result.message}"
                    (result as? SyncProviderResult.Success)?.isPartial == true -> "Quick sync completed with warnings: $warningsMessage"
                    !catalogRefreshed -> "Library already up to date"
                    else -> "Quick sync completed"
                },
                syncWarningsByProvider = when {
                    result is SyncProviderResult.Error -> state.syncWarningsByProvider - providerId
                    (result as? SyncProviderResult.Success)?.isPartial == true -> state.syncWarningsByProvider + (providerId to partialWarnings)
                    else -> state.syncWarningsByProvider - providerId
                }
            )
        }
    }

    private suspend fun runFullSync(providerId: Long, providerName: String?) {
        val result = syncProvider(
            SyncProviderCommand(
                providerId = providerId,
                force = true,
                movieFastSyncOverride = null,
                epgSyncModeOverride = null
            ),
            onProgress = { message ->
                uiState.update { state ->
                    state.copy(syncProgress = message, syncingProviderName = providerName)
                }
            }
        )
        if (result !is SyncProviderResult.Error) {
            tvInputChannelSyncManager.refreshTvInputCatalog()
        }
        uiState.update { state ->
            val partialWarnings = (result as? SyncProviderResult.Success)?.warnings.orEmpty()
            val warningsMessage = partialWarnings.take(3).joinToString(separator = ", ").ifBlank { "Some sections are incomplete." }
            state.copy(
                isSyncing = false,
                syncProgress = null,
                syncingProviderName = null,
                userMessage = when {
                    result is SyncProviderResult.Error -> "Refresh failed: ${result.message}"
                    (result as? SyncProviderResult.Success)?.isPartial == true -> "Refresh completed with warnings: $warningsMessage"
                    else -> "Provider refreshed successfully"
                },
                syncWarningsByProvider = when {
                    result is SyncProviderResult.Error -> state.syncWarningsByProvider - providerId
                    (result as? SyncProviderResult.Success)?.isPartial == true -> state.syncWarningsByProvider + (providerId to partialWarnings)
                    else -> state.syncWarningsByProvider - providerId
                }
            )
        }
    }

    private fun mapQuickSyncProgress(message: String): String = when (message) {
        "Downloading Movies..." -> "Checking Movies..."
        "Downloading Series..." -> "Checking Series..."
        else -> message
    }

    fun deleteProvider(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            providerRepository.deleteProvider(providerId)
        }
    }
}

enum class SettingsProviderSyncMode {
    QUICK,
    FULL
}
