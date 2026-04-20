package com.streamvault.domain.manager

import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingRecurrence
import kotlinx.coroutines.flow.Flow
import com.streamvault.domain.model.Result

data class BackupData(
    val version: Int = 5,
    val checksum: String? = null,
    val preferences: Map<String, String>? = null,
    val providers: List<Provider>? = null,
    val favorites: List<com.streamvault.domain.model.Favorite>? = null,
    val virtualGroups: List<com.streamvault.domain.model.VirtualGroup>? = null,
    val playbackHistory: List<PlaybackHistory>? = null,
    val multiViewPresets: Map<String, List<Long>>? = null,
    val protectedCategories: List<ProtectedCategoryBackup>? = null,
    val scheduledRecordings: List<ScheduledRecordingBackup>? = null
)

data class ProtectedCategoryBackup(
    val providerServerUrl: String,
    val providerUsername: String,
    val providerStalkerMacAddress: String? = null,
    val categoryId: Long,
    val categoryName: String,
    val type: ContentType
)

data class ScheduledRecordingBackup(
    val providerServerUrl: String,
    val providerUsername: String,
    val providerStalkerMacAddress: String? = null,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val programTitle: String? = null,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE
)

enum class BackupConflictStrategy {
    KEEP_EXISTING,
    REPLACE_EXISTING
}

data class BackupPreview(
    val version: Int,
    val providerCount: Int,
    val favoriteCount: Int,
    val groupCount: Int,
    val playbackHistoryCount: Int,
    val multiViewPresetCount: Int,
    val preferenceCount: Int,
    val protectedCategoryCount: Int,
    val scheduledRecordingCount: Int,
    val providerConflicts: Int,
    val favoriteConflicts: Int,
    val groupConflicts: Int,
    val historyConflicts: Int,
    val protectedCategoryConflicts: Int,
    val recordingConflicts: Int
)

data class BackupImportPlan(
    val importPreferences: Boolean = true,
    val importProviders: Boolean = true,
    val importSavedLibrary: Boolean = true,
    val importPlaybackHistory: Boolean = true,
    val importMultiViewPresets: Boolean = true,
    val importRecordingSchedules: Boolean = true,
    val conflictStrategy: BackupConflictStrategy = BackupConflictStrategy.KEEP_EXISTING
)

data class BackupImportResult(
    val importedSections: List<String> = emptyList(),
    val skippedSections: List<String> = emptyList()
)

interface BackupManager {
    /**
     * Exports the configuration to the provided URI string (SAF document URI)
     */
    suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit>

    /**
     * Reads a backup and returns a preview with conflict counts before importing.
     */
    suspend fun inspectBackup(uriString: String): Result<BackupPreview>

    /**
     * Imports the configuration from the provided URI string (SAF document URI)
     */
    suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan = BackupImportPlan()
    ): Result<BackupImportResult>
}
