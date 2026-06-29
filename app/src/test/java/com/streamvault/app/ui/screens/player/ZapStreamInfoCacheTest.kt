package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.player.LivePlaybackStreamCache
import com.streamvault.app.player.LivePlaybackStreamCacheKey
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class LivePlaybackStreamCacheTest {
    private var nowMs = 0L

    @Test
    fun `returns cached stream info within ttl`() {
        val cache = LivePlaybackStreamCache(nowMs = { nowMs })
        val key = LivePlaybackStreamCacheKey(providerId = 1L, channelId = 51L, streamUrl = "http://127.0.0.1:3000/stream.m3u8")
        val info = StreamInfo(url = key.streamUrl, title = "ESPN")

        cache.put(key, info)

        assertThat(cache.get(key)).isEqualTo(info)
    }

    @Test
    fun `expires cached stream info after ttl`() {
        val cache = LivePlaybackStreamCache(
            ttlMs = 1_000L,
            nowMs = { nowMs },
        )
        val key = LivePlaybackStreamCacheKey(providerId = 1L, channelId = 51L, streamUrl = "http://127.0.0.1:3000/stream.m3u8")
        cache.put(key, StreamInfo(url = key.streamUrl, title = "ESPN"))

        nowMs = 1_500L
        assertThat(cache.get(key)).isNull()
    }
}
