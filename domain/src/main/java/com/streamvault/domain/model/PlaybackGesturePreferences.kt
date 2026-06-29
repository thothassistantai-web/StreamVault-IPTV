package com.streamvault.domain.model

data class PlaybackGesturePreferences(
    val enabled: Boolean = true,
    val swipeChannelChangeEnabled: Boolean = true,
    val swipeOverlayNavigationEnabled: Boolean = true,
    val pinchZoomEnabled: Boolean = true,
    val doubleTapSkipEnabled: Boolean = true,
    val longPressQuickMenuEnabled: Boolean = true,
    val edgePanelsEnabled: Boolean = true,
    val twoFingerSwipeEnabled: Boolean = true,
    val twoFingerProgramDetailsEnabled: Boolean = true,
    val edgeHoldZonesEnabled: Boolean = true,
    val cornerHoldZonesEnabled: Boolean = true,
    val swipeSensitivityPercent: Int = DEFAULT_SWIPE_SENSITIVITY_PERCENT,
) {
    fun swipeThresholdMultiplier(): Float =
        DEFAULT_SWIPE_SENSITIVITY_PERCENT.toFloat() / swipeSensitivityPercent.coerceIn(MIN_SWIPE_SENSITIVITY, MAX_SWIPE_SENSITIVITY)

    companion object {
        const val MIN_SWIPE_SENSITIVITY = 50
        const val MAX_SWIPE_SENSITIVITY = 150
        const val DEFAULT_SWIPE_SENSITIVITY_PERCENT = 100
    }
}
