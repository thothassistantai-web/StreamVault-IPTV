package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerLiveTranslationActionsTest {

    @Test
    fun shouldEnableLiveTranslationSession_requiresEnabledSupportedProviderAndLiveContent() {
        assertTrue(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.XTREAM_CODES
            )
        )

        assertFalse(
            shouldEnableLiveTranslationSession(
                enabledPreference = false,
                contentType = ContentType.LIVE,
                providerType = ProviderType.XTREAM_CODES
            )
        )

        assertFalse(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.MOVIE,
                providerType = ProviderType.XTREAM_CODES
            )
        )

        assertFalse(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.STALKER_PORTAL
            )
        )

        assertTrue(
            shouldEnableLiveTranslationSession(
                enabledPreference = true,
                contentType = ContentType.LIVE,
                providerType = ProviderType.M3U
            )
        )
    }
}
