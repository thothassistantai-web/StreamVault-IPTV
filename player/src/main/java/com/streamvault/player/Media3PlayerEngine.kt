package com.streamvault.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.common.Format
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VideoFormat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
class Media3PlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : PlayerEngine {

    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Main + supervisorJob)
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var currentDecoderMode: DecoderMode = DecoderMode.AUTO
    private var pollingJob: Job? = null
    private var lastStreamInfo: StreamInfo? = null
    private var retryCount = 0
    private var lastFrameRate: Float = 0f
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _videoFormat = MutableStateFlow(VideoFormat(0, 0))
    override val videoFormat: StateFlow<VideoFormat> = _videoFormat.asStateFlow()

    private val _error = MutableSharedFlow<PlayerError?>(replay = 1)
    override val error: Flow<PlayerError?> = _error.asSharedFlow()

    private val _availableAudioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = _availableSubtitleTracks.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also {
            exoPlayer = it
            mediaSession = MediaSession.Builder(context, it).build()
        }
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                when (currentDecoderMode) {
                    DecoderMode.AUTO -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
        }

        // Tuned for IPTV live streams: larger buffer for variable network conditions
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // min buffer
                60_000, // max buffer
                2500,   // buffer for playback
                5000    // buffer for rebuffering
            )
            .build()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = parameters.buildUpon()
                .setMaxVideoSize(1280, 720) // Multi-View optimization: don't request 1080p/4K per slot
                .build()
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                true // handleAudioFocus
            )
            .build()
            .apply {
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        decoderReuseEvaluation?.let { eval ->
                            if (eval.result != 0) { // Not REUSE_RESULT_YES
                                Log.d(TAG, "Decoder reuse: result=${eval.result}, discardReasons=${eval.discardReasons}")
                            }
                        }
                        lastFrameRate = format.frameRate.takeIf { it > 0f } ?: lastFrameRate
                        _playerStats.update { 
                            it.copy(
                                videoCodec = format.sampleMimeType ?: format.codecs ?: it.videoCodec,
                                videoBitrate = format.bitrate.takeIf { b -> b > 0 } ?: it.videoBitrate,
                                width = format.width.takeIf { w -> w > 0 } ?: it.width,
                                height = format.height.takeIf { h -> h > 0 } ?: it.height
                            ) 
                        }
                    }

                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        _playerStats.update { 
                            it.copy(
                                audioCodec = format.sampleMimeType ?: format.codecs ?: it.audioCodec
                            ) 
                        }
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        _playerStats.update { 
                            it.copy(droppedFrames = it.droppedFrames + droppedFrames) 
                        }
                    }

                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        _playerStats.update {
                            it.copy(bandwidthEstimate = bitrateEstimate)
                        }
                    }
                })
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val newState = when (state) {
                            Player.STATE_IDLE -> PlaybackState.IDLE
                            Player.STATE_BUFFERING -> {
                                // Track rebuffering: BUFFERING after READY = rebuffer event
                                if (_playbackState.value == PlaybackState.READY) {
                                    _playerStats.update { it.copy(rebufferCount = it.rebufferCount + 1) }
                                }
                                PlaybackState.BUFFERING
                            }
                            Player.STATE_READY -> PlaybackState.READY
                            Player.STATE_ENDED -> PlaybackState.ENDED
                            else -> PlaybackState.IDLE
                        }
                        _playbackState.value = newState
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing) {
                            startPolling()
                        } else {
                            stopPolling()
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        _videoFormat.value = VideoFormat(
                            width = videoSize.width,
                            height = videoSize.height,
                            frameRate = lastFrameRate
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount < MAX_RETRIES && isRecoverableError(error)) {
                            retryCount++
                            Log.w(TAG, "Recoverable error (attempt $retryCount/$MAX_RETRIES), retrying...")
                            handler.postDelayed({
                                lastStreamInfo?.let { prepare(it) }
                            }, retryCount * RETRY_BASE_DELAY_MS)
                        } else {
                            _error.tryEmit(PlayerError.fromException(error))
                            _playbackState.value = PlaybackState.ERROR
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        _currentPosition.value = newPosition.positionMs
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val audioTracks = mutableListOf<PlayerTrack>()
                        val subtitleTracks = mutableListOf<PlayerTrack>()

                        for (group in tracks.groups) {
                            val type = group.mediaTrackGroup.type
                            val isAudio = type == C.TRACK_TYPE_AUDIO
                            val isText = type == C.TRACK_TYPE_TEXT

                            if (isAudio || isText) {
                                for (i in 0 until group.length) {
                                    val format = group.mediaTrackGroup.getFormat(i)
                                    val id = format.id ?: "${group.mediaTrackGroup.hashCode()}_$i"
                                    val name = format.label ?: format.language ?: "Track ${i + 1}"
                                    val isSelected = group.isTrackSelected(i)

                                    val track = PlayerTrack(
                                        id = id,
                                        name = name,
                                        language = format.language,
                                        type = if (isAudio) TrackType.AUDIO else TrackType.TEXT,
                                        isSelected = isSelected
                                    )

                                    if (isAudio && group.isTrackSupported(i, false)) {
                                        audioTracks.add(track)
                                    } else if (isText && group.isTrackSupported(i, false)) {
                                        subtitleTracks.add(track)
                                    }
                                }
                            }
                        }

                        _availableAudioTracks.value = audioTracks
                        _availableSubtitleTracks.value = subtitleTracks
                    }
                })
            }
    }

    override fun prepare(streamInfo: StreamInfo) {
        lastStreamInfo = streamInfo
        retryCount = 0
        lastFrameRate = 0f
        handler.removeCallbacksAndMessages(null)
        val player = getOrCreatePlayer()
        _error.tryEmit(null)
        _playerStats.value = PlayerStats() // reset stats

        val dataSourceFactory = createDataSourceFactory(streamInfo)
        val streamType = streamInfo.streamType.takeIf { it != StreamType.UNKNOWN }
            ?: StreamTypeDetector.detect(streamInfo.url)

        val mediaSource = createMediaSource(streamInfo.url, streamType, dataSourceFactory)

        player.setMediaSource(mediaSource)
        player.prepare()
        _videoFormat.value = VideoFormat(0, 0) // Reset format
        player.playWhenReady = true
    }

    private fun createDataSourceFactory(streamInfo: StreamInfo): DataSource.Factory {
        val headers = buildMap {
            putAll(streamInfo.headers)
            streamInfo.userAgent?.let { put("User-Agent", it) }
        }

        val timeoutClient = okHttpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        return if (headers.isNotEmpty()) {
            OkHttpDataSource.Factory(timeoutClient).apply {
                setDefaultRequestProperties(headers)
            }
        } else {
            OkHttpDataSource.Factory(timeoutClient)
        }
    }

    private fun createMediaSource(
        url: String,
        streamType: StreamType,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)

        return when (streamType) {
            StreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)

            StreamType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)

            StreamType.MPEG_TS, StreamType.PROGRESSIVE, StreamType.UNKNOWN ->
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
        }
    }

    override fun play() {
        exoPlayer?.playWhenReady = true
    }

    override fun pause() {
        exoPlayer?.playWhenReady = false
    }

    override fun stop() {
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        exoPlayer?.let { player ->
            val newPos = (player.currentPosition + ms).coerceAtMost(player.duration)
            player.seekTo(newPos)
        }
    }

    override fun seekBackward(ms: Long) {
        exoPlayer?.let { player ->
            val newPos = (player.currentPosition - ms).coerceAtLeast(0)
            player.seekTo(newPos)
        }
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (currentDecoderMode != mode) {
            currentDecoderMode = mode
            // Recreate player with new decoder settings
            val wasPlaying = exoPlayer?.isPlaying ?: false
            val position = exoPlayer?.currentPosition ?: 0
            val mediaSource = exoPlayer?.currentMediaItem

            exoPlayer?.release()
            exoPlayer = null

            if (mediaSource != null) {
                val player = getOrCreatePlayer()
                player.setMediaItem(mediaSource)
                player.prepare()
                player.seekTo(position)
                player.playWhenReady = wasPlaying
            }
        }
    }

    override fun setVolume(volume: Float) {
        exoPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    override fun selectAudioTrack(trackId: String) {
        exoPlayer?.let { player ->
            val tracks = player.currentTracks
            for (group in tracks.groups) {
                if (group.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        val format = group.mediaTrackGroup.getFormat(i)
                        val id = format.id ?: "${group.mediaTrackGroup.hashCode()}_$i"
                        if (id == trackId) {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(
                                        group.mediaTrackGroup,
                                        listOf(i)
                                    )
                                )
                                .build()
                            return
                        }
                    }
                }
            }
        }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        exoPlayer?.let { player ->
            if (trackId == null) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            } else {
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    if (group.mediaTrackGroup.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            val format = group.mediaTrackGroup.getFormat(i)
                            val id = format.id ?: "${group.mediaTrackGroup.hashCode()}_$i"
                            if (id == trackId) {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(
                                        androidx.media3.common.TrackSelectionOverride(
                                            group.mediaTrackGroup,
                                            listOf(i)
                                        )
                                    )
                                    .build()
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    override fun release() {
        stopPolling()
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        lastStreamInfo = null
        supervisorJob.cancel()
        supervisorJob = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Main + supervisorJob)
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
    }

    private fun isRecoverableError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
            else -> false
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _currentPosition.value = player.currentPosition
                    _duration.value = player.duration.coerceAtLeast(0L)
                    // Update buffered duration for diagnostics
                    val buffered = player.bufferedPosition - player.currentPosition
                    _playerStats.update { it.copy(bufferedDurationMs = buffered.coerceAtLeast(0L)) }
                }
                delay(1000) // Poll every 1s — supplements onPositionDiscontinuity for continuous updates
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun getPlayerView(): Any? = exoPlayer
}
