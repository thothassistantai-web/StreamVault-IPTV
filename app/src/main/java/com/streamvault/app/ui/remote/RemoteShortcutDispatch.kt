package com.streamvault.app.ui.remote

import android.view.KeyEvent
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction

data class PlayerRemoteShortcutHandler(
    val isLiveContent: Boolean,
    val isCatchUpPlayback: Boolean,
    val onOpenGuide: () -> Unit,
    val onOpenPlayerControls: () -> Unit,
    val onOpenChannelInfo: () -> Unit,
    val onOpenChannelList: () -> Unit,
    val onOpenCategoryList: () -> Unit,
    val onLastChannel: () -> Unit,
    val onNextChannel: () -> Unit,
    val onPreviousChannel: () -> Unit,
    val onAddToSplitScreen: () -> Unit
)

sealed interface LiveBrowseRemoteShortcutHandler {
    val onOpenGuide: (() -> Unit)?

    data class Channel(
        val onToggleFavorite: () -> Unit,
        val onPlayChannel: () -> Unit,
        val onAddToSplitScreen: () -> Unit,
        override val onOpenGuide: (() -> Unit)? = null
    ) : LiveBrowseRemoteShortcutHandler

    data class Category(
        val onPinCategory: () -> Unit,
        val onToggleCategoryLock: () -> Unit,
        val onHideCategory: () -> Unit,
        override val onOpenGuide: (() -> Unit)? = null
    ) : LiveBrowseRemoteShortcutHandler
}

fun remoteColorButtonForKeyCode(keyCode: Int): RemoteColorButton? = when (keyCode) {
    KeyEvent.KEYCODE_PROG_RED -> RemoteColorButton.RED
    KeyEvent.KEYCODE_PROG_GREEN -> RemoteColorButton.GREEN
    KeyEvent.KEYCODE_PROG_YELLOW -> RemoteColorButton.YELLOW
    KeyEvent.KEYCODE_PROG_BLUE -> RemoteColorButton.BLUE
    else -> null
}

fun dispatchPlayerRemoteShortcut(
    action: RemoteShortcutAction,
    handler: PlayerRemoteShortcutHandler
): Boolean {
    if (!action.isSupportedInPlayback(handler.isLiveContent, handler.isCatchUpPlayback)) return false
    when (action) {
        RemoteShortcutAction.NONE -> return true
        RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide()
        RemoteShortcutAction.OPEN_PLAYER_CONTROLS -> handler.onOpenPlayerControls()
        RemoteShortcutAction.OPEN_CHANNEL_INFO -> handler.onOpenChannelInfo()
        RemoteShortcutAction.LAST_CHANNEL -> handler.onLastChannel()
        RemoteShortcutAction.NEXT_CHANNEL -> handler.onNextChannel()
        RemoteShortcutAction.PREVIOUS_CHANNEL -> handler.onPreviousChannel()
        RemoteShortcutAction.OPEN_CHANNEL_LIST -> handler.onOpenChannelList()
        RemoteShortcutAction.OPEN_CATEGORY_LIST -> handler.onOpenCategoryList()
        RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> handler.onAddToSplitScreen()
        else -> return false
    }
    return true
}

fun dispatchLiveBrowseRemoteShortcut(
    action: RemoteShortcutAction,
    handler: LiveBrowseRemoteShortcutHandler
): Boolean {
    if (!action.isSupportedInBrowse(handler)) return false
    when (handler) {
        is LiveBrowseRemoteShortcutHandler.Channel -> when (action) {
            RemoteShortcutAction.NONE -> return true
            RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide?.invoke() ?: return false
            RemoteShortcutAction.TOGGLE_FAVORITE -> handler.onToggleFavorite()
            RemoteShortcutAction.PLAY_CHANNEL -> handler.onPlayChannel()
            RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> handler.onAddToSplitScreen()
            else -> return false
        }
        is LiveBrowseRemoteShortcutHandler.Category -> when (action) {
            RemoteShortcutAction.NONE -> return true
            RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide?.invoke() ?: return false
            RemoteShortcutAction.PIN_CATEGORY -> handler.onPinCategory()
            RemoteShortcutAction.TOGGLE_CATEGORY_LOCK -> handler.onToggleCategoryLock()
            RemoteShortcutAction.HIDE_CATEGORY -> handler.onHideCategory()
            else -> return false
        }
    }
    return true
}

fun RemoteShortcutAction.isSupportedInPlayback(
    isLiveContent: Boolean,
    isCatchUpPlayback: Boolean
): Boolean = when (this) {
    RemoteShortcutAction.NONE,
    RemoteShortcutAction.OPEN_PLAYER_CONTROLS -> true
    RemoteShortcutAction.OPEN_GUIDE,
    RemoteShortcutAction.OPEN_CHANNEL_INFO,
    RemoteShortcutAction.LAST_CHANNEL,
    RemoteShortcutAction.NEXT_CHANNEL,
    RemoteShortcutAction.PREVIOUS_CHANNEL,
    RemoteShortcutAction.OPEN_CHANNEL_LIST,
    RemoteShortcutAction.OPEN_CATEGORY_LIST,
    RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> isLiveContent && !isCatchUpPlayback
    else -> false
}

fun RemoteShortcutAction.isSupportedInBrowse(handler: LiveBrowseRemoteShortcutHandler): Boolean = when (handler) {
    is LiveBrowseRemoteShortcutHandler.Channel -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.TOGGLE_FAVORITE,
        RemoteShortcutAction.PLAY_CHANNEL,
        RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> true
        else -> false
    }
    is LiveBrowseRemoteShortcutHandler.Category -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.PIN_CATEGORY,
        RemoteShortcutAction.TOGGLE_CATEGORY_LOCK,
        RemoteShortcutAction.HIDE_CATEGORY -> true
        else -> false
    }
}
