package com.streamvault.player.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerRetrySeekPositionPolicyTest {

    @Test
    fun `network retry for progressive movie preserves current position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_695_105L,
            durationMs = C.TIME_UNSET,
            isCurrentMediaItemLive = false
        )

        assertThat(seekPosition).isEqualTo(1_695_105L)
    }

    @Test
    fun `network retry for finite hls movie preserves current position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.HLS,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = false
        )

        assertThat(seekPosition).isEqualTo(1_695_105L)
    }

    @Test
    fun `network retry for live hls does not seek into the live window`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.HLS,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = true
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `live window retry does not preserve stale position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.LIVE_WINDOW,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = false
        )

        assertThat(seekPosition).isNull()
    }
}
