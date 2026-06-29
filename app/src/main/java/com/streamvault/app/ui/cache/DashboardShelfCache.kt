package com.streamvault.app.ui.cache

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Series
import com.streamvault.app.ui.screens.dashboard.DashboardFeature
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardShelfCache @Inject constructor() {
    companion object {
        const val STALE_AFTER_MS = 5 * 60 * 1000L
    }

    data class CachedSnapshot(
        val favoriteChannels: List<Channel> = emptyList(),
        val recentChannels: List<Channel> = emptyList(),
        val continueWatching: List<PlaybackHistory> = emptyList(),
        val continueWatchingSeries: List<Series> = emptyList(),
        val continueWatchingMovies: List<PlaybackHistory> = emptyList(),
        val continueWatchingSeriesItems: List<PlaybackHistory> = emptyList(),
        val continueWatchingDegraded: Boolean = false,
        val favoriteMovies: List<Movie> = emptyList(),
        val favoriteSeries: List<Series> = emptyList(),
        val recentMovies: List<Movie> = emptyList(),
        val recentSeries: List<Series> = emptyList(),
        val topRatedMovies: List<Movie> = emptyList(),
        val recommendedMovies: List<Movie> = emptyList(),
        val feature: DashboardFeature = DashboardFeature(),
        val cachedAtMillis: Long = System.currentTimeMillis()
    )

    private val entries = ConcurrentHashMap<String, CachedSnapshot>()

    fun get(sourceKey: String): CachedSnapshot? = entries[sourceKey]

    fun put(sourceKey: String, snapshot: CachedSnapshot) {
        entries[sourceKey] = snapshot.copy(cachedAtMillis = System.currentTimeMillis())
    }

    fun isStale(sourceKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cachedAt = entries[sourceKey]?.cachedAtMillis ?: return true
        return nowMillis - cachedAt >= STALE_AFTER_MS
    }

    fun clear() {
        entries.clear()
    }

    fun clearForSource(sourceKey: String) {
        entries.remove(sourceKey)
    }
}
