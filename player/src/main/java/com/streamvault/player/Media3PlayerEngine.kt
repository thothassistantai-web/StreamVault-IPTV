package com.streamvault.player

import android.content.Context
import android.os.Build
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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.model.PlaybackCompatibilityRecord
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.player.audio.PlayerAudioFocusController
import com.streamvault.player.playback.ActiveDecoderPolicy
import com.streamvault.player.playback.DefaultDecoderPreferencePolicy
import com.streamvault.player.playback.DefaultPlaybackCompatibilityProfile
import com.streamvault.player.playback.AudioVideoOffsetAudioSink
import com.streamvault.player.playback.PlaybackCodecSelector
import com.streamvault.player.playback.PlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackBufferPolicies
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.playback.PlaybackRetryContext
import com.streamvault.player.playback.buildPlaybackPreparationPlan
import com.streamvault.player.playback.buildPlaybackRendererPlan
import com.streamvault.player.playback.PlayerDataSourceFactoryProvider
import com.streamvault.player.playback.PlayerErrorClassifier
import com.streamvault.player.playback.PlayerMediaSourceFactory
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.PlayerTimeoutProfile
import com.streamvault.player.playback.PreloadCoordinator
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import com.streamvault.player.playback.VideoStallDetector
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
import java.util.concurrent.atomic.AtomicLong
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
    private val okHttpClient: OkHttpClient,
    private val playbackCompatibilityRepository: PlaybackCompatibilityRepository
) : PlayerEngine {

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val AUDIO_RENDERER_RECOVERY_COOLDOWN_MS = 15_000L
        private const val TEXTURE_VIEW_STARTUP_TIMEOUT_MS = 9_000L
        private const val TEXTURE_VIEW_BUFFERED_STARTUP_THRESHOLD_MS = 4_000L
        private const val KNOWN_BAD_FAILURE_THRESHOLD = 3
        private const val MEDIA_SESSION_ID_PREFIX = "streamvault"
        private val nextMediaSessionInstanceId = AtomicLong(1L)
    }

    var constrainResolutionForMultiView: Boolean = false
    var bypassAudioFocus: Boolean = false
        set(value) {
            field = value
            audioFocusController.bypassAudioFocus = value
        }
    var mediaSessionId: String = "$MEDIA_SESSION_ID_PREFIX-${nextMediaSessionInstanceId.getAndIncrement()}"
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
    // scope is a var because resetForReuse() replaces it after cancelling the old
    // collectors. Terminal release() never recreates the scope.
    private var scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private set
    private var isDisposed = false
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var requestedDecoderMode: DecoderMode = DecoderMode.AUTO
    private var activeDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var requestedSurfaceMode: PlayerSurfaceMode = PlayerSurfaceMode.AUTO
    private var sessionSurfaceModeOverride: PlayerSurfaceMode? = null
    private var activeDecoderPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO
    private var recoveryDecoderPolicyOverride: ActiveDecoderPolicy? = null
    private var knownBadDecoderNames: Set<String> = emptySet()
    private var knownBadSurfaceTypes: Set<String> = emptySet()
    private var selectedVideoDecoderName: String = "Unknown"
    private var videoStallCount = 0
    private var videoStallRecoveryAttempt = 0
    private var videoStallSafeRecoveryPerformed = false
    private var textureViewSessionFallbackAttempted = false
    private var audioVideoSyncSinkActive = false
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

    private val audioVideoOffsetUs = AtomicLong(0L)
    private val _audioVideoOffsetMs = MutableStateFlow(0)
    override val audioVideoOffsetMs: StateFlow<Int> = _audioVideoOffsetMs.asStateFlow()
    private val _audioVideoSyncEnabled = MutableStateFlow(false)
    override val audioVideoSyncEnabled: StateFlow<Boolean> = _audioVideoSyncEnabled.asStateFlow()

    private val _renderSurfaceType = MutableStateFlow(PlayerRenderSurfaceType.SURFACE_VIEW)
    override val renderSurfaceType: StateFlow<PlayerRenderSurfaceType> = _renderSurfaceType.asStateFlow()

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
    private val videoStallDetector = VideoStallDetector()
    // All reads/writes on Dispatchers.Main.immediate (engine scope).
    @get:MainThread private var activeLiveTimeshiftStreamInfo: StreamInfo? = null
    @get:MainThread private var activeLiveTimeshiftChannelKey: String? = null
    @get:MainThread private var isPlayingTimeshiftSnapshot: Boolean = false
    private var pendingTimeshiftSeekMs: Long? = null
    private var pendingTimeshiftSeekToEnd: Boolean = false
    private var pendingTimeshiftAutoPlay: Boolean = false
    private var lastAudioRendererRecoveryAtMs: Long = 0L

    init {
        startEngineCollectors()
    }

    private fun startEngineCollectors() {
        if (isDisposed) return
        scope.launch {
            liveTimeshiftManager.state.collectLatest {
                syncTimeshiftState()
            }
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                val stats = _playerStats.value
                val stalled = videoStallDetector.shouldReportStall(
                    playbackState = _playbackState.value,
                    isPlaying = _isPlaying.value,
                    playbackStarted = playbackStarted,
                    currentPositionMs = _currentPosition.value,
                    bufferedDurationMs = stats.bufferedDurationMs
                )
                _playerStats.value = _playerStats.value.copy(
                    lastVideoFrameAgoMs = videoStallDetector.lastVideoFrameAgoMs(),
                    videoStallCount = videoStallCount,
                    videoDecoderName = selectedVideoDecoderName,
                    activeDecoderPolicy = activeDecoderPolicy.name,
                    renderSurfaceType = _renderSurfaceType.value.name,
                    audioVideoSyncEnabled = _audioVideoSyncEnabled.value,
                    audioVideoSyncSinkActive = audioVideoSyncSinkActive
                )
                if (shouldFallbackTextureViewBeforeFirstFrame(stats)) {
                    fallbackTextureViewSurface("NO_FIRST_FRAME")
                    continue
                }
                if (stalled) handleVideoStall()
            }
        }
    }

    override fun prepare(streamInfo: StreamInfo) {
        if (ensureNotDisposed("prepare")) return
        prepareInternal(streamInfo = streamInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
    }

    override fun renewStreamUrl(streamInfo: StreamInfo) {
        if (ensureNotDisposed("renewStreamUrl")) return
        val player = exoPlayer ?: return
        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = false,
            playbackStarted = { playbackStarted }
        )

        lastStreamInfo = streamInfo
        lastMediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        currentResolvedStreamType = playbackPlan.resolvedStreamType
        currentTimeoutProfile = playbackPlan.timeoutProfile
        currentRetryContext = playbackPlan.retryContext
        currentRetryPolicy = playbackPlan.retryPolicy
        retryAttempt = 0
        _retryStatus.value = null
        playbackStarted = false

        val mediaSource = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = currentResolvedStreamType,
            retryPolicy = playbackPlan.retryPolicy,
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

    override fun setSurfaceMode(mode: PlayerSurfaceMode) {
        if (requestedSurfaceMode == mode) return
        requestedSurfaceMode = mode
        sessionSurfaceModeOverride = null
        textureViewSessionFallbackAttempted = false
        updateRenderSurfaceForMode()
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

    override fun setAudioVideoSyncEnabled(enabled: Boolean) {
        if (_audioVideoSyncEnabled.value == enabled) return
        _audioVideoSyncEnabled.value = enabled
        if (!enabled) {
            _audioVideoOffsetMs.value = 0
            audioVideoOffsetUs.set(0L)
        }
        val streamInfo = lastStreamInfo ?: return
        val player = exoPlayer ?: return
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.takeIf { it > 0L }
        prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = position, autoPlay = wasPlaying)
    }

    override fun setAudioVideoOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
        val effectiveOffset = if (_audioVideoSyncEnabled.value) clamped else 0
        _audioVideoOffsetMs.value = effectiveOffset
        audioVideoOffsetUs.set(effectiveOffset * 1_000L)
    }

    @MainThread
    override fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        if (ensureNotDisposed("startLiveTimeshift")) return
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

    override fun addExternalSubtitle(subtitleUri: android.net.Uri, language: String) {
        val player = exoPlayer ?: return
        val streamInfo = lastStreamInfo ?: return
        val retryPolicy = currentRetryPolicy ?: return
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady

        val (_, mainMediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = currentResolvedStreamType,
            retryPolicy = retryPolicy,
            preload = false
        )
        val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val subtitleSource = androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(context)
        ).createMediaSource(subtitleConfig, C.TIME_UNSET)
        val merged = androidx.media3.exoplayer.source.MergingMediaSource(mainMediaSource, subtitleSource)

        player.setMediaSource(merged, position)
        player.prepare()
        player.playWhenReady = wasPlaying
        Log.i(TAG, "addExternalSubtitle language=$language uri=$subtitleUri")
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
        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = true,
            playbackStarted = { false }
        )
        val (_, mediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = playbackPlan.resolvedStreamType,
            retryPolicy = playbackPlan.retryPolicy,
            preload = true
        )
        preloadCoordinator.store(mediaId, streamInfo, playbackPlan.resolvedStreamType, mediaSource)
    }

    override fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType
    ) = viewBinder.createRenderView(context, resizeMode, surfaceType)

    override fun bindRenderView(renderView: android.view.View, resizeMode: PlayerSurfaceResizeMode) {
        if (ensureNotDisposed("bindRenderView")) return
        viewBinder.bind(renderView, getOrCreatePlayer(), resizeMode)
    }

    override fun clearRenderBinding() {
        viewBinder.attachPlayer(null)
    }

    override fun releaseRenderView(renderView: android.view.View) {
        viewBinder.release(renderView)
    }

    override fun release() {
        if (isDisposed) return
        isDisposed = true
        resetEngineState(restartCollectors = false)
    }

    override fun resetForReuse() {
        if (isDisposed) {
            Log.w(TAG, "resetForReuse ignored after terminal release")
            return
        }
        resetEngineState(restartCollectors = true)
    }

    private fun resetEngineState(restartCollectors: Boolean) {
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
        videoStallDetector.reset()
        selectedVideoDecoderName = "Unknown"
        videoStallCount = 0
        videoStallRecoveryAttempt = 0
        recoveryDecoderPolicyOverride = null
        knownBadDecoderNames = emptySet()
        knownBadSurfaceTypes = emptySet()
        sessionSurfaceModeOverride = null
        textureViewSessionFallbackAttempted = false
        audioVideoSyncSinkActive = false
        activeLiveTimeshiftStreamInfo = null
        activeLiveTimeshiftChannelKey = null
        isPlayingTimeshiftSnapshot = false
        _timeshiftState.value = LiveTimeshiftState()
        scope.cancel()
        if (restartCollectors) {
            scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            startEngineCollectors()
        }
        // File cleanup runs outside the engine scope — orphans are also cleaned on next app start
        CoroutineScope(Dispatchers.IO).launch { liveTimeshiftManager.stopSession() }
    }

    private fun ensureNotDisposed(action: String): Boolean {
        if (!isDisposed) return false
        Log.w(TAG, "$action ignored after terminal release")
        return true
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
            sessionSurfaceModeOverride = null
            textureViewSessionFallbackAttempted = false
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
        }
        lastMediaId = mediaId
        playbackStarted = false
        prepareStartMs = System.currentTimeMillis()
        audioCodecUnsupportedReported = false
        _error.tryEmit(null)
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()
        videoStallDetector.reset()
        if (!preserveRetryState) {
            videoStallRecoveryAttempt = 0
            videoStallSafeRecoveryPerformed = false
        }
        selectedVideoDecoderName = "Unknown"

        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = false,
            playbackStarted = { playbackStarted }
        )
        currentResolvedStreamType = playbackPlan.resolvedStreamType
        currentTimeoutProfile = playbackPlan.timeoutProfile
        currentRetryContext = playbackPlan.retryContext
        currentRetryPolicy = playbackPlan.retryPolicy
        if (!preserveRetryState) {
            recoveryDecoderPolicyOverride = null
        }
        val preferredDecoderMode = decoderPreferencePolicy.preferredMode(requestedDecoderMode, mediaId)
        val nextDecoderPolicy = recoveryDecoderPolicyOverride ?: resolveActiveDecoderPolicy(
            policyModeFor(requestedDecoderMode, preferredDecoderMode)
        )
        val isLiveBuffer = currentResolvedStreamType in setOf(
            ResolvedStreamType.HLS, ResolvedStreamType.MPEG_TS_LIVE, ResolvedStreamType.RTSP
        )
        val previousDecoderPolicy = activeDecoderPolicy
        val needsRecreate = activeDecoderMode != preferredDecoderMode ||
            previousDecoderPolicy != nextDecoderPolicy ||
            isLiveBuffer != currentBufferIsLive ||
            requestedDecoderMode == DecoderMode.COMPATIBILITY
        activeDecoderMode = preferredDecoderMode
        activeDecoderPolicy = nextDecoderPolicy
        currentBufferIsLive = isLiveBuffer
        updateRenderSurfaceForMode()
        if (needsRecreate) {
            recreatePlayer()
        }

        Log.i(
            TAG,
            "prepare resolvedStreamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile decoderPreference=$activeDecoderMode activePolicy=$activeDecoderPolicy surface=${_renderSurfaceType.value} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        try {
            val player = getOrCreatePlayer()
            trackController.applyInitialParameters(player, constrainResolutionForMultiView)
            if (activeDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, 720)
                    .build()
            }
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
        } catch (error: Exception) {
            handlePlaybackSetupFailure(error, streamInfo, mediaId, seekPositionMs, autoPlay)
        }
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
        check(!isDisposed) { "Cannot create a player after terminal release" }
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
        val bufferPolicy = PlaybackBufferPolicies.forPlayback(
            isLive = currentBufferIsLive == true,
            compatibilityMode = activeDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferPolicy.minBufferMs,
                bufferPolicy.maxBufferMs,
                bufferPolicy.playbackBufferMs,
                bufferPolicy.rebufferMs
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
        val decoderPolicy = activeDecoderPolicy
        val badDecoderNames = knownBadDecoderNames
        val requestedMode = requestedDecoderMode
        val rendererPlan = buildPlaybackRendererPlan(
            requestedMode = requestedMode,
            decoderPolicy = decoderPolicy,
            useAudioVideoSyncSink = _audioVideoSyncEnabled.value,
            useVideoRendererWorkaround = compatibilityProfile.shouldDisableDecoderReuseWorkaround()
        )
        audioVideoSyncSinkActive = false

        val factory = if (rendererPlan.useStockRenderersFactory) {
            DefaultRenderersFactory(context)
        } else {
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    val audioSink = requireNotNull(
                        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                    ) {
                        "DefaultRenderersFactory did not provide an AudioSink."
                    }
                    if (!rendererPlan.useAudioVideoSyncSink) {
                        audioVideoSyncSinkActive = false
                        return audioSink
                    }
                    audioVideoSyncSinkActive = true
                    return AudioVideoOffsetAudioSink(
                        delegate = audioSink,
                        offsetUsProvider = { audioVideoOffsetUs.get() }
                    )
                }

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
                    if (!rendererPlan.useVideoRendererWorkaround) {
                        super.buildVideoRenderers(
                            context,
                            extensionRendererMode,
                            mediaCodecSelector,
                            enableDecoderFallback,
                            eventHandler,
                            eventListener,
                            allowedVideoJoiningTimeMs,
                            out
                        )
                        return
                    }

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
        }

        return factory.apply {
            setEnableDecoderFallback(true)
            if (rendererPlan.useManagedCodecSelector) {
                setMediaCodecSelector(
                    PlaybackCodecSelector(
                        policyProvider = { decoderPolicy },
                        knownBadProvider = { badDecoderNames }
                    )
                )
            }
            setExtensionRendererMode(
                when (activeDecoderMode) {
                    DecoderMode.AUTO, DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderMode.SOFTWARE, DecoderMode.COMPATIBILITY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
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
                refreshKnownBadCompatibilityRecords()
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                selectedVideoDecoderName = decoderName
                _playerStats.value = _playerStats.value.copy(videoDecoderName = decoderName)
                Log.i(TAG, "video-decoder name=$decoderName policy=$activeDecoderPolicy surface=${_renderSurfaceType.value}")
                refreshKnownBadCompatibilityRecords()
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
        recordCompatibilitySuccess()
        refreshKnownBadCompatibilityRecords()
    }

    private fun resolveActiveDecoderPolicy(mode: DecoderMode): ActiveDecoderPolicy = when (mode) {
        DecoderMode.AUTO -> ActiveDecoderPolicy.AUTO
        DecoderMode.HARDWARE -> ActiveDecoderPolicy.HARDWARE_PREFERRED
        DecoderMode.SOFTWARE -> ActiveDecoderPolicy.SOFTWARE_PREFERRED
        DecoderMode.COMPATIBILITY -> ActiveDecoderPolicy.COMPATIBILITY
    }

    private fun policyModeFor(requestedMode: DecoderMode, preferredMode: DecoderMode): DecoderMode = when {
        requestedMode == DecoderMode.COMPATIBILITY -> DecoderMode.COMPATIBILITY
        preferredMode == DecoderMode.SOFTWARE -> DecoderMode.SOFTWARE
        else -> requestedMode
    }

    private fun updateRenderSurfaceForMode() {
        val surfaceMode = sessionSurfaceModeOverride
            ?: when {
                requestedSurfaceMode == PlayerSurfaceMode.TEXTURE_VIEW &&
                    knownBadSurfaceTypes.contains(PlayerRenderSurfaceType.TEXTURE_VIEW.name) -> {
                    PlayerSurfaceMode.SURFACE_VIEW
                }
                else -> requestedSurfaceMode
            }
        _renderSurfaceType.value = when (surfaceMode) {
            PlayerSurfaceMode.SURFACE_VIEW -> PlayerRenderSurfaceType.SURFACE_VIEW
            PlayerSurfaceMode.TEXTURE_VIEW -> PlayerRenderSurfaceType.TEXTURE_VIEW
            PlayerSurfaceMode.AUTO -> PlayerRenderSurfaceType.SURFACE_VIEW
        }
    }

    private fun refreshKnownBadCompatibilityRecords() {
        if (!shouldUseCompatibilityHistory()) {
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
            return
        }
        val video = _videoFormat.value
        val videoMime = video.codecV ?: _playerStats.value.videoCodec
        val bucket = resolutionBucket(video.width.takeIf { it > 0 } ?: _playerStats.value.width)
        if (selectedVideoDecoderName.isUnknownLike() || videoMime.isUnknownLike() || bucket.isUnknownLike()) {
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
            return
        }
        val lookupGeneration = retryGeneration
        scope.launch(Dispatchers.IO) {
            val records = runCatching {
                playbackCompatibilityRepository.getKnownBadRecords(
                    deviceFingerprint = Build.FINGERPRINT.orEmpty(),
                    streamType = currentResolvedStreamType.name,
                    videoMimeType = videoMime,
                    resolutionBucket = bucket
                )
            }.getOrElse { throwable ->
                Log.w(TAG, "compatibility-history lookup failed: ${throwable.sanitizedSummary()}")
                emptyList()
            }
            scope.launch {
                if (lookupGeneration != retryGeneration) return@launch
                val actionableRecords = records.filter(::isHighConfidenceKnownBad)
                val names = PlaybackCodecSelector.knownBadDecoderNames(actionableRecords)
                val surfaces = actionableRecords.map { it.key.surfaceType }.toSet()
                knownBadDecoderNames = names
                knownBadSurfaceTypes = surfaces
            }
        }
    }

    private fun shouldUseCompatibilityHistory(): Boolean {
        return playbackStarted ||
            retryAttempt > 0 ||
            videoStallRecoveryAttempt > 0 ||
            recoveryDecoderPolicyOverride != null ||
            textureViewSessionFallbackAttempted
    }

    private fun handleVideoStall() {
        val streamInfo = lastStreamInfo ?: return
        videoStallCount++
        recordCompatibilityFailure("VIDEO_STALL")
        _playerStats.value = _playerStats.value.copy(videoStallCount = videoStallCount)

        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition?.takeIf { it > 0L }
        Log.w(
            TAG,
            "video-stall count=$videoStallCount recovered=$videoStallSafeRecoveryPerformed decoder=$selectedVideoDecoderName policy=$activeDecoderPolicy surface=${_renderSurfaceType.value} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        if (!playbackStarted) {
            Log.w(TAG, "video-stall ignored because playback has not started yet")
            return
        }

        videoStallRecoveryAttempt++
        if (!videoStallSafeRecoveryPerformed) {
            videoStallSafeRecoveryPerformed = true
            Log.w(TAG, "video-stall safe reprepare attempt=$videoStallRecoveryAttempt")
            prepareInternal(
                streamInfo = streamInfo,
                preserveRetryState = true,
                seekPositionMs = seekPosition,
                autoPlay = wasPlaying
            )
            return
        }

        if (tryVideoStallDecoderFallback(streamInfo, seekPosition, wasPlaying)) {
            return
        }

        if (_renderSurfaceType.value == PlayerRenderSurfaceType.TEXTURE_VIEW && !textureViewSessionFallbackAttempted) {
            fallbackTextureViewSurface("VIDEO_STALL")
            return
        }

        _error.tryEmit(
            PlayerError.DecoderError(
                "Video playback stalled on this device. Try another stream or open diagnostics."
            )
        )
    }

    private fun tryVideoStallDecoderFallback(
        streamInfo: StreamInfo,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ): Boolean {
        val mediaId = lastMediaId ?: return false
        val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(requestedDecoderMode, mediaId) ?: return false
        val fallbackPolicy = resolveActiveDecoderPolicy(policyModeFor(requestedDecoderMode, fallbackMode))
        recoveryDecoderPolicyOverride = fallbackPolicy
        Log.w(
            TAG,
            "video-stall decoder fallback=$fallbackMode policy=$fallbackPolicy attempt=$videoStallRecoveryAttempt mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = seekPositionMs,
            autoPlay = autoPlay
        )
        return true
    }

    private fun recordCompatibilityFailure(failureType: String) {
        val key = currentCompatibilityKey() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                playbackCompatibilityRepository.recordFailure(key, failureType)
            }.onFailure { throwable ->
                Log.w(TAG, "compatibility-history failure record skipped: ${throwable.sanitizedSummary()}")
            }
        }
    }

    private fun recordCompatibilitySuccess() {
        val key = currentCompatibilityKey() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                playbackCompatibilityRepository.recordSuccess(key)
            }.onFailure { throwable ->
                Log.w(TAG, "compatibility-history success record skipped: ${throwable.sanitizedSummary()}")
            }
        }
    }

    private fun currentCompatibilityKey(): PlaybackCompatibilityKey? {
        val decoder = selectedVideoDecoderName.takeUnless { it.isUnknownLike() } ?: return null
        val video = _videoFormat.value
        val width = video.width.takeIf { it > 0 } ?: _playerStats.value.width
        val mime = video.codecV ?: _playerStats.value.videoCodec
        val videoMimeType = mime.takeUnless { it.isUnknownLike() } ?: return null
        val resolutionBucket = resolutionBucket(width).takeUnless { it.isUnknownLike() } ?: return null
        return PlaybackCompatibilityKey(
            deviceFingerprint = Build.FINGERPRINT.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            streamType = currentResolvedStreamType.name,
            videoMimeType = videoMimeType,
            resolutionBucket = resolutionBucket,
            decoderName = decoder,
            surfaceType = _renderSurfaceType.value.name
        )
    }

    private fun resolutionBucket(width: Int): String = when {
        width >= 3800 -> "4K"
        width >= 1900 -> "1080P"
        width >= 1200 -> "720P"
        width > 0 -> "SD"
        else -> "UNKNOWN"
    }

    private fun isHighConfidenceKnownBad(record: PlaybackCompatibilityRecord): Boolean {
        val key = record.key
        if (record.failureCount < KNOWN_BAD_FAILURE_THRESHOLD) return false
        if (record.lastSucceededAt > record.lastFailedAt) return false
        if (key.videoMimeType.isUnknownLike()) return false
        if (key.resolutionBucket.isUnknownLike()) return false
        if (key.decoderName.isUnknownLike()) return false
        if (key.surfaceType.isUnknownLike()) return false
        return true
    }

    private fun String.isUnknownLike(): Boolean {
        val normalized = trim().lowercase(java.util.Locale.ROOT)
        return normalized.isEmpty() || normalized == "unknown"
    }

    private fun Throwable.sanitizedSummary(): String {
        val type = this::class.java.simpleName.ifBlank { "Throwable" }
        return "$type: ${PlaybackLogSanitizer.sanitizeMessage(message)}"
    }

    private fun shouldFallbackTextureViewBeforeFirstFrame(stats: PlayerStats): Boolean {
        if (_renderSurfaceType.value != PlayerRenderSurfaceType.TEXTURE_VIEW) return false
        if (sessionSurfaceModeOverride == PlayerSurfaceMode.SURFACE_VIEW) return false
        if (textureViewSessionFallbackAttempted) return false
        if (lastStreamInfo == null) return false
        if (playbackStarted) return false
        if (!isCurrentStreamLive()) return false
        val state = _playbackState.value
        if (state != PlaybackState.READY && state != PlaybackState.BUFFERING) return false
        if (System.currentTimeMillis() - prepareStartMs < TEXTURE_VIEW_STARTUP_TIMEOUT_MS) return false
        if (state == PlaybackState.BUFFERING && stats.bufferedDurationMs < TEXTURE_VIEW_BUFFERED_STARTUP_THRESHOLD_MS) {
            return false
        }
        if (selectedVideoDecoderName == "Unknown" && state != PlaybackState.READY) return false
        return true
    }

    private fun fallbackTextureViewSurface(reason: String) {
        val streamInfo = lastStreamInfo ?: return
        if (_renderSurfaceType.value != PlayerRenderSurfaceType.TEXTURE_VIEW) return
        if (textureViewSessionFallbackAttempted) return

        textureViewSessionFallbackAttempted = true
        sessionSurfaceModeOverride = PlayerSurfaceMode.SURFACE_VIEW
        updateRenderSurfaceForMode()
        recordCompatibilityFailure("TEXTURE_VIEW_$reason")

        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition?.takeIf {
            currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE && it > 0L
        }
        Log.w(
            TAG,
            "texture-view fallback reason=$reason decoder=$selectedVideoDecoderName policy=$activeDecoderPolicy target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        viewBinder.attachPlayer(null)
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = seekPosition,
            autoPlay = wasPlaying
        )
    }

    private fun isCurrentStreamLive(): Boolean = currentResolvedStreamType in setOf(
        ResolvedStreamType.HLS,
        ResolvedStreamType.MPEG_TS_LIVE,
        ResolvedStreamType.RTSP
    )

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

    private fun handlePlaybackSetupFailure(
        error: Exception,
        streamInfo: StreamInfo,
        mediaId: String,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        val category = PlayerErrorClassifier.classify(error)
        Log.e(
            TAG,
            "prepare-failed category=$category decoderPreference=$activeDecoderMode activePolicy=$activeDecoderPolicy target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}",
            error
        )

        if (category == PlaybackErrorCategory.DECODER || category == PlaybackErrorCategory.FORMAT_UNSUPPORTED) {
            recordCompatibilityFailure("DECODER_INIT")
            val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(requestedDecoderMode, mediaId)
            if (fallbackMode != null) {
                Log.w(
                    TAG,
                    "decoder-setup fallback=$fallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                recreatePlayer()
                prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = seekPositionMs, autoPlay = autoPlay)
                return
            }
        }

        recreatePlayer()
        _retryStatus.value = null
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
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
            val fallbackMode = decoderPreferencePolicy.onDecoderInitFailure(requestedDecoderMode, mediaId)
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
