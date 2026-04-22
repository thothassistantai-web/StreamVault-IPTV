package com.streamvault.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.audio.PlayerAudioFocusController
import com.streamvault.player.playback.DefaultDecoderPreferencePolicy
import com.streamvault.player.playback.DefaultPlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.playback.PlaybackRetryContext
import com.streamvault.player.playback.PlayerDataSourceFactoryProvider
import com.streamvault.player.playback.PlayerErrorClassifier
import com.streamvault.player.playback.PlayerMediaSourceFactory
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.PlayerTimeoutProfile
import com.streamvault.player.playback.PreloadCoordinator
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import com.streamvault.player.stats.PlayerStatsCollector
import com.streamvault.player.timeshift.DefaultLiveTimeshiftManager
import com.streamvault.player.timeshift.LiveTimeshiftBackend
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import com.streamvault.player.timeshift.TimeshiftConfig
import com.streamvault.player.tracks.PlayerTrackController
import com.streamvault.player.ui.PlayerViewBinder
import com.streamvault.player.ui.SubtitleStyleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class Media3PlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : PlayerEngine {

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val AUDIO_RENDERER_RECOVERY_COOLDOWN_MS = 15_000L
    }

    var constrainResolutionForMultiView: Boolean = false
    var bypassAudioFocus: Boolean = false
        set(value) {
            field = value
            audioFocusController.bypassAudioFocus = value
        }
    var mediaSessionId: String = "streamvault-main"
    var enableMediaSession: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                exoPlayer?.let { player ->
                    if (mediaSession == null) {
                        mediaSession = MediaSession.Builder(context, player)
                            .setId(mediaSessionId)
                            .build()
                    }
                }
            } else {
                mediaSession?.release()
                mediaSession = null
            }
        }

    // All mutable engine state below is read/written on Dispatchers.Main.immediate
    // (the engine scope dispatcher). No @Volatile or synchronisation is needed as long
    // as the scope is not replaced with a different dispatcher in tests.
    //
    // scope is a var because release() cancels it to tear down dangling coroutines,
    // then creates a fresh one so the singleton engine remains usable on re-entry.
    private var scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private set
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var requestedDecoderMode: DecoderMode = DecoderMode.AUTO
    private var activeDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var lastStreamInfo: StreamInfo? = null
    private var lastMediaId: String? = null
    private var currentResolvedStreamType: ResolvedStreamType = ResolvedStreamType.UNKNOWN
    private var currentTimeoutProfile: PlayerTimeoutProfile = PlayerTimeoutProfile.VOD
    private var currentRetryPolicy: PlayerRetryPolicy? = null
    private var currentRetryContext: PlaybackRetryContext? = null
    private var playbackStarted = false
    private var prepareStartMs = 0L
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var retryGeneration = 0L
    private var currentBufferIsLive: Boolean? = null
    private var audioCodecUnsupportedReported = false

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

    private val _retryStatus = MutableStateFlow<PlayerRetryStatus?>(null)
    override val retryStatus: StateFlow<PlayerRetryStatus?> = _retryStatus.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val liveTimeshiftManager = DefaultLiveTimeshiftManager(context, okHttpClient)
    private val _timeshiftState = MutableStateFlow(LiveTimeshiftState())
    override val timeshiftState: StateFlow<LiveTimeshiftState> = _timeshiftState.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val subtitleStyleController = SubtitleStyleController()
    private val viewBinder = PlayerViewBinder(subtitleStyleController)
    private val trackController = PlayerTrackController(context)
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = trackController.availableAudioTracks
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = trackController.availableSubtitleTracks
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = trackController.availableVideoTracks

    private val _audioFocusDenied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val audioFocusDenied: Flow<Unit> = _audioFocusDenied.asSharedFlow()

    private val audioFocusController = PlayerAudioFocusController(
        context = context,
        applyVolume = { volume -> exoPlayer?.volume = volume },
        setPlayWhenReady = { playWhenReady -> exoPlayer?.playWhenReady = playWhenReady },
        onAudioFocusDenied = { _audioFocusDenied.tryEmit(Unit) }
    ).also {
        it.bypassAudioFocus = bypassAudioFocus
    }
    override val isMuted: StateFlow<Boolean> = audioFocusController.isMuted

    private val statsCollector = PlayerStatsCollector(
        scopeProvider = { scope },
        currentPosition = _currentPosition,
        duration = _duration,
        videoFormat = _videoFormat,
        playerStats = _playerStats,
        playbackState = _playbackState
    ).also {
        it.bind { exoPlayer }
    }
    private val dataSourceFactoryProvider = PlayerDataSourceFactoryProvider(context, okHttpClient)
    private val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceFactoryProvider)
    private val preloadCoordinator = PreloadCoordinator()
    private val compatibilityProfile: PlaybackCompatibilityProfile = DefaultPlaybackCompatibilityProfile
    private val decoderPreferencePolicy = DefaultDecoderPreferencePolicy()
    // All reads/writes on Dispatchers.Main.immediate (engine scope).
    @get:MainThread private var activeLiveTimeshiftStreamInfo: StreamInfo? = null
    @get:MainThread private var activeLiveTimeshiftChannelKey: String? = null
    @get:MainThread private var isPlayingTimeshiftSnapshot: Boolean = false
    private var pendingTimeshiftSeekMs: Long? = null
    private var pendingTimeshiftSeekToEnd: Boolean = false
    private var pendingTimeshiftAutoPlay: Boolean = false
    private var lastAudioRendererRecoveryAtMs: Long = 0L

    init {
        scope.launch {
            liveTimeshiftManager.state.collectLatest {
                syncTimeshiftState()
            }
        }
    }

    override fun prepare(streamInfo: StreamInfo) {
        prepareInternal(streamInfo = streamInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
    }

    override fun renewStreamUrl(streamInfo: StreamInfo) {
        val player = exoPlayer ?: return
        val retryPolicy = currentRetryPolicy ?: return

        lastStreamInfo = streamInfo
        lastMediaId = mediaSourceFactory.mediaIdFor(streamInfo)

        val mediaSource = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = currentResolvedStreamType,
            retryPolicy = retryPolicy,
            preload = false
        ).second

        player.setMediaSource(mediaSource, /* resetPosition= */ false)
        player.prepare()

        Log.i(
            TAG,
            "renew-url resolvedStreamType=$currentResolvedStreamType target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
    }

    override fun play() {
        if (audioFocusController.requestAudioFocusIfNeeded()) {
            exoPlayer?.playWhenReady = true
            syncTimeshiftState()
        }
    }

    override fun pause() {
        retryJob?.cancel()
        exoPlayer?.playWhenReady = false
        audioFocusController.onPauseOrStop()
    }

    override fun stop() {
        retryJob?.cancel()
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        audioFocusController.onPauseOrStop()
    }

    override fun seekTo(positionMs: Long) {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            switchToTimeshiftSnapshot(positionMs = positionMs.coerceAtLeast(0L), autoPlay = true)
            return
        }
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        if (isPlayingTimeshiftSnapshot) {
            exoPlayer?.let { player ->
                val duration = player.duration
                if (duration != C.TIME_UNSET && player.currentPosition + ms >= duration) {
                    seekToLiveEdge()
                    return
                }
                val newPosition = if (duration != C.TIME_UNSET) {
                    (player.currentPosition + ms).coerceAtMost(duration)
                } else {
                    player.currentPosition + ms
                }
                player.seekTo(newPosition)
            }
            return
        }
        exoPlayer?.let { player ->
            val duration = player.duration
            val newPosition = if (duration != C.TIME_UNSET) {
                (player.currentPosition + ms).coerceAtMost(duration)
            } else {
                player.currentPosition + ms
            }
            player.seekTo(newPosition)
        }
    }

    override fun seekBackward(ms: Long) {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            val liveEdge = _timeshiftState.value.liveEdgePositionMs
            val target = (liveEdge - ms).coerceAtLeast(0L)
            switchToTimeshiftSnapshot(positionMs = target, autoPlay = true)
            return
        }
        exoPlayer?.let { player ->
            player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
        }
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (requestedDecoderMode == mode) return
        requestedDecoderMode = mode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setMediaSessionEnabled(enabled: Boolean) {
        enableMediaSession = enabled
    }

    override fun setVolume(volume: Float) {
        audioFocusController.setVolume(volume)
    }

    override fun setMuted(muted: Boolean) {
        audioFocusController.setMuted(muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _playbackSpeed.value = clamped
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    @MainThread
    override fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        activeLiveTimeshiftStreamInfo = streamInfo
        activeLiveTimeshiftChannelKey = channelKey
        scope.launch {
            liveTimeshiftManager.startSession(streamInfo, channelKey, config)
            syncTimeshiftState()
        }
    }

    @MainThread
    override fun stopLiveTimeshift() {
        val wasSnapshot = isPlayingTimeshiftSnapshot
        val liveInfo = activeLiveTimeshiftStreamInfo
        activeLiveTimeshiftStreamInfo = null
        activeLiveTimeshiftChannelKey = null
        isPlayingTimeshiftSnapshot = false
        pendingTimeshiftSeekMs = null
        pendingTimeshiftSeekToEnd = false
        pendingTimeshiftAutoPlay = false
        if (wasSnapshot) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        scope.launch {
            liveTimeshiftManager.stopSession()
            if (wasSnapshot && liveInfo != null) {
                prepareInternal(liveInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
            }
            syncTimeshiftState()
        }
    }

    @MainThread
    override fun seekToLiveEdge() {
        val liveInfo = activeLiveTimeshiftStreamInfo ?: return
        val wasSnapshot = isPlayingTimeshiftSnapshot
        isPlayingTimeshiftSnapshot = false
        pendingTimeshiftSeekMs = null
        pendingTimeshiftSeekToEnd = false
        pendingTimeshiftAutoPlay = false
        if (wasSnapshot) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        prepareInternal(liveInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
        syncTimeshiftState()
        if (wasSnapshot) {
            scope.launch { liveTimeshiftManager.releaseRetiredSnapshots() }
        }
    }

    override fun pauseTimeshift() {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            switchToTimeshiftSnapshot(positionMs = null, autoPlay = false, seekToEnd = true)
            return
        }
        exoPlayer?.playWhenReady = false
        audioFocusController.onPauseOrStop()
        syncTimeshiftState()
    }

    override fun resumeTimeshift() {
        if (isPlayingTimeshiftSnapshot) {
            play()
            return
        }
        seekToLiveEdge()
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        trackController.setPreferredAudioLanguage(exoPlayer, languageTag)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        subtitleStyleController.updateStyle(style)
        viewBinder.reapplyStyle()
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        trackController.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
        exoPlayer?.let { player -> trackController.applyInitialParameters(player, constrainResolutionForMultiView) }
    }

    override fun selectAudioTrack(trackId: String) {
        exoPlayer?.let { trackController.selectAudioTrack(it, trackId) }
    }

    override fun selectVideoTrack(trackId: String) {
        exoPlayer?.let { trackController.selectVideoTrack(it, trackId) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        exoPlayer?.let { trackController.selectSubtitleTrack(it, trackId) }
    }

    override fun toggleMute() {
        audioFocusController.toggleMute()
    }

    override fun setScrubbingMode(enabled: Boolean) {
        val player = exoPlayer ?: return
        if (enabled) {
            player.setScrubbingModeEnabled(true)
        } else {
            player.setScrubbingModeEnabled(false)
            player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
        }
    }

    override fun preload(streamInfo: StreamInfo?) {
        if (streamInfo == null) {
            preloadCoordinator.invalidate("cleared")
            return
        }
        if (streamInfo.url == lastStreamInfo?.url) return

        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        val resolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        val retryContext = PlaybackRetryContext(
            resolvedStreamType = resolvedStreamType,
            timeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload = true)
        )
        val retryPolicy = PlayerRetryPolicy(retryContext) { false }
        val (_, mediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = resolvedStreamType,
            retryPolicy = retryPolicy,
            preload = true
        )
        preloadCoordinator.store(mediaId, streamInfo, resolvedStreamType, mediaSource)
    }

    override fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType
    ) = viewBinder.createRenderView(context, resizeMode, surfaceType)

    override fun bindRenderView(renderView: android.view.View, resizeMode: PlayerSurfaceResizeMode) {
        viewBinder.bind(renderView, getOrCreatePlayer(), resizeMode)
    }

    override fun clearRenderBinding() {
        viewBinder.attachPlayer(null)
    }

    override fun releaseRenderView(renderView: android.view.View) {
        viewBinder.release(renderView)
    }

    override fun release() {
        retryJob?.cancel()
        retryJob = null
        preloadCoordinator.release()
        statsCollector.stop()
        audioFocusController.release()
        mediaSession?.release()
        mediaSession = null
        viewBinder.clear()
        exoPlayer?.release()
        exoPlayer = null
        lastStreamInfo = null
        lastMediaId = null
        currentRetryPolicy = null
        currentRetryContext = null
        playbackStarted = false
        retryAttempt = 0
        _retryStatus.value = null
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()
        activeLiveTimeshiftStreamInfo = null
        activeLiveTimeshiftChannelKey = null
        isPlayingTimeshiftSnapshot = false
        _timeshiftState.value = LiveTimeshiftState()
        scope.cancel()
        // Replace with a fresh scope so the singleton engine remains functional on re-entry.
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        // Re-launch the timeshift state collector on the new scope.
        scope.launch {
            liveTimeshiftManager.state.collectLatest {
                syncTimeshiftState()
            }
        }
        // File cleanup runs outside the engine scope — orphans are also cleaned on next app start
        CoroutineScope(Dispatchers.IO).launch { liveTimeshiftManager.stopSession() }
    }

    private fun prepareInternal(
        streamInfo: StreamInfo,
        preserveRetryState: Boolean,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        retryJob?.cancel()
        retryJob = null
        retryGeneration++
        lastStreamInfo = streamInfo
        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        if (!preserveRetryState || lastMediaId != mediaId) {
            retryAttempt = 0
            _retryStatus.value = null
        }
        if (lastMediaId != mediaId) {
            decoderPreferencePolicy.resetForMedia(mediaId)
        }
        lastMediaId = mediaId
        playbackStarted = false
        prepareStartMs = System.currentTimeMillis()
        audioCodecUnsupportedReported = false
        _error.tryEmit(null)
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()

        currentResolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        currentTimeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, currentResolvedStreamType, preload = false)
        currentRetryContext = PlaybackRetryContext(currentResolvedStreamType, currentTimeoutProfile)
        currentRetryPolicy = PlayerRetryPolicy(currentRetryContext!!) { playbackStarted }

        val preferredDecoderMode = decoderPreferencePolicy.preferredMode(requestedDecoderMode, mediaId)
        val isLiveBuffer = currentResolvedStreamType in setOf(
            ResolvedStreamType.HLS, ResolvedStreamType.MPEG_TS_LIVE, ResolvedStreamType.RTSP
        )
        val needsRecreate = activeDecoderMode != preferredDecoderMode || isLiveBuffer != currentBufferIsLive
        activeDecoderMode = preferredDecoderMode
        currentBufferIsLive = isLiveBuffer
        if (needsRecreate) {
            recreatePlayer()
        }

        Log.i(
            TAG,
            "prepare resolvedStreamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile decoderPreference=$activeDecoderMode target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        val player = getOrCreatePlayer()
        trackController.applyInitialParameters(player, constrainResolutionForMultiView)
        player.playbackParameters = PlaybackParameters(_playbackSpeed.value)

        val mediaSource = preloadCoordinator.tryReuse(mediaId, streamInfo, currentResolvedStreamType)
            ?: mediaSourceFactory.create(
                streamInfo = streamInfo,
                resolvedStreamType = currentResolvedStreamType,
                retryPolicy = currentRetryPolicy!!,
                preload = false
            ).second
        preloadCoordinator.onPlaybackStarted(mediaId)
        player.setMediaSource(mediaSource)
        player.prepare()
        seekPositionMs?.takeIf { it > 0L }?.let(player::seekTo)

        val isLive = currentResolvedStreamType in setOf(
            ResolvedStreamType.HLS, ResolvedStreamType.MPEG_TS_LIVE, ResolvedStreamType.RTSP
        )
        val osContentType = if (isLive) {
            android.media.AudioAttributes.CONTENT_TYPE_MUSIC
        } else {
            android.media.AudioAttributes.CONTENT_TYPE_MOVIE
        }
        val media3ContentType = if (isLive) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE
        player.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(media3ContentType)
                .build(),
            false
        )
        val focusGranted = audioFocusController.requestAudioFocusIfNeeded(osContentType)
        player.playWhenReady = autoPlay && focusGranted
        if (autoPlay && !focusGranted) {
            _audioFocusDenied.tryEmit(Unit)
        }
        viewBinder.attachPlayer(player)
    }

    private fun recreatePlayer() {
        val existing = exoPlayer ?: return
        mediaSession?.release()
        mediaSession = null
        existing.release()
        exoPlayer = null
        viewBinder.attachPlayer(null)
        // The caller (prepareInternal) will create a fresh player and set it up fully.
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        return exoPlayer ?: createPlayer().also { player ->
            exoPlayer = player
            statsCollector.bind { exoPlayer }
            audioFocusController.reapplyVolume()
            if (enableMediaSession) {
                mediaSession = MediaSession.Builder(context, player)
                    .setId(mediaSessionId)
                    .build()
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = buildRenderersFactory()
        val maxBufferMs = if (currentBufferIsLive == true) 30_000 else 120_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                maxBufferMs,
                2_500,
                10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                false
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playbackParameters = PlaybackParameters(_playbackSpeed.value)
                addAnalyticsListener(createAnalyticsListener())
                addListener(createPlayerListener())
            }
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val baseFactory = if (compatibilityProfile.shouldDisableDecoderReuseWorkaround()) {
            object : DefaultRenderersFactory(context) {
                override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: Handler,
                    eventListener: VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: ArrayList<Renderer>
                ) {
                    out.add(object : MediaCodecVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50
                    ) {
                        override fun canReuseCodec(
                            codecInfo: MediaCodecInfo,
                            oldFormat: Format,
                            newFormat: Format
                        ): DecoderReuseEvaluation {
                            return DecoderReuseEvaluation(
                                codecInfo.name,
                                oldFormat,
                                newFormat,
                                DecoderReuseEvaluation.REUSE_RESULT_NO,
                                DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
                            )
                        }
                    })
                }
            }
        } else {
            DefaultRenderersFactory(context)
        }

        return baseFactory.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(
                when (activeDecoderMode) {
                    DecoderMode.AUTO, DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                }
            )
        }
    }

    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onVideoFormatChanged(format)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onAudioFormatChanged(format)
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                Log.w(
                    TAG,
                    "audio-underrun bufferSize=$bufferSize bufferSizeMs=$bufferSizeMs elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs"
                )
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                handleAudioRendererIssue(audioSinkError, "audio-sink")
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                handleAudioRendererIssue(audioCodecError, "audio-codec")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                statsCollector.onDroppedFrames(droppedFrames)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                statsCollector.onBandwidthEstimate(bitrateEstimate)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                if (prepareStartMs > 0L) {
                    val ttff = System.currentTimeMillis() - prepareStartMs
                    _playerStats.value = _playerStats.value.copy(ttffMs = ttff)
                }
                markPlaybackStarted("first-frame-success")
            }
        }
    }

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val previousState = _playbackState.value
                _playbackState.value = when (state) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                if (previousState == PlaybackState.READY && _playbackState.value == PlaybackState.BUFFERING) {
                    statsCollector.incrementRebufferCount()
                }
                if (_playbackState.value == PlaybackState.READY) {
                    _retryStatus.value = null
                    if (isPlayingTimeshiftSnapshot && pendingTimeshiftSeekToEnd) {
                        pendingTimeshiftSeekToEnd = false
                        playerOrNull()?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }?.let { duration ->
                            playerOrNull()?.seekTo((duration - 200L).coerceAtLeast(0L))
                        }
                    } else if (isPlayingTimeshiftSnapshot) {
                        pendingTimeshiftSeekMs?.let { target ->
                            pendingTimeshiftSeekMs = null
                            playerOrNull()?.seekTo(target)
                        }
                    }
                }
                if (_playbackState.value == PlaybackState.READY && _isPlaying.value) {
                    markPlaybackStarted("ready-while-playing")
                }
                syncTimeshiftState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    if (_playbackState.value == PlaybackState.READY) {
                        markPlaybackStarted("playing-ready")
                    }
                    statsCollector.start()
                    audioFocusController.onPlaybackStarted()
                } else {
                    statsCollector.stop()
                }
                syncTimeshiftState()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs
                syncTimeshiftState()
            }

            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                _mediaTitle.value = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackController.onTracksChanged(tracks)
                // Detect the silent failure: the stream contains audio groups but no track
                // is decodable on this device (e.g. EAC3/AC3 without passthrough or a
                // software decoder). ExoPlayer simply skips the audio renderer without
                // throwing an error, resulting in mute playback with no user feedback.
                if (!audioCodecUnsupportedReported) {
                    val hasAudioGroups = tracks.groups.any {
                        it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO && it.length > 0
                    }
                    if (hasAudioGroups && trackController.availableAudioTracks.value.isEmpty()) {
                        audioCodecUnsupportedReported = true
                        val mimeTypes = tracks.groups
                            .filter { it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO }
                            .flatMap { g -> (0 until g.length).mapNotNull { g.mediaTrackGroup.getFormat(it).sampleMimeType } }
                            .distinct()
                            .joinToString()
                        Log.w(TAG, "audio-codec-unsupported mimeTypes=$mimeTypes target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url.orEmpty())}")
                        _error.tryEmit(
                            PlayerError.DecoderError(
                                "No compatible audio decoder for this stream ($mimeTypes). " +
                                    "The audio codec is not supported on this device."
                            )
                        )
                    }
                }
            }
        }
    }

    private fun playerOrNull(): ExoPlayer? = exoPlayer

    private fun switchToTimeshiftSnapshot(
        positionMs: Long?,
        autoPlay: Boolean,
        seekToEnd: Boolean = false
    ) {
        val liveInfo = activeLiveTimeshiftStreamInfo ?: return
        scope.launch {
            val snapshot = liveTimeshiftManager.createSnapshot() ?: run {
                syncTimeshiftState(messageOverride = "Local live rewind is still buffering.")
                return@launch
            }
            if (activeLiveTimeshiftStreamInfo !== liveInfo) return@launch
            isPlayingTimeshiftSnapshot = true
            pendingTimeshiftSeekMs = positionMs
            pendingTimeshiftSeekToEnd = seekToEnd
            pendingTimeshiftAutoPlay = autoPlay
            val snapshotInfo = liveInfo.copy(url = snapshot.url, streamType = inferSnapshotStreamType(snapshot.url))
            prepareInternal(snapshotInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = autoPlay)
            liveTimeshiftManager.releaseRetiredSnapshots()
            syncTimeshiftState()
        }
    }

    private fun inferSnapshotStreamType(url: String) = when {
        url.lowercase().endsWith(".m3u8") -> com.streamvault.domain.model.StreamType.HLS
        else -> com.streamvault.domain.model.StreamType.PROGRESSIVE
    }

    private fun syncTimeshiftState(messageOverride: String? = null) {
        val managerState = liveTimeshiftManager.state.value
        if (!managerState.enabled) {
            _timeshiftState.value = managerState
            return
        }
        val player = exoPlayer
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET } ?: managerState.liveEdgePositionMs
        val currentPosition = player?.currentPosition ?: duration
        val offsetFromLive = when {
            isPlayingTimeshiftSnapshot -> (managerState.liveEdgePositionMs - currentPosition).coerceAtLeast(0L)
            else -> 0L
        }
        val status = when {
            managerState.status == LiveTimeshiftStatus.FAILED -> LiveTimeshiftStatus.FAILED
            !managerState.supported -> LiveTimeshiftStatus.UNSUPPORTED
            isPlayingTimeshiftSnapshot && _playbackState.value == PlaybackState.BUFFERING -> LiveTimeshiftStatus.BUFFERING
            isPlayingTimeshiftSnapshot && _isPlaying.value -> LiveTimeshiftStatus.PLAYING_BEHIND_LIVE
            isPlayingTimeshiftSnapshot -> LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
            managerState.status == LiveTimeshiftStatus.PREPARING -> LiveTimeshiftStatus.PREPARING
            else -> LiveTimeshiftStatus.LIVE
        }
        _timeshiftState.value = managerState.copy(
            backend = managerState.backend,
            status = status,
            liveEdgePositionMs = managerState.liveEdgePositionMs,
            currentOffsetFromLiveMs = offsetFromLive,
            message = messageOverride ?: managerState.message
        )
    }

    private fun markPlaybackStarted(reason: String) {
        if (playbackStarted) return
        playbackStarted = true
        Log.i(
            TAG,
            "$reason streamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}"
        )
    }

    private fun handleAudioRendererIssue(error: Exception, source: String) {
        val streamInfo = lastStreamInfo
        if (streamInfo == null) {
            _error.tryEmit(PlayerError.DecoderError(error.message ?: "Audio playback failed."))
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAudioRendererRecoveryAtMs < AUDIO_RENDERER_RECOVERY_COOLDOWN_MS) {
            Log.e(
                TAG,
                "audio-renderer-failed source=$source target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
            )
            _error.tryEmit(
                PlayerError.DecoderError(
                    error.message ?: "Audio playback failed for this stream."
                )
            )
            return
        }

        lastAudioRendererRecoveryAtMs = now
        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition
            ?.takeIf { currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE && it > 0L }

        Log.w(
            TAG,
            "audio-renderer-recover source=$source target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )

        retryJob?.cancel()
        retryJob = null
        _retryStatus.value = null
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = false,
            seekPositionMs = seekPosition,
            autoPlay = wasPlaying
        )
    }

    private fun handlePlaybackError(error: PlaybackException) {
        retryJob?.cancel()
        retryJob = null
        val streamInfo = lastStreamInfo
        val mediaId = lastMediaId
        val retryPolicy = currentRetryPolicy
        val retryContext = currentRetryContext
        val category = PlayerErrorClassifier.classify(error)

        if (streamInfo == null || mediaId == null || retryPolicy == null || retryContext == null) {
            _retryStatus.value = null
            _error.tryEmit(PlayerError.fromException(error))
            _playbackState.value = PlaybackState.ERROR
            return
        }

        if (category == PlaybackErrorCategory.DECODER || category == PlaybackErrorCategory.FORMAT_UNSUPPORTED) {
            val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(activeDecoderMode, mediaId)
            if (fallbackMode != null) {
                Log.w(
                    TAG,
                    "decoder-preference fallback=$fallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                activeDecoderMode = fallbackMode
                prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = exoPlayer?.currentPosition, autoPlay = true)
                return
            }
        }

        val nextAttempt = retryAttempt + 1
        if (retryPolicy.shouldRetry(error, retryContext, playbackStarted, nextAttempt)) {
            val delayMs = retryPolicy.retryDelayMs(error, nextAttempt)
            val retrySeekPositionMs = exoPlayer?.currentPosition?.takeIf {
                category == PlaybackErrorCategory.FORMAT_UNSUPPORTED &&
                    currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE &&
                    it > 0L
            }
            // retryGeneration captured and checked on Main — safe with
            // Dispatchers.Main.immediate. If the scope dispatcher is ever changed
            // (e.g. in tests), convert retryGeneration to AtomicLong.
            val scheduledRetryGeneration = retryGeneration
            retryAttempt = nextAttempt
            _retryStatus.value = PlayerRetryStatus(
                attempt = nextAttempt,
                maxAttempts = retryPolicy.maxAttempts(error, playbackStarted),
                delayMs = delayMs
            )
            Log.w(
                TAG,
                "retry category=$category attempt=$nextAttempt delayMs=$delayMs reason=${retryPolicy.retryReason(error)} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            retryJob = scope.launch {
                delay(delayMs)
                if (
                    scheduledRetryGeneration != retryGeneration ||
                    lastMediaId != mediaId ||
                    retryAttempt != nextAttempt
                ) {
                    return@launch
                }
                retryJob = null
                if (category == PlaybackErrorCategory.LIVE_WINDOW &&
                    currentResolvedStreamType != ResolvedStreamType.MPEG_TS_LIVE
                ) {
                    exoPlayer?.seekToDefaultPosition()
                }
                prepareInternal(
                    streamInfo,
                    preserveRetryState = category != PlaybackErrorCategory.LIVE_WINDOW,
                    seekPositionMs = retrySeekPositionMs,
                    autoPlay = true
                )
            }
            return
        }

        _retryStatus.value = null
        Log.e(
            TAG,
            "fatal-error category=$category target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }
}
