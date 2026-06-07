package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import org.junit.Test

class RemoteShortcutPreferenceCodecTest {

    @Test
    fun decodePreferencesUsesProfileDefaultsWhenUnset() {
        val preferences = decodeRemoteShortcutPreferences { _, _ -> null }

        assertThat(
            preferences.resolvedAction(RemoteShortcutProfile.PLAYBACK, RemoteColorButton.RED)
        ).isEqualTo(RemoteShortcutAction.LAST_CHANNEL)
        assertThat(
            preferences.resolvedAction(RemoteShortcutProfile.BROWSE, RemoteColorButton.YELLOW)
        ).isEqualTo(RemoteShortcutAction.HIDE_CATEGORY)
    }

    @Test
    fun playbackGlobalSentinelFallsBackToGlobalDefault() {
        val preferences = decodeRemoteShortcutPreferences { profile, button ->
            if (profile == RemoteShortcutProfile.PLAYBACK && button == RemoteColorButton.GREEN) {
                REMOTE_SHORTCUT_USE_GLOBAL_SENTINEL
            } else {
                null
            }
        }

        val selection = preferences.selection(RemoteShortcutProfile.PLAYBACK, RemoteColorButton.GREEN)
        assertThat(selection).isEqualTo(RemoteShortcutSelection.globalDefault())
        assertThat(
            preferences.resolvedAction(RemoteShortcutProfile.PLAYBACK, RemoteColorButton.GREEN)
        ).isEqualTo(RemoteShortcutAction.OPEN_GUIDE)
    }

    @Test
    fun encodeRoundTripPreservesExplicitAction() {
        val encoded = encodeRemoteShortcutSelection(
            profile = RemoteShortcutProfile.BROWSE,
            selection = RemoteShortcutSelection.explicit(RemoteShortcutAction.PLAY_CHANNEL)
        )

        val decoded = decodeRemoteShortcutSelection(RemoteShortcutProfile.BROWSE, encoded)

        assertThat(encoded).isEqualTo(RemoteShortcutAction.PLAY_CHANNEL.storageValue)
        assertThat(decoded).isEqualTo(RemoteShortcutSelection.explicit(RemoteShortcutAction.PLAY_CHANNEL))
    }

    @Test
    fun globalProfileCannotPersistGlobalFallbackSentinel() {
        val encoded = encodeRemoteShortcutSelection(
            profile = RemoteShortcutProfile.GLOBAL,
            selection = RemoteShortcutSelection.globalDefault()
        )

        assertThat(encoded).isNull()
    }
}
