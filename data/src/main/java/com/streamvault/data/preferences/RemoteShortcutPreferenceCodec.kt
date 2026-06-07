package com.streamvault.data.preferences

import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.RemoteShortcutAction

internal const val REMOTE_SHORTCUT_USE_GLOBAL_SENTINEL = "__global_default__"

internal fun decodeRemoteShortcutSelection(
    profile: RemoteShortcutProfile,
    rawValue: String?
): RemoteShortcutSelection = when {
    rawValue.isNullOrBlank() -> RemoteShortcutSelection.profileDefault()
    rawValue == REMOTE_SHORTCUT_USE_GLOBAL_SENTINEL && profile != RemoteShortcutProfile.GLOBAL ->
        RemoteShortcutSelection.globalDefault()
    else -> RemoteShortcutAction.fromStorage(rawValue)
        ?.let(RemoteShortcutSelection::explicit)
        ?: RemoteShortcutSelection.profileDefault()
}

internal fun encodeRemoteShortcutSelection(
    profile: RemoteShortcutProfile,
    selection: RemoteShortcutSelection
): String? {
    val normalized = selection.normalizedForProfile(profile)
    return when (normalized.mode) {
        com.streamvault.domain.model.RemoteShortcutSelectionMode.PROFILE_DEFAULT -> null
        com.streamvault.domain.model.RemoteShortcutSelectionMode.GLOBAL_DEFAULT -> REMOTE_SHORTCUT_USE_GLOBAL_SENTINEL
        com.streamvault.domain.model.RemoteShortcutSelectionMode.ACTION -> normalized.action?.storageValue
    }
}

internal fun decodeRemoteShortcutPreferences(
    read: (RemoteShortcutProfile, RemoteColorButton) -> String?
): RemoteShortcutPreferences = RemoteShortcutPreferences(
    selections = RemoteShortcutProfile.entries.associateWith { profile ->
        RemoteColorButton.entries.associateWith { button ->
            decodeRemoteShortcutSelection(profile, read(profile, button))
        }
    }
)
