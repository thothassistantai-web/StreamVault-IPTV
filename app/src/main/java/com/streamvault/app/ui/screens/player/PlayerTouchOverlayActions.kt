package com.streamvault.app.ui.screens.player

import com.streamvault.app.ui.screens.player.gesture.PlaybackAction
import com.streamvault.app.ui.screens.player.gesture.PlaybackGestureContext
import com.streamvault.app.ui.screens.player.gesture.PlayerOverlayType
import com.streamvault.app.ui.screens.player.gesture.TouchEdgePanel
import com.streamvault.app.ui.screens.player.gesture.isLiveTv
import com.streamvault.app.ui.screens.player.gesture.supportsSkipSeek
import com.streamvault.domain.model.ContentType

fun PlayerViewModel.showManagedOverlay(type: PlayerOverlayType) {
    when (type) {
        PlayerOverlayType.HUD -> Unit
        PlayerOverlayType.CONTROLS -> {
            dismissManagedTouchOverlays(keepControls = true)
            if (!showControlsFlow.value) {
                showControlsFlow.value = true
            }
        }
        PlayerOverlayType.MINI_GUIDE -> {
            dismissManagedTouchOverlays()
            showMiniGuideOverlayFlow.value = true
            scheduleLiveOverlayAutoHide()
        }
        PlayerOverlayType.CHANNEL_BROWSER -> openChannelListOverlay()
        PlayerOverlayType.QUICK_MENU -> {
            dismissManagedTouchOverlays()
            showQuickMenuOverlayFlow.value = true
            scheduleLiveOverlayAutoHide()
        }
        PlayerOverlayType.PROGRAM_DETAILS -> {
            dismissManagedTouchOverlays()
            showProgramDetailsOverlayFlow.value = true
            scheduleLiveOverlayAutoHide()
        }
        PlayerOverlayType.FULL_GUIDE -> openFullGuideOverlay()
        PlayerOverlayType.EDGE_PANEL -> Unit
    }
}

fun PlayerViewModel.showTouchEdgePanel(panel: TouchEdgePanel) {
    dismissManagedTouchOverlays()
    touchEdgePanelFlow.value = panel
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.dismissTouchEdgePanel() {
    touchEdgePanelFlow.value = TouchEdgePanel.NONE
    if (!hasVisibleTransientLiveOverlay() && !showQuickMenuOverlayFlow.value &&
        !showMiniGuideOverlayFlow.value && !showProgramDetailsOverlayFlow.value
    ) {
        clearLiveOverlayAutoHide()
    }
}

fun PlayerViewModel.dismissManagedTouchOverlays(keepControls: Boolean = false) {
    showMiniGuideOverlayFlow.value = false
    showQuickMenuOverlayFlow.value = false
    showProgramDetailsOverlayFlow.value = false
    touchEdgePanelFlow.value = TouchEdgePanel.NONE
    showChannelInfoOverlayFlow.value = false
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    channelInfoHideJob?.cancel()
    if (!keepControls) {
        showControlsFlow.value = false
        clearSeekPreview()
    }
}

fun PlayerViewModel.hideAllTouchOverlays() {
    dismissManagedTouchOverlays()
    showDiagnosticsFlow.value = false
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.closeMiniGuideOverlay() {
    showMiniGuideOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.closeQuickMenuOverlay() {
    showQuickMenuOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.closeProgramDetailsOverlay() {
    showProgramDetailsOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.handlePlaybackAction(
    action: PlaybackAction,
    context: PlaybackGestureContext,
) {
    notifyUserActivity()
    when (action) {
        PlaybackAction.ToggleControls -> {
            if (showControlsFlow.value) {
                showControlsFlow.value = false
                clearSeekPreview()
            } else {
                showManagedOverlay(PlayerOverlayType.CONTROLS)
            }
        }
        PlaybackAction.ShowChannelBrowser -> {
            if (context.contentType == ContentType.LIVE.name) {
                showManagedOverlay(PlayerOverlayType.CHANNEL_BROWSER)
            }
        }
        PlaybackAction.ShowMiniGuide -> {
            if (context.contentType == ContentType.LIVE.name) {
                showManagedOverlay(PlayerOverlayType.MINI_GUIDE)
            }
        }
        PlaybackAction.ChannelPrevious -> {
            if (context.isLiveTv()) playPrevious() else seekBackward()
        }
        PlaybackAction.ChannelNext -> {
            if (context.isLiveTv()) playNext() else seekForward()
        }
        PlaybackAction.SeekBackward -> seekBackward()
        PlaybackAction.SeekForward -> seekForward()
        PlaybackAction.SkipBackward10 -> {
            if (context.supportsSkipSeek()) {
                notifyUserActivity()
                playerEngine.seekBackward(10_000)
            }
        }
        PlaybackAction.SkipForward10 -> {
            if (context.supportsSkipSeek()) {
                notifyUserActivity()
                playerEngine.seekForward(10_000)
            }
        }
        PlaybackAction.ShowQuickMenu -> showManagedOverlay(PlayerOverlayType.QUICK_MENU)
        PlaybackAction.ShowFullGuide -> {
            if (context.contentType == ContentType.LIVE.name) {
                showManagedOverlay(PlayerOverlayType.FULL_GUIDE)
            }
        }
        PlaybackAction.HideAllOverlays -> hideAllTouchOverlays()
        PlaybackAction.ShowProgramDetails -> showManagedOverlay(PlayerOverlayType.PROGRAM_DETAILS)
        is PlaybackAction.ShowEdgePanel -> {
            if (action.panel != TouchEdgePanel.NONE) {
                showTouchEdgePanel(action.panel)
            } else {
                dismissTouchEdgePanel()
            }
        }
    }
}

fun PlayerViewModel.hasManagedTouchOverlayVisible(): Boolean =
    showMiniGuideOverlayFlow.value ||
        showQuickMenuOverlayFlow.value ||
        showProgramDetailsOverlayFlow.value ||
        touchEdgePanelFlow.value != TouchEdgePanel.NONE ||
        showFullGuideOverlayFlow.value
