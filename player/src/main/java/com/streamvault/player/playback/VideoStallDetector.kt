package com.streamvault.player.playback

import com.streamvault.player.PlaybackState

class VideoStallDetector(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val initialGraceMs: Long = 8_000L,
    private val stallThresholdMs: Long = 5_000L,
    private val minPositionAdvanceMs: Long = 1_500L
) {
    private var startedAtMs: Long = 0L
    private var lastFrameAtMs: Long = 0L
    private var lastPositionMs: Long = 0L
    private var lastPositionCheckMs: Long = 0L
    private var stalled = false

    fun reset() {
        val now = nowMs()
        startedAtMs = now
        lastFrameAtMs = 0L
        lastPositionMs = 0L
        lastPositionCheckMs = now
        stalled = false
    }

    fun onVideoFrameRendered() {
        lastFrameAtMs = nowMs()
        stalled = false
    }

    fun lastVideoFrameAgoMs(): Long {
        val frameAt = lastFrameAtMs
        return if (frameAt <= 0L) 0L else (nowMs() - frameAt).coerceAtLeast(0L)
    }

    fun shouldReportStall(
        playbackState: PlaybackState,
        isPlaying: Boolean,
        currentPositionMs: Long,
        bufferedDurationMs: Long
    ): Boolean {
        val now = nowMs()
        if (playbackState != PlaybackState.READY || !isPlaying || bufferedDurationMs <= 1_000L) {
            lastPositionMs = currentPositionMs
            lastPositionCheckMs = now
            stalled = false
            return false
        }
        if (now - startedAtMs < initialGraceMs || lastFrameAtMs <= 0L) return false

        val positionAdvanced = currentPositionMs - lastPositionMs >= minPositionAdvanceMs
        val checkWindowElapsed = now - lastPositionCheckMs >= minPositionAdvanceMs
        if (checkWindowElapsed) {
            lastPositionMs = currentPositionMs
            lastPositionCheckMs = now
        }

        val frameSilent = now - lastFrameAtMs >= stallThresholdMs
        val nextStalled = positionAdvanced && frameSilent
        if (nextStalled && !stalled) {
            stalled = true
            return true
        }
        if (!frameSilent) stalled = false
        return false
    }
}

