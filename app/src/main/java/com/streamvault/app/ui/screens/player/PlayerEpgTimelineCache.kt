package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.Program
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class CachedPlayerEpgTimeline(
    val programs: List<Program>,
    val savedAtMs: Long,
)

/**
 * Caches the player overlay programme timeline so zapping back to a channel or
 * reopening the mini-guide does not reload from scratch.
 */
@Singleton
class PlayerEpgTimelineCache @Inject constructor() {
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    private val entries = ConcurrentHashMap<EpgRequestKey, CachedPlayerEpgTimeline>()

    fun getFresh(key: EpgRequestKey, freshTtlMs: Long = DEFAULT_FRESH_MS): List<Program>? {
        val entry = entries[key] ?: return null
        return entry.programs.takeIf { nowMs() - entry.savedAtMs < freshTtlMs }
    }

    fun getStale(key: EpgRequestKey, staleTtlMs: Long = DEFAULT_STALE_MS): List<Program>? {
        val entry = entries[key] ?: return null
        return entry.programs.takeIf { nowMs() - entry.savedAtMs < staleTtlMs }
    }

    fun put(key: EpgRequestKey, programs: List<Program>) {
        if (programs.isEmpty()) return
        entries[key] = CachedPlayerEpgTimeline(programs, nowMs())
    }

    fun clear() {
        entries.clear()
    }

    fun clearForProvider(providerId: Long) {
        entries.keys.removeAll { it.providerId == providerId }
    }

    companion object {
        const val DEFAULT_FRESH_MS = 30L * 1_000L
        const val DEFAULT_STALE_MS = 10L * 60L * 1_000L
    }
}
