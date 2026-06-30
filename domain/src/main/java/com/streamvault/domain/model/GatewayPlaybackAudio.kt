package com.streamvault.domain.model

import kotlin.math.pow

/**
 * Gateway Settings → Audio preferences delivered via plugin `audio_json` on playback prepare.
 *
 * Amplification is applied to ExoPlayer volume. Loudness normalization follows Media3 codec
 * loudness metadata when present (gateway exposes the user toggle for suite alignment).
 */
data class GatewayPlaybackAudio(
    val volumeNormalization: Boolean = false,
    val amplificationGainDb: Float = DEFAULT_GAIN_DB,
) {
    companion object {
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val DEFAULT_GAIN_DB = 0f
        const val DEFAULT_LINEAR_VOLUME = 1f
        const val MAX_LINEAR_VOLUME = 2f

        fun clampGainDb(raw: Float): Float = raw.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        fun dbToLinear(db: Float): Float = 10.0.pow((clampGainDb(db) / 20.0)).toFloat()

        fun linearVolume(audio: GatewayPlaybackAudio): Float =
            dbToLinear(audio.amplificationGainDb).coerceIn(0f, MAX_LINEAR_VOLUME)
    }
}
