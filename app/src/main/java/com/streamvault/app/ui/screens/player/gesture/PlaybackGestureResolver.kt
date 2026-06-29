package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.ui.geometry.Offset
import com.streamvault.domain.model.PlaybackGesturePreferences
import kotlin.math.abs

internal fun isPointerInCorner(
    start: Offset,
    width: Float,
    height: Float,
    edgePx: Float,
): Boolean {
    val nearHorizontalEdge = start.x <= edgePx || start.x >= width - edgePx
    val nearVerticalEdge = start.y <= edgePx || start.y >= height - edgePx
    return nearHorizontalEdge && nearVerticalEdge
}

internal fun resolveLongPressHoldAction(
    start: Offset,
    width: Float,
    height: Float,
    edgePx: Float,
    preferences: PlaybackGesturePreferences,
): PlaybackAction? {
    if (preferences.cornerHoldZonesEnabled && isPointerInCorner(start, width, height, edgePx)) {
        return PlaybackAction.ShowProgramDetails
    }
    if (preferences.edgeHoldZonesEnabled && preferences.edgePanelsEnabled) {
        when {
            start.x <= edgePx -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.LEFT_NAV)
            start.x >= width - edgePx -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.RIGHT_MEDIA)
            start.y <= edgePx -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.TOP_STATUS)
            start.y >= height - edgePx -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.BOTTOM_PLAYBACK)
        }
    }
    if (preferences.longPressQuickMenuEnabled) {
        return PlaybackAction.ShowQuickMenu
    }
    return null
}

internal fun resolveHorizontalPanAction(
    panX: Float,
    context: PlaybackGestureContext,
    preferences: PlaybackGesturePreferences,
): PlaybackAction? {
    if (panX < 0f) {
        return if (context.isLiveTv()) {
            if (!preferences.swipeChannelChangeEnabled) null else PlaybackAction.ChannelNext
        } else {
            PlaybackAction.SeekForward
        }
    }
    return if (context.isLiveTv()) {
        if (!preferences.swipeChannelChangeEnabled) null else PlaybackAction.ChannelPrevious
    } else {
        PlaybackAction.SeekBackward
    }
}

internal fun resolveVerticalPanAction(
    panY: Float,
    preferences: PlaybackGesturePreferences,
): PlaybackAction? {
    if (!preferences.swipeOverlayNavigationEnabled) return null
    return if (panY < 0f) {
        PlaybackAction.ShowChannelBrowser
    } else {
        PlaybackAction.ShowMiniGuide
    }
}

internal fun resolvePanSwipeAction(
    pan: Offset,
    context: PlaybackGestureContext,
    preferences: PlaybackGesturePreferences,
): PlaybackAction? {
    val absX = abs(pan.x)
    val absY = abs(pan.y)
    return if (absY > absX) {
        resolveVerticalPanAction(pan.y, preferences)
    } else {
        resolveHorizontalPanAction(pan.x, context, preferences)
    }
}

internal fun resolveEdgeSwipeAction(
    pan: Offset,
    start: Offset,
    width: Float,
    height: Float,
    edgePx: Float,
    context: PlaybackGestureContext,
    preferences: PlaybackGesturePreferences,
): PlaybackAction? {
    val absX = abs(pan.x)
    val absY = abs(pan.y)
    if (absY > absX) {
        return resolveVerticalPanAction(pan.y, preferences)
    }

    val fromLeftEdge = start.x <= edgePx
    val fromRightEdge = start.x >= width - edgePx
    val fromTopEdge = start.y <= edgePx
    val fromBottomEdge = start.y >= height - edgePx

    if (preferences.edgePanelsEnabled) {
        when {
            fromLeftEdge && pan.x > 0f -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.LEFT_NAV)
            fromRightEdge && pan.x < 0f -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.RIGHT_MEDIA)
            fromTopEdge && pan.y > 0f -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.TOP_STATUS)
            fromBottomEdge && pan.y < 0f -> return PlaybackAction.ShowEdgePanel(TouchEdgePanel.BOTTOM_PLAYBACK)
        }
    }
    return resolveHorizontalPanAction(pan.x, context, preferences)
}

internal fun resolveDoubleTapSkipAction(
    startX: Float,
    width: Float,
): PlaybackAction {
    val isLeft = startX < width / 2f
    return if (isLeft) PlaybackAction.SkipBackward10 else PlaybackAction.SkipForward10
}
