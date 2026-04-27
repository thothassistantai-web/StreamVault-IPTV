package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class VideoStallDetectorTest {
    private var nowMs = 0L

    @Test
    fun `does not report before initial grace`() {
        val detector = detector()
        detector.reset()
        detector.onVideoFrameRendered()
        nowMs = 3_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `reports once when position advances and frames stop`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered()
        nowMs = 9_000L

        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                currentPositionMs = 3_000L,
                bufferedDurationMs = 5_000L
            )
        ).isTrue()
        assertThat(
            detector.shouldReportStall(
                playbackState = PlaybackState.READY,
                isPlaying = true,
                currentPositionMs = 5_000L,
                bufferedDurationMs = 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `does not report while buffering paused or empty buffered`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered()
        nowMs = 9_000L

        assertThat(detector.shouldReportStall(PlaybackState.BUFFERING, true, 4_000L, 5_000L)).isFalse()
        assertThat(detector.shouldReportStall(PlaybackState.READY, false, 4_000L, 5_000L)).isFalse()
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, 4_000L, 0L)).isFalse()
    }

    @Test
    fun `new rendered frame clears stalled state`() {
        val detector = detector()
        detector.reset()
        nowMs = 1_000L
        detector.onVideoFrameRendered()
        nowMs = 9_000L
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, 3_000L, 5_000L)).isTrue()

        nowMs = 9_100L
        detector.onVideoFrameRendered()
        nowMs = 15_000L
        assertThat(detector.shouldReportStall(PlaybackState.READY, true, 6_000L, 5_000L)).isTrue()
    }

    private fun detector() = VideoStallDetector(
        nowMs = { nowMs },
        initialGraceMs = 5_000L,
        stallThresholdMs = 4_000L,
        minPositionAdvanceMs = 1_000L
    )
}

