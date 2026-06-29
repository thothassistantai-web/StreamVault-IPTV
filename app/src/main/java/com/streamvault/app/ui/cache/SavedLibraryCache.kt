package com.streamvault.app.ui.cache

import com.streamvault.app.ui.screens.favorites.FavoriteSectionUiModel
import com.streamvault.app.ui.screens.favorites.SavedGroupManagementUiModel
import com.streamvault.app.ui.screens.favorites.SavedHistoryUiModel
import com.streamvault.app.ui.screens.favorites.SavedLibraryPresetSummary
import com.streamvault.app.ui.screens.favorites.SavedLibrarySummary
import javax.inject.Inject
import javax.inject.Singleton

data class CachedSavedLibrary(
    val sections: List<FavoriteSectionUiModel>,
    val continueWatching: List<SavedHistoryUiModel>,
    val recentLive: List<SavedHistoryUiModel>,
    val managedGroups: List<SavedGroupManagementUiModel>,
    val summary: SavedLibrarySummary,
    val presetSummary: SavedLibraryPresetSummary,
    val activeProviderId: Long?,
    val activeProviderName: String?,
)

@Singleton
class SavedLibraryCache @Inject constructor() {
    private var snapshot: CachedSavedLibrary? = null

    fun get(): CachedSavedLibrary? = snapshot

    fun put(snapshot: CachedSavedLibrary) {
        this.snapshot = snapshot
    }

    fun clear() {
        snapshot = null
    }
}
