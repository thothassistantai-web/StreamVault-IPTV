package com.streamvault.app.ui.cache

import com.streamvault.app.ui.screens.settings.SettingsUiState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsSnapshotCache @Inject constructor() {
    private var snapshot: SettingsUiState? = null

    fun get(): SettingsUiState? = snapshot

    fun put(state: SettingsUiState) {
        snapshot = state.forCache()
    }

    fun clear() {
        snapshot = null
    }
}

internal fun SettingsUiState.forCache(): SettingsUiState = copy(
    isSyncing = false,
    syncProgress = null,
    syncingProviderName = null,
    userMessage = null,
    isRunningInternetSpeedTest = false,
    viewedCrashReport = null,
    backupPreview = null,
    pendingBackupUri = null,
    epgPendingDeleteSourceId = null,
)
