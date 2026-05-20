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
            isCurrentMediaItemLive = false,
            playbackStarted = true
        )

        assertThat(seekPosition).isEqualTo(1_695_105L)
    }

    @Test
    fun `startup retry for progressive movie does not preserve stuck resume position before first frame`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
            currentPositionMs = 1_001_654L,
            durationMs = C.TIME_UNSET,
            isCurrentMediaItemLive = false,
            playbackStarted = false
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `network retry for finite hls movie preserves current position`() {
        val seekPosition = resolveRetrySeekPositionMs(
            category = PlaybackErrorCategory.NETWORK,
            resolvedStreamType = ResolvedStreamType.HLS,
            currentPositionMs = 1_695_105L,
            durationMs = 7_200_000L,
            isCurrentMediaItemLive = false,
            playbackStarted = true
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
            isCurrentMediaItemLive = true,
            playbackStarted = true
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
            isCurrentMediaItemLive = false,
            playbackStarted = true
        )

        assertThat(seekPosition).isNull()
    }

    @Test
    fun `ready movie playback after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterReady(
            currentAttempt = 1,
            playbackStarted = true
        )

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `ready live playback after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterReady(
            currentAttempt = 1,
            playbackStarted = true
        )

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `first frame after retry restores transient retry budget`() {
        val nextAttempt = resolveRetryAttemptAfterPlaybackStarted(currentAttempt = 1)

        assertThat(nextAttempt).isEqualTo(0)
    }

    @Test
    fun `new retry category starts its own attempt budget`() {
        val nextAttempt = resolveRetryAttemptForCategory(
            currentAttempt = 1,
            lastCategory = PlaybackErrorCategory.LIVE_WINDOW,
            nextCategory = PlaybackErrorCategory.SOURCE_MALFORMED
        )

        assertThat(nextAttempt).isEqualTo(1)
    }

    @Test
    fun `same retry category increments current attempt`() {
        val nextAttempt = resolveRetryAttemptForCategory(
            currentAttempt = 2,
            lastCategory = PlaybackErrorCategory.SOURCE_MALFORMED,
            nextCategory = PlaybackErrorCategory.SOURCE_MALFORMED
        )

        assertThat(nextAttempt).isEqualTo(3)
    }
}
