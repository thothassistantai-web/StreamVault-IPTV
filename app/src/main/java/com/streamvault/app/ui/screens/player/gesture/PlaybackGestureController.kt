package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.streamvault.domain.model.PlaybackGesturePreferences
import kotlin.math.abs

private class TapTracker {
    var lastTapTime: Long = 0L
}

@Composable
fun rememberPlaybackGestureModifier(
    enabled: Boolean,
    preferences: PlaybackGesturePreferences,
    context: PlaybackGestureContext,
    gesturesBlocked: Boolean,
    onAction: (PlaybackAction) -> Unit,
): Modifier {
    val tapTracker = remember { TapTracker() }
    val density = LocalDensity.current
    val edgePx = with(density) { TouchPlaybackTheme.EdgeZone.toPx() }
    val swipePx = with(density) {
        TouchPlaybackTheme.SwipeThreshold.toPx() * preferences.swipeThresholdMultiplier()
    }
    return Modifier.pointerInput(enabled, preferences, context, gesturesBlocked, edgePx, swipePx, tapTracker) {
        if (!enabled || !preferences.enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (gesturesBlocked || down.type != PointerType.Touch) return@awaitEachGesture

            val start = down.position
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            var maxPointerCount = 1
            var longPressTriggered = false
            var singlePanConsumed = false
            var twoFingerPanConsumed = false
            var cumulativeSinglePan = Offset.Zero
            var cumulativeTwoFingerPan = Offset.Zero
            var pinchHandled = false
            val longPressDeadline = down.uptimeMillis + TouchPlaybackTheme.LongPressMs

            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressedCount = event.changes.count { it.pressed }
                maxPointerCount = maxOf(maxPointerCount, pressedCount)

                if (!longPressTriggered && pressedCount == 1 &&
                    event.changes.first().uptimeMillis >= longPressDeadline
                ) {
                    resolveLongPressHoldAction(start, width, height, edgePx, preferences)?.let { action ->
                        longPressTriggered = true
                        onAction(action)
                        event.changes.forEach { it.consume() }
                        break
                    }
                }

                if (preferences.pinchZoomEnabled && pressedCount >= 2 && !pinchHandled) {
                    val zoom = event.calculateZoom()
                    if (abs(zoom - 1f) > TouchPlaybackTheme.PinchScaleThreshold) {
                        pinchHandled = true
                        onAction(
                            if (zoom > 1f) PlaybackAction.ShowFullGuide
                            else PlaybackAction.HideAllOverlays
                        )
                        event.changes.forEach { it.consume() }
                        break
                    }
                }

                if (preferences.twoFingerSwipeEnabled && pressedCount >= 2 && !pinchHandled && !twoFingerPanConsumed) {
                    cumulativeTwoFingerPan += event.calculatePan()
                    if (cumulativeTwoFingerPan.getDistance() > swipePx) {
                        resolvePanSwipeAction(cumulativeTwoFingerPan, context, preferences)?.let { action ->
                            twoFingerPanConsumed = true
                            onAction(action)
                            event.changes.forEach { it.consume() }
                            break
                        }
                    }
                } else if (pressedCount == 1 && !singlePanConsumed) {
                    cumulativeSinglePan += event.calculatePan()
                    if (cumulativeSinglePan.getDistance() > swipePx) {
                        resolveEdgeSwipeAction(
                            pan = cumulativeSinglePan,
                            start = start,
                            width = width,
                            height = height,
                            edgePx = edgePx,
                            context = context,
                            preferences = preferences,
                        )?.let { action ->
                            singlePanConsumed = true
                            onAction(action)
                            event.changes.forEach { it.consume() }
                            break
                        }
                    }
                }

                if (event.changes.all { !it.pressed }) {
                    if (!longPressTriggered && !singlePanConsumed && !twoFingerPanConsumed && !pinchHandled) {
                        when {
                            maxPointerCount >= 2 && preferences.twoFingerProgramDetailsEnabled ->
                                onAction(PlaybackAction.ShowProgramDetails)
                            maxPointerCount >= 2 -> Unit
                            else -> {
                                val now = event.changes.first().uptimeMillis
                                val isDoubleTap = preferences.doubleTapSkipEnabled &&
                                    now - tapTracker.lastTapTime <= TouchPlaybackTheme.DoubleTapWindowMs
                                if (isDoubleTap) {
                                    onAction(resolveDoubleTapSkipAction(start.x, width))
                                    tapTracker.lastTapTime = 0L
                                } else {
                                    tapTracker.lastTapTime = now
                                    onAction(PlaybackAction.ToggleControls)
                                }
                            }
                        }
                    }
                    break
                }
            } while (true)
        }
    }
}
