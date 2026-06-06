package com.streamvault.app.ui.screens.downloads

import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStorageConfig

/**
 * UI state for the Downloads screen.
 */
data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val isLoading: Boolean = true,
    val storageConfig: DownloadStorageConfig = DownloadStorageConfig(),
    val userMessage: String? = null,
    val deleteConfirmItem: DownloadItem? = null
)
