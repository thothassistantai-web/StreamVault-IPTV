package com.streamvault.data.remote.stalker

import java.util.concurrent.ConcurrentHashMap

internal object StalkerTrafficCoordinator {
    private const val ACTIVE_PLAYBACK_RECHECK_MILLIS = 3_000L
    private val activePlaybackCountsByProvider = ConcurrentHashMap<Long, Int>()

    fun notePlaybackStarted(providerId: Long) {
        if (providerId <= 0L) return
        activePlaybackCountsByProvider.compute(providerId) { _, current ->
            (current ?: 0) + 1
        }
    }

    fun notePlaybackStopped(providerId: Long) {
        if (providerId <= 0L) return
        activePlaybackCountsByProvider.compute(providerId) { _, current ->
            when {
                current == null || current <= 1 -> null
                else -> current - 1
            }
        }
    }

    fun shouldDeferCatalogFetch(providerId: Long, now: Long = System.currentTimeMillis()): Boolean =
        deferCatalogFetchMillis(providerId, now) > 0L

    fun deferCatalogFetchMillis(providerId: Long, now: Long = System.currentTimeMillis()): Long {
        if (providerId <= 0L) return 0L
        if ((activePlaybackCountsByProvider[providerId] ?: 0) <= 0) return 0L
        return ACTIVE_PLAYBACK_RECHECK_MILLIS
    }

    internal fun resetForTests() {
        activePlaybackCountsByProvider.clear()
    }
}