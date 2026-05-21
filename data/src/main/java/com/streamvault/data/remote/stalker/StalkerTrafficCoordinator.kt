package com.streamvault.data.remote.stalker

import java.util.concurrent.ConcurrentHashMap

internal object StalkerTrafficCoordinator {
    private const val PLAYBACK_PROTECTION_WINDOW_MILLIS = 90_000L
    private const val ACTIVE_PLAYBACK_RECHECK_MILLIS = 5_000L
    private val recentPlaybackByProvider = ConcurrentHashMap<Long, Long>()
    private val activePlaybackCountByProvider = ConcurrentHashMap<Long, Int>()

    fun notePlaybackActivity(providerId: Long, now: Long = System.currentTimeMillis()) {
        if (providerId <= 0L) return
        recentPlaybackByProvider[providerId] = now
    }

    fun notePlaybackStarted(providerId: Long, now: Long = System.currentTimeMillis()) {
        if (providerId <= 0L) return
        recentPlaybackByProvider[providerId] = now
        activePlaybackCountByProvider.compute(providerId) { _, existing ->
            (existing ?: 0) + 1
        }
    }

    fun notePlaybackStopped(providerId: Long) {
        if (providerId <= 0L) return
        activePlaybackCountByProvider.compute(providerId) { _, existing ->
            when {
                existing == null || existing <= 1 -> null
                else -> existing - 1
            }
        }
        if ((activePlaybackCountByProvider[providerId] ?: 0) <= 0) {
            activePlaybackCountByProvider.remove(providerId)
            recentPlaybackByProvider.remove(providerId)
        }
    }

    fun shouldDeferCatalogFetch(providerId: Long, now: Long = System.currentTimeMillis()): Boolean =
        deferCatalogFetchMillis(providerId, now) > 0L

    fun deferCatalogFetchMillis(providerId: Long, now: Long = System.currentTimeMillis()): Long {
        if (providerId <= 0L) return 0L
        if ((activePlaybackCountByProvider[providerId] ?: 0) > 0) {
            return ACTIVE_PLAYBACK_RECHECK_MILLIS
        }
        val lastPlaybackAt = recentPlaybackByProvider[providerId] ?: return 0L
        val remaining = (lastPlaybackAt + PLAYBACK_PROTECTION_WINDOW_MILLIS) - now
        if (remaining <= 0L) {
            recentPlaybackByProvider.remove(providerId, lastPlaybackAt)
            return 0L
        }
        return remaining
    }

    internal fun resetForTests() {
        recentPlaybackByProvider.clear()
        activePlaybackCountByProvider.clear()
    }
}