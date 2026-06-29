package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object TouchPlaybackTheme {
    val VaultGold = Color(0xFFFFB000)
    val VaultGoldMuted = Color(0x33FFB000)
    val GlassDark = Color(0xCC0A0F18)
    val GlassBorder = Color(0x1AFFFFFF)

    val TouchTargetMin = 56.dp
    val EdgeZone = 56.dp
    val SwipeThreshold = 80.dp
    val PinchScaleThreshold = 0.15f
    const val LongPressMs = 500L
    const val DoubleTapWindowMs = 300L

    val OverlaySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
