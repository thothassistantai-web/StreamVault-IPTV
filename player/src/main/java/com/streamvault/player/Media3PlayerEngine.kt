package com.streamvault.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.view.View
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
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.Format
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VideoFormat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
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
    /**
     * When true the track selector caps video at 720p.
     * Set to true for multi-view slots so each instance doesn't compete for 4K bandwidth.
     */
    var constrainResolutionForMultiView: Boolean = false
    private var pollingJob: Job? = null
    private var lastStreamInfo: StreamInfo? = null
    private var retryCount = 0
    private var lastFrameRate: Float = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var lastBandwidthStatsUpdateMs: Long = 0L
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var shouldResumeOnAudioFocusGain = false
    private var currentVolume = 1f
    private var isDucked = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                isDucked = false
                exoPlayer?.volume = currentVolume
                if (shouldResumeOnAudioFocusGain) {
                    shouldResumeOnAudioFocusGain = false
                    exoPlayer?.playWhenReady = true
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                exoPlayer?.volume = (currentVolume * 0.2f).coerceAtLeast(0.05f)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = exoPlayer?.isPlaying == true
                exoPlayer?.playWhenReady = false
                exoPlayer?.volume = currentVolume
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                isDucked = false
                shouldResumeOnAudioFocusGain = false
                exoPlayer?.playWhenReady = false
                exoPlayer?.volume = currentVolume
            }
        }
    }

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 2000L
        private const val BANDWIDTH_STATS_MIN_INTERVAL_MS = 1500L
        private const val BUFFERED_DURATION_PUBLISH_STEP_MS = 1000L
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
            if (constrainResolutionForMultiView) {
                parameters = parameters.buildUpon()
                    .setMaxVideoSize(1280, 720) // Multi-view: cap each slot at 720p
                    .build()
            }
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                false // Explicitly managed via AudioManager below.
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
                        val now = SystemClock.elapsedRealtime()
                        updatePlayerStats { current ->
                            if (!shouldPublishBandwidthEstimate(current.bandwidthEstimate, bitrateEstimate, now)) {
                                current
                            } else {
                                lastBandwidthStatsUpdateMs = now
                                current.copy(bandwidthEstimate = bitrateEstimate)
                            }
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
                            val streamInfoSnapshot = lastStreamInfo
                            _playerStats.update { it.copy(droppedFrames = 0) }
                            handler.postDelayed({
                                streamInfoSnapshot?.let { prepare(it) }
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
        lastBandwidthStatsUpdateMs = 0L
        handler.removeCallbacksAndMessages(null)
        val player = getOrCreatePlayer()
        _error.tryEmit(null)
        _playerStats.value = PlayerStats() // reset stats

        val dataSourceFactory = createDataSourceFactory(streamInfo)
        val streamType = streamInfo.streamType.takeIf { it != StreamType.UNKNOWN }
            ?: StreamTypeDetector.detect(streamInfo.url)

        val mediaSource = createMediaSource(streamInfo.url, streamType, dataSourceFactory, streamInfo.title)

        player.setMediaSource(mediaSource)
        player.prepare()
        _videoFormat.value = VideoFormat(0, 0) // Reset format
        player.playWhenReady = requestAudioFocusIfNeeded()
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
        dataSourceFactory: DataSource.Factory,
        title: String? = null
    ): MediaSource {
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()

        return when (streamType) {
            StreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)

            StreamType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)

            // PE-H03: RTSP uses its own built-in transport, not OkHttp.
            StreamType.RTSP -> RtspMediaSource.Factory()
                .createMediaSource(mediaItem)

            StreamType.MPEG_TS, StreamType.PROGRESSIVE, StreamType.UNKNOWN ->
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
        }
    }

    override fun play() {
        if (requestAudioFocusIfNeeded()) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun pause() {
        exoPlayer?.playWhenReady = false
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    override fun stop() {
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        exoPlayer?.let { player ->
            val duration = player.duration
            // C.TIME_UNSET (-1) is returned for live/unbounded streams — don't coerce against it.
            val newPos = if (duration != C.TIME_UNSET) {
                (player.currentPosition + ms).coerceAtMost(duration)
            } else {
                player.currentPosition + ms
            }
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
            val streamInfo = lastStreamInfo ?: return  // Nothing playing — just update mode
            val wasPlaying = exoPlayer?.isPlaying ?: false
            val position = exoPlayer?.currentPosition ?: 0

            exoPlayer?.release()
            exoPlayer = null
            mediaSession?.release()
            mediaSession = null

            // Re-prepare using the original StreamInfo so the custom data source factory
            // (headers, user-agent) is fully rebuilt — not just the bare MediaItem.
            prepare(streamInfo)
            if (position > 0) exoPlayer?.seekTo(position)
            if (!wasPlaying) exoPlayer?.playWhenReady = false
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        exoPlayer?.volume = if (isDucked) {
            (currentVolume * 0.2f).coerceAtLeast(0.05f)
        } else {
            currentVolume
        }
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
        shouldResumeOnAudioFocusGain = false
        abandonAudioFocusIfHeld()
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
                    updatePlayerStats { current ->
                        val bufferedDurationMs = buffered.coerceAtLeast(0L)
                        if (shouldPublishBufferedDuration(current.bufferedDurationMs, bufferedDurationMs)) {
                            current.copy(bufferedDurationMs = bufferedDurationMs)
                        } else {
                            current
                        }
                    }
                }
                delay(250) // PE-M02: 250ms polling for smooth seek-bar animation
            }
        }
    }

    private inline fun updatePlayerStats(transform: (PlayerStats) -> PlayerStats) {
        _playerStats.update { current ->
            val updated = transform(current)
            if (updated == current) current else updated
        }
    }

    private fun shouldPublishBandwidthEstimate(
        currentEstimate: Long,
        newEstimate: Long,
        nowMs: Long
    ): Boolean {
        if (newEstimate <= 0L) return false
        if (currentEstimate <= 0L) return true
        val absoluteDelta = abs(newEstimate - currentEstimate)
        val relativeThreshold = (currentEstimate / 8).coerceAtLeast(250_000L)
        return absoluteDelta >= relativeThreshold || nowMs - lastBandwidthStatsUpdateMs >= BANDWIDTH_STATS_MIN_INTERVAL_MS
    }

    private fun shouldPublishBufferedDuration(currentDurationMs: Long, newDurationMs: Long): Boolean {
        return currentDurationMs == 0L || newDurationMs == 0L || abs(newDurationMs - currentDurationMs) >= BUFFERED_DURATION_PUBLISH_STEP_MS
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun createRenderView(context: Context, resizeMode: PlayerSurfaceResizeMode): View {
        return PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            player = exoPlayer
            applyResizeMode(resizeMode)
        }
    }

    override fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode) {
        val playerView = renderView as? PlayerView ?: return
        if (playerView.player !== exoPlayer) {
            playerView.player = exoPlayer
        }
        playerView.applyResizeMode(resizeMode)
    }

    private fun PlayerView.applyResizeMode(surfaceResizeMode: PlayerSurfaceResizeMode) {
        resizeMode = when (surfaceResizeMode) {
            PlayerSurfaceResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerSurfaceResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlayerSurfaceResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (hasAudioFocus) {
            if (isDucked) {
                isDucked = false
                exoPlayer?.volume = currentVolume
            }
            return true
        }
        val focusRequest = audioFocusRequest ?: buildAudioFocusRequest().also { audioFocusRequest = it }
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        hasAudioFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            Log.w(TAG, "Audio focus request denied; playback will remain paused")
            return false
        }
        isDucked = false
        exoPlayer?.volume = currentVolume
        return true
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
        isDucked = false
    }

    private fun buildAudioFocusRequest(): AudioFocusRequest {
        val platformAudioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(platformAudioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }

}
