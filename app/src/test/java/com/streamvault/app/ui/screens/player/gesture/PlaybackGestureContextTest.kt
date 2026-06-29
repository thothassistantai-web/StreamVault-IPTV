package com.streamvault.app.ui.screens.player.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGestureContextTest {

    @Test
    fun `live tv excludes skip seek`() {
        val context = PlaybackGestureContext(
            contentType = "LIVE",
            isCatchUpPlayback = false,
            timeshiftEnabled = false,
        )
        assertTrue(context.isLiveTv())
        assertFalse(context.supportsSkipSeek())
    }

    @Test
    fun `catchup supports skip seek`() {
        val context = PlaybackGestureContext(
            contentType = "LIVE",
            isCatchUpPlayback = true,
            timeshiftEnabled = false,
        )
        assertFalse(context.isLiveTv())
        assertTrue(context.supportsSkipSeek())
    }

    @Test
    fun `vod supports skip seek`() {
        val context = PlaybackGestureContext(
            contentType = "MOVIE",
            isCatchUpPlayback = false,
            timeshiftEnabled = false,
        )
        assertFalse(context.isLiveTv())
        assertTrue(context.supportsSkipSeek())
    }
}
