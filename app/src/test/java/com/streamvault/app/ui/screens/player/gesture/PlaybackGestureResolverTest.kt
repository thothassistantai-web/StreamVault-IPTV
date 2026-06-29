package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.ui.geometry.Offset
import com.streamvault.domain.model.PlaybackGesturePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGestureResolverTest {

    private val liveContext = PlaybackGestureContext(
        contentType = "LIVE",
        isCatchUpPlayback = false,
        timeshiftEnabled = false,
    )

    private val movieContext = PlaybackGestureContext(
        contentType = "MOVIE",
        isCatchUpPlayback = false,
        timeshiftEnabled = false,
    )

    @Test
    fun `corner hold opens program details`() {
        val action = resolveLongPressHoldAction(
            start = Offset(10f, 10f),
            width = 1000f,
            height = 600f,
            edgePx = 56f,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.ShowProgramDetails, action)
    }

    @Test
    fun `left edge hold opens left nav panel`() {
        val action = resolveLongPressHoldAction(
            start = Offset(20f, 300f),
            width = 1000f,
            height = 600f,
            edgePx = 56f,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.ShowEdgePanel(TouchEdgePanel.LEFT_NAV), action)
    }

    @Test
    fun `center hold opens quick menu`() {
        val action = resolveLongPressHoldAction(
            start = Offset(500f, 300f),
            width = 1000f,
            height = 600f,
            edgePx = 56f,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.ShowQuickMenu, action)
    }

    @Test
    fun `two finger vertical swipe opens channel browser`() {
        val action = resolvePanSwipeAction(
            pan = Offset(0f, -120f),
            context = liveContext,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.ShowChannelBrowser, action)
    }

    @Test
    fun `two finger horizontal swipe zaps live channels`() {
        val action = resolvePanSwipeAction(
            pan = Offset(-120f, 0f),
            context = liveContext,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.ChannelNext, action)
    }

    @Test
    fun `double tap left skips backward`() {
        val action = resolveDoubleTapSkipAction(startX = 100f, width = 1000f)
        assertEquals(PlaybackAction.SkipBackward10, action)
    }

    @Test
    fun `double tap right skips forward`() {
        val action = resolveDoubleTapSkipAction(startX = 900f, width = 1000f)
        assertEquals(PlaybackAction.SkipForward10, action)
    }

    @Test
    fun `vod horizontal swipe seeks`() {
        val action = resolvePanSwipeAction(
            pan = Offset(120f, 0f),
            context = movieContext,
            preferences = PlaybackGesturePreferences(),
        )
        assertEquals(PlaybackAction.SeekBackward, action)
    }

    @Test
    fun `disabled channel swipe returns null for live tv`() {
        val action = resolveHorizontalPanAction(
            panX = -120f,
            context = liveContext,
            preferences = PlaybackGesturePreferences(swipeChannelChangeEnabled = false),
        )
        assertNull(action)
    }

    @Test
    fun `corner detection requires both edges`() {
        assertTrue(isPointerInCorner(Offset(10f, 10f), 1000f, 600f, 56f))
        assertFalse(isPointerInCorner(Offset(10f, 300f), 1000f, 600f, 56f))
    }
}
