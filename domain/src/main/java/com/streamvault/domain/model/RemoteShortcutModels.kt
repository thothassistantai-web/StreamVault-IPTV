package com.streamvault.domain.model

enum class RemoteColorButton(val storageValue: String) {
    RED("red"),
    GREEN("green"),
    YELLOW("yellow"),
    BLUE("blue");

    companion object {
        fun fromStorage(value: String?): RemoteColorButton? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class RemoteShortcutProfile(val storageValue: String) {
    GLOBAL("global"),
    PLAYBACK("playback"),
    BROWSE("browse");

    companion object {
        fun fromStorage(value: String?): RemoteShortcutProfile? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class RemoteShortcutAction(val storageValue: String) {
    NONE("none"),
    OPEN_GUIDE("open_guide"),
    OPEN_PLAYER_CONTROLS("open_player_controls"),
    OPEN_CHANNEL_INFO("open_channel_info"),
    LAST_CHANNEL("last_channel"),
    NEXT_CHANNEL("next_channel"),
    PREVIOUS_CHANNEL("previous_channel"),
    OPEN_CHANNEL_LIST("open_channel_list"),
    OPEN_CATEGORY_LIST("open_category_list"),
    ADD_TO_SPLIT_SCREEN("add_to_split_screen"),
    TOGGLE_FAVORITE("toggle_favorite"),
    PLAY_CHANNEL("play_channel"),
    PIN_CATEGORY("pin_category"),
    TOGGLE_CATEGORY_LOCK("toggle_category_lock"),
    HIDE_CATEGORY("hide_category");

    companion object {
        fun fromStorage(value: String?): RemoteShortcutAction? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class RemoteShortcutSelectionMode {
    PROFILE_DEFAULT,
    GLOBAL_DEFAULT,
    ACTION
}

data class RemoteShortcutSelection(
    val mode: RemoteShortcutSelectionMode,
    val action: RemoteShortcutAction? = null
) {
    fun resolve(profile: RemoteShortcutProfile, button: RemoteColorButton): RemoteShortcutAction =
        when (mode) {
            RemoteShortcutSelectionMode.PROFILE_DEFAULT -> profile.defaultAction(button)
            RemoteShortcutSelectionMode.GLOBAL_DEFAULT -> RemoteShortcutProfile.GLOBAL.defaultAction(button)
            RemoteShortcutSelectionMode.ACTION -> action ?: profile.defaultAction(button)
        }

    fun normalizedForProfile(profile: RemoteShortcutProfile): RemoteShortcutSelection =
        when {
            mode == RemoteShortcutSelectionMode.ACTION && action == null -> profileDefault()
            profile == RemoteShortcutProfile.GLOBAL && mode == RemoteShortcutSelectionMode.GLOBAL_DEFAULT -> profileDefault()
            else -> this
        }

    companion object {
        fun profileDefault(): RemoteShortcutSelection = RemoteShortcutSelection(RemoteShortcutSelectionMode.PROFILE_DEFAULT)

        fun globalDefault(): RemoteShortcutSelection = RemoteShortcutSelection(RemoteShortcutSelectionMode.GLOBAL_DEFAULT)

        fun explicit(action: RemoteShortcutAction): RemoteShortcutSelection =
            RemoteShortcutSelection(RemoteShortcutSelectionMode.ACTION, action)
    }
}

data class RemoteShortcutPreferences(
    val selections: Map<RemoteShortcutProfile, Map<RemoteColorButton, RemoteShortcutSelection>> =
        RemoteShortcutProfile.entries.associateWith { profile ->
            RemoteColorButton.entries.associateWith { profile.defaultSelection() }
        }
) {
    fun selection(profile: RemoteShortcutProfile, button: RemoteColorButton): RemoteShortcutSelection =
        selections[profile]?.get(button)?.normalizedForProfile(profile) ?: profile.defaultSelection()

    fun resolvedAction(profile: RemoteShortcutProfile, button: RemoteColorButton): RemoteShortcutAction =
        selection(profile, button).resolve(profile, button)
}

fun RemoteShortcutProfile.defaultSelection(): RemoteShortcutSelection = RemoteShortcutSelection.profileDefault()

fun RemoteShortcutProfile.defaultAction(button: RemoteColorButton): RemoteShortcutAction = when (this) {
    RemoteShortcutProfile.GLOBAL -> when (button) {
        RemoteColorButton.RED -> RemoteShortcutAction.TOGGLE_FAVORITE
        RemoteColorButton.GREEN -> RemoteShortcutAction.OPEN_GUIDE
        RemoteColorButton.YELLOW -> RemoteShortcutAction.OPEN_CHANNEL_INFO
        RemoteColorButton.BLUE -> RemoteShortcutAction.ADD_TO_SPLIT_SCREEN
    }
    RemoteShortcutProfile.PLAYBACK -> when (button) {
        RemoteColorButton.RED -> RemoteShortcutAction.LAST_CHANNEL
        RemoteColorButton.GREEN -> RemoteShortcutAction.OPEN_GUIDE
        RemoteColorButton.YELLOW -> RemoteShortcutAction.OPEN_CHANNEL_INFO
        RemoteColorButton.BLUE -> RemoteShortcutAction.ADD_TO_SPLIT_SCREEN
    }
    RemoteShortcutProfile.BROWSE -> when (button) {
        RemoteColorButton.RED -> RemoteShortcutAction.TOGGLE_FAVORITE
        RemoteColorButton.GREEN -> RemoteShortcutAction.PIN_CATEGORY
        RemoteColorButton.YELLOW -> RemoteShortcutAction.HIDE_CATEGORY
        RemoteColorButton.BLUE -> RemoteShortcutAction.ADD_TO_SPLIT_SCREEN
    }
}
