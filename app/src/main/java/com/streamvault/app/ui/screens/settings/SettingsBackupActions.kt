package com.streamvault.app.ui.screens.settings

import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ExportBackupCommand
import com.streamvault.domain.usecase.ExportBackupResult
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsBackupActions(
    private val exportBackup: ExportBackup,
    private val importBackup: ImportBackup,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    fun exportConfig(scope: CoroutineScope, uriString: String, onSuccess: (() -> Unit)? = null) {
        scope.launch {
            uiState.update { it.copy(isSyncing = true) }
            val result = exportBackup(ExportBackupCommand(uriString))
            if (result is ExportBackupResult.Success) {
                onSuccess?.invoke()
            }
            uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    userMessage = if (result is ExportBackupResult.Error) {
                        "Export failed: ${result.message}"
                    } else {
                        "Configuration exported successfully"
                    }
                )
            }
        }
    }

    fun inspectBackup(scope: CoroutineScope, uriString: String) {
        scope.launch {
            uiState.update { it.copy(isSyncing = true) }
            val result = importBackup.inspect(InspectBackupCommand(uriString))
            uiState.update { state ->
                when (result) {
                    is InspectBackupResult.Error -> state.copy(
                        isSyncing = false,
                        userMessage = "Import failed: ${result.message}"
                    )
                    is InspectBackupResult.Success -> state.copy(
                        isSyncing = false,
                        pendingBackupUri = result.uriString,
                        backupPreview = result.preview,
                        backupImportPlan = result.defaultPlan
                    )
                }
            }
        }
    }

    fun dismissBackupPreview() {
        uiState.update {
            it.copy(
                backupPreview = null,
                pendingBackupUri = null,
                backupImportPlan = BackupImportPlan()
            )
        }
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(conflictStrategy = strategy)) }
    }

    fun setImportPreferences(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPreferences = enabled)) }
    }

    fun setImportProviders(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importProviders = enabled)) }
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importSavedLibrary = enabled)) }
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPlaybackHistory = enabled)) }
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importMultiViewPresets = enabled)) }
    }

    fun setImportRecordingSchedules(enabled: Boolean) {
        uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importRecordingSchedules = enabled)) }
    }

    fun confirmBackupImport(scope: CoroutineScope) {
        // Atomically capture uri+plan and mark in-flight so rapid double-taps cannot both
        // pass the guard before the flag is written. MutableStateFlow.update {} is a CAS
        // loop, so only one call wins the transition isImportingBackup=false→true.
        var capturedUri: String? = null
        var capturedPlan: BackupImportPlan? = null
        uiState.update { state ->
            if (state.isImportingBackup || state.pendingBackupUri == null) return@update state
            val plan = state.backupImportPlan
            if (!plan.importPreferences && !plan.importProviders && !plan.importSavedLibrary &&
                !plan.importPlaybackHistory && !plan.importMultiViewPresets && !plan.importRecordingSchedules
            ) {
                return@update state.copy(userMessage = "Select at least one section to import")
            }
            capturedUri = state.pendingBackupUri
            capturedPlan = plan
            state.copy(isImportingBackup = true)
        }
        val uriString = capturedUri ?: return
        val plan = capturedPlan ?: return
        scope.launch {
            val result = importBackup.confirm(ImportBackupCommand(uriString, plan))
            uiState.update { state ->
                state.copy(
                    isImportingBackup = false,
                    isSyncing = false,
                    userMessage = if (result is ImportBackupResult.Error) {
                        "Import failed: ${result.message}"
                    } else {
                        "Configuration imported: ${(result as ImportBackupResult.Success).importedSummary}"
                    },
                    backupPreview = null,
                    pendingBackupUri = null,
                    backupImportPlan = BackupImportPlan()
                )
            }
        }
    }
}
