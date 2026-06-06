package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerDataSourceFactoryProviderTest {
    @Test
    fun `effective playback request properties inject explicit user agent`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf("Referer" to "https://portal.example.com/c/"),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Referer", "https://portal.example.com/c/",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties replace case insensitive user agent header`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf(
                "user-agent" to "StreamVault/1.0.12-beta",
                "Origin" to "https://portal.example.com"
            ),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Origin", "https://portal.example.com",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties preserve headers when user agent blank`() {
        val original = linkedMapOf(
            "User-Agent" to "ExistingAgent/1.0",
            "Referer" to "https://portal.example.com/c/"
        )

        val headers = effectivePlaybackRequestProperties(
            headers = original,
            userAgent = "  "
        )

        assertThat(headers).isEqualTo(original)
    }

    @Test
    fun `mpeg ts live keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.MPEG_TS_LIVE)).isFalse()
    }

    @Test
    fun `hls keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.HLS)).isFalse()
    }

    @Test
    fun `read stats wrap only live stream types`() {
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.PROGRESSIVE)).isFalse()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.DASH)).isFalse()
    }
}
