package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalSubtitlePlaybackTest {

    @Test
    fun `external subtitle transition resumes from current playback position`() {
        val transition = buildExternalSubtitlePlaybackTransition(
            currentPositionMs = 123_456L,
            playWhenReady = true
        )

        assertThat(transition.resumePositionMs).isEqualTo(123_456L)
        assertThat(transition.playWhenReady).isTrue()
    }

    @Test
    fun `external subtitle transition enables text renderer`() {
        val textDisabledParameters = TrackSelectionParameters.DEFAULT
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        val subtitleEnabledParameters = textDisabledParameters.withExternalSubtitleEnabled()

        assertThat(subtitleEnabledParameters.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
    }
}
