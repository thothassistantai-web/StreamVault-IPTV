package com.streamvault.app.player

import com.streamvault.domain.model.StreamInfo

data class LivePlaybackStreamCacheKey(
    val providerId: Long,
    val channelId: Long,
    val streamUrl: String,
)

private data class CachedLivePlaybackStreamInfo(
    val streamInfo: StreamInfo,
    val savedAtMs: Long,
)

/**
 * Short-lived in-memory cache of resolved + gateway-prepared live stream info.
 * Shared by Live TV preview, EPG preview, and player zapping to avoid repeat
 * resolve/prepare work on adjacent channels and quick revisits.
 */
class LivePlaybackStreamCache(
    private val ttlMs: Long = DEFAULT_LIVE_PLAYBACK_STREAM_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = linkedMapOf<LivePlaybackStreamCacheKey, CachedLivePlaybackStreamInfo>()

    fun get(key: LivePlaybackStreamCacheKey): StreamInfo? {
        purgeExpired()
        val cached = entries[key] ?: return null
        if (nowMs() - cached.savedAtMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return cached.streamInfo
    }

    fun put(key: LivePlaybackStreamCacheKey, streamInfo: StreamInfo) {
        purgeExpired()
        entries[key] = CachedLivePlaybackStreamInfo(streamInfo, nowMs())
        while (entries.size > maxEntries) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
        }
    }

    private fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { now - it.value.savedAtMs > ttlMs }
    }

    companion object {
        const val DEFAULT_LIVE_PLAYBACK_STREAM_TTL_MS = 45_000L
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
