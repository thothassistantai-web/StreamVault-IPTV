package com.streamvault.app.ui.screens.player.gesture

/**
 * Overlay precedence — only one managed overlay is visible at a time.
 * Higher ordinal wins when resolving conflicts.
 */
enum class PlayerOverlayType {
    HUD,
    CONTROLS,
    MINI_GUIDE,
    CHANNEL_BROWSER,
    QUICK_MENU,
    PROGRAM_DETAILS,
    FULL_GUIDE,
    EDGE_PANEL,
}
