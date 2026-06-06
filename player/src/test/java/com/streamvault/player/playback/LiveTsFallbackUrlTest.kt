package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveTsFallbackUrlTest {

    @Test
    fun `live hls path is converted to transport stream path`() {
        val fallback = buildLiveTsFallbackUrl("https://example.test/live/user/pass/61351.m3u8")

        assertThat(fallback).isEqualTo("https://example.test/live/user/pass/61351.ts")
    }

    @Test
    fun `live hls query extension is converted to transport stream query extension`() {
        val fallback = buildLiveTsFallbackUrl("https://example.test/live/user/pass/61351?ext=m3u8")

        assertThat(fallback).isEqualTo("https://example.test/live/user/pass/61351?ext=ts")
    }

    @Test
    fun `non hls live url is not converted`() {
        assertThat(buildLiveTsFallbackUrl("https://example.test/live/user/pass/61351.ts")).isNull()
    }

    @Test
    fun `non live hls url is not converted`() {
        assertThat(buildLiveTsFallbackUrl("https://example.test/movie/user/pass/61351.m3u8")).isNull()
    }

    @Test
    fun `malformed hls can use transport stream fallback`() {
        assertThat(
            shouldFallbackMalformedHlsToLiveTs(
                category = PlaybackErrorCategory.SOURCE_MALFORMED,
                resolvedStreamType = ResolvedStreamType.HLS,
                playbackStarted = false
            )
        ).isTrue()
    }

    @Test
    fun `malformed hls does not switch to transport stream after playback started`() {
        assertThat(
            shouldFallbackMalformedHlsToLiveTs(
                category = PlaybackErrorCategory.SOURCE_MALFORMED,
                resolvedStreamType = ResolvedStreamType.HLS,
                playbackStarted = true
            )
        ).isFalse()
    }

    @Test
    fun `stalled hls can use transport stream fallback after bounded recoveries`() {
        assertThat(
            shouldFallbackStalledHlsToLiveTs(
                resolvedStreamType = ResolvedStreamType.HLS,
                recoveryAttempt = 2
            )
        ).isTrue()
        assertThat(
            shouldFallbackStalledHlsToLiveTs(
                resolvedStreamType = ResolvedStreamType.HLS,
                recoveryAttempt = 1
            )
        ).isFalse()
        assertThat(
            shouldFallbackStalledHlsToLiveTs(
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 3
            )
        ).isFalse()
    }
}
