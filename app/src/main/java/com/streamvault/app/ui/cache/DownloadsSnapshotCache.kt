package com.streamvault.app.ui.cache

import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStorageConfig
import javax.inject.Inject
import javax.inject.Singleton

data class CachedDownloadsSnapshot(
    val downloads: List<DownloadItem>,
    val storageConfig: DownloadStorageConfig,
)

@Singleton
class DownloadsSnapshotCache @Inject constructor() {
    private var snapshot: CachedDownloadsSnapshot? = null

    fun get(): CachedDownloadsSnapshot? = snapshot

    fun putDownloads(downloads: List<DownloadItem>) {
        snapshot = snapshot?.copy(downloads = downloads)
            ?: CachedDownloadsSnapshot(downloads = downloads, storageConfig = DownloadStorageConfig())
    }

    fun putStorageConfig(storageConfig: DownloadStorageConfig) {
        snapshot = snapshot?.copy(storageConfig = storageConfig)
            ?: CachedDownloadsSnapshot(downloads = emptyList(), storageConfig = storageConfig)
    }

    fun clear() {
        snapshot = null
    }
}
