package com.streamvault.player

import android.content.Context
import android.view.View
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the underlying media player.
 * UI layer talks to this interface, never to ExoPlayer directly.
 */
interface PlayerEngine {
    val playbackState: StateFlow<PlaybackState>
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val videoFormat: StateFlow<VideoFormat>
    val error: Flow<PlayerError?>
    val playerStats: StateFlow<PlayerStats>

    // Tracks
    val availableAudioTracks: StateFlow<List<PlayerTrack>>
    val availableSubtitleTracks: StateFlow<List<PlayerTrack>>

    fun prepare(streamInfo: StreamInfo)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekForward(ms: Long = 10_000)
    fun seekBackward(ms: Long = 10_000)
    fun setDecoderMode(mode: DecoderMode)
    fun setVolume(volume: Float)
    fun selectAudioTrack(trackId: String)
    fun selectSubtitleTrack(trackId: String?) // null to disable subtitles
    fun release()

    fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode): View
    fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode)
}

enum class PlayerSurfaceResizeMode {
    FIT,
    FILL,
    ZOOM
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class PlayerStats(
    val videoCodec: String = "Unknown",
    val audioCodec: String = "Unknown",
    val videoBitrate: Int = 0,
    val droppedFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bandwidthEstimate: Long = 0,
    val bufferedDurationMs: Long = 0,
    val rebufferCount: Int = 0
)

sealed class PlayerError(val message: String) {
    class NetworkError(message: String) : PlayerError(message)
    class SourceError(message: String) : PlayerError(message)
    class DecoderError(message: String) : PlayerError(message)
    class UnknownError(message: String) : PlayerError(message)

    companion object {
        fun fromException(e: Throwable): PlayerError {
            val msg = e.message ?: "Unknown playback error"
            if (e is PlaybackException) {
                return when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                        NetworkError(msg)
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        SourceError(msg)
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        DecoderError(msg)
                    else -> UnknownError(msg)
                }
            }
            return UnknownError(msg)
        }
    }
}
