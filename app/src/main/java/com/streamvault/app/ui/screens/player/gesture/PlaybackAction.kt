package com.streamvault.app.ui.screens.player.gesture

enum class TouchEdgePanel {
    NONE,
    LEFT_NAV,
    RIGHT_MEDIA,
    TOP_STATUS,
    BOTTOM_PLAYBACK,
}

sealed interface PlaybackAction {
    data object ToggleControls : PlaybackAction
    data object ShowChannelBrowser : PlaybackAction
    data object ShowMiniGuide : PlaybackAction
    data object ChannelPrevious : PlaybackAction
    data object ChannelNext : PlaybackAction
    data object SeekBackward : PlaybackAction
    data object SeekForward : PlaybackAction
    data object SkipBackward10 : PlaybackAction
    data object SkipForward10 : PlaybackAction
    data object ShowQuickMenu : PlaybackAction
    data object ShowFullGuide : PlaybackAction
    data object HideAllOverlays : PlaybackAction
    data object ShowProgramDetails : PlaybackAction
    data class ShowEdgePanel(val panel: TouchEdgePanel) : PlaybackAction
}

data class PlaybackGestureContext(
    val contentType: String,
    val isCatchUpPlayback: Boolean,
    val timeshiftEnabled: Boolean,
)

fun PlaybackGestureContext.isLiveTv(): Boolean =
    contentType == "LIVE" && !isCatchUpPlayback

fun PlaybackGestureContext.supportsSkipSeek(): Boolean =
    !isLiveTv() || timeshiftEnabled
