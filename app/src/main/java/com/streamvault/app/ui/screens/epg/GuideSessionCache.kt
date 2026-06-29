package com.streamvault.app.ui.screens.epg

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class GuideSessionCacheKey(
    val sourceId: Long,
    val combinedProfileId: Long?,
    val categoryId: Long,
    val anchorTime: Long,
    val windowStart: Long,
    val windowEnd: Long,
    val favoritesOnly: Boolean,
)

data class CachedGuideSession(
    val providerId: Long,
    val combinedProfileId: Long?,
    val currentProviderName: String,
    val providerSourceLabel: String,
    val providerArchiveSummary: String,
    val categories: List<Category>,
    val selectedCategoryId: Long,
    val parentalControlLevel: Int,
    val showFavoritesOnly: Boolean,
    val favoriteChannelIds: Set<Long>,
    val allChannels: List<Channel>,
    val visibleChannels: List<Channel>,
    val programsByChannel: Map<String, List<Program>>,
    val failedScheduleCount: Int,
    val channelsWithSchedule: Int,
    val guideAnchorTime: Long,
    val guideWindowStart: Long,
    val guideWindowEnd: Long,
    val hiddenCategoryIds: Set<Long>,
    val nextRawChannelOffset: Int,
    val hasMoreChannels: Boolean,
    val savedAtMs: Long,
) {
    fun isFresh(nowMs: Long, freshTtlMs: Long = GuideSessionCache.DEFAULT_FRESH_MS): Boolean =
        nowMs - savedAtMs < freshTtlMs
}

/**
 * Retains the last rendered Guide grid so revisiting the tab can paint instantly
 * while Room/network refresh runs in the background.
 */
@Singleton
class GuideSessionCache @Inject constructor() {
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    private val entries = ConcurrentHashMap<GuideSessionCacheKey, CachedGuideSession>()

    fun get(key: GuideSessionCacheKey, staleTtlMs: Long = DEFAULT_STALE_MS): CachedGuideSession? {
        val entry = entries[key] ?: return null
        return entry.takeIf { nowMs() - it.savedAtMs < staleTtlMs }
    }

    fun put(key: GuideSessionCacheKey, session: CachedGuideSession) {
        entries[key] = session
    }

    fun invalidate(key: GuideSessionCacheKey) {
        entries.remove(key)
    }

    fun invalidateForSource(sourceId: Long, combinedProfileId: Long? = null) {
        entries.keys.removeAll { entry ->
            entry.sourceId == sourceId && entry.combinedProfileId == combinedProfileId
        }
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_FRESH_MS = 3L * 60L * 1_000L
        const val DEFAULT_STALE_MS = 20L * 60L * 1_000L
    }
}
