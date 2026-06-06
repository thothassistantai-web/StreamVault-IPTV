package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerDataSourceReadStatsTrackerTest {
    @Test
    fun `does not emit before thresholds`() {
        val tracker = PlayerDataSourceReadStatsTracker(
            minLogIntervalMs = 2_000,
            minLogDeltaBytes = 1_000
        )

        tracker.open(nowMs = 100)

        assertThat(tracker.recordRead(bytesRead = 400, nowMs = 500)).isNull()
    }

    @Test
    fun `emits when byte threshold is reached`() {
        val tracker = PlayerDataSourceReadStatsTracker(
            minLogIntervalMs = 2_000,
            minLogDeltaBytes = 1_000
        )

        tracker.open(nowMs = 100)
        val snapshot = tracker.recordRead(bytesRead = 1_000, nowMs = 600)

        assertThat(snapshot).isEqualTo(
            PlayerDataSourceReadStatsSnapshot(
                totalBytes = 1_000,
                deltaBytes = 1_000,
                elapsedMs = 500,
                intervalMs = 500,
                averageKbps = 16,
                intervalKbps = 16
            )
        )
    }

    @Test
    fun `emits when time threshold is reached`() {
        val tracker = PlayerDataSourceReadStatsTracker(
            minLogIntervalMs = 2_000,
            minLogDeltaBytes = 1_000_000
        )

        tracker.open(nowMs = 100)
        val snapshot = tracker.recordRead(bytesRead = 800, nowMs = 2_100)

        assertThat(snapshot?.totalBytes).isEqualTo(800)
        assertThat(snapshot?.elapsedMs).isEqualTo(2_000)
        assertThat(snapshot?.averageKbps).isEqualTo(3)
    }

    @Test
    fun `ignores non positive reads`() {
        val tracker = PlayerDataSourceReadStatsTracker(
            minLogIntervalMs = 1,
            minLogDeltaBytes = 1
        )

        tracker.open(nowMs = 100)

        assertThat(tracker.recordRead(bytesRead = 0, nowMs = 200)).isNull()
        assertThat(tracker.recordRead(bytesRead = -1, nowMs = 300)).isNull()
        assertThat(tracker.snapshot(nowMs = 300).totalBytes).isEqualTo(0)
    }
}
