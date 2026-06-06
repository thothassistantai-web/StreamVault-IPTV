package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import org.junit.Test

class PlayerContentResolutionSupportTest {
    @Test
    fun shouldUseStoredLiveStreamInfo_usesStoredInfoForPrimaryLiveUrl() {
        val primaryUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )

        assertThat(
            shouldUseStoredLiveStreamInfo(
                logicalUrl = primaryUrl,
                storedStreamUrl = primaryUrl
            )
        ).isTrue()
    }

    @Test
    fun shouldUseStoredLiveStreamInfo_bypassesStoredInfoForTransportFallback() {
        val primaryUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val fallbackUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = XtreamStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            shouldUseStoredLiveStreamInfo(
                logicalUrl = fallbackUrl,
                storedStreamUrl = primaryUrl
            )
        ).isFalse()
    }

    @Test
    fun shouldStartLiveTimeshiftForStreamClass_skipsMpegTsFallback() {
        assertThat(shouldStartLiveTimeshiftForStreamClass("MPEG-TS fallback")).isFalse()
    }

    @Test
    fun shouldStartLiveTimeshiftForStreamClass_allowsPrimaryLivePlayback() {
        assertThat(shouldStartLiveTimeshiftForStreamClass("Primary")).isTrue()
    }
}
