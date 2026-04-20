package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.cast.CastManager
import com.streamvault.app.cast.CastMediaRequest
import com.streamvault.app.cast.CastStartResult
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.app.ui.model.orderedByRequestedRawIds
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.MarkAsWatched
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerSubtitleStyle
import com.streamvault.player.timeshift.LiveTimeshiftBackend
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import com.streamvault.player.timeshift.TimeshiftConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel @Inject constructor(
    @param:MainPlayerEngine
    val playerEngine: PlayerEngine,
    private val epgRepository: EpgRepository,
    internal val channelRepository: ChannelRepository,
    internal val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val favoriteRepository: com.streamvault.domain.repository.FavoriteRepository,
    internal val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: com.streamvault.domain.repository.ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val getCustomCategories: GetCustomCategories,
    private val markAsWatched: MarkAsWatched,
    internal val scheduleRecordingUseCase: ScheduleRecording,
    internal val recordingManager: RecordingManager,
    private val watchNextManager: WatchNextManager,
    private val launcherRecommendationsManager: LauncherRecommendationsManager,
    internal val castManager: CastManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val seekThumbnailProvider: SeekThumbnailProvider,
    private val okHttpClient: OkHttpClient
) : ViewModel() {
    companion object {
        private const val MUTE_TOGGLE_DEBOUNCE_MS = 250L
        private const val MAX_PROGRAM_HISTORY_ITEMS = 18
        private const val MAX_UPCOMING_PROGRAM_ITEMS = 24
        private const val PLAYER_EPG_REFRESH_INTERVAL_MS = 30_000L
        private const val MIN_WATCHED_FOR_AUTO_PLAY_MS = 5_000L
        private const val TOKEN_RENEWAL_LEAD_MS = 60_000L
        private const val TOKEN_RENEWAL_CHECK_INTERVAL_MS = 10_000L
        private const val LOW_BANDWIDTH_THRESHOLD_BPS = 500_000L
        private const val LOW_BANDWIDTH_DURATION_SECONDS = 30
    }

    internal val showControlsFlow = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = showControlsFlow.asStateFlow()

    private val _isCatchUpPlayback = MutableStateFlow(false)
    val isCatchUpPlayback: StateFlow<Boolean> = _isCatchUpPlayback.asStateFlow()

    internal val showZapOverlayFlow = MutableStateFlow(false)
    val showZapOverlay: StateFlow<Boolean> = showZapOverlayFlow.asStateFlow()
    
    private val _currentProgram = MutableStateFlow<Program?>(null)
    val currentProgram: StateFlow<Program?> = _currentProgram.asStateFlow()

    private val _nextProgram = MutableStateFlow<Program?>(null)
    val nextProgram: StateFlow<Program?> = _nextProgram.asStateFlow()

    private val _programHistory = MutableStateFlow<List<Program>>(emptyList())
    val programHistory: StateFlow<List<Program>> = _programHistory.asStateFlow()

    private val _upcomingPrograms = MutableStateFlow<List<Program>>(emptyList())
    val upcomingPrograms: StateFlow<List<Program>> = _upcomingPrograms.asStateFlow()

    internal val currentChannelFlow = MutableStateFlow<com.streamvault.domain.model.Channel?>(null)
    val currentChannel: StateFlow<com.streamvault.domain.model.Channel?> = currentChannelFlow.asStateFlow()

    private val _currentSeries = MutableStateFlow<Series?>(null)
    val currentSeries: StateFlow<Series?> = _currentSeries.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    private val _nextEpisode = MutableStateFlow<Episode?>(null)
    val nextEpisode: StateFlow<Episode?> = _nextEpisode.asStateFlow()

    internal val playbackTitleFlow = MutableStateFlow("")
    val playbackTitle: StateFlow<String> = playbackTitleFlow.asStateFlow()
    
    private val _resumePrompt = MutableStateFlow(ResumePromptState())
    val resumePrompt: StateFlow<ResumePromptState> = _resumePrompt.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    internal val showChannelListOverlayFlow = MutableStateFlow(false)
    val showChannelListOverlay: StateFlow<Boolean> = showChannelListOverlayFlow.asStateFlow()

    internal val showEpgOverlayFlow = MutableStateFlow(false)
    val showEpgOverlay: StateFlow<Boolean> = showEpgOverlayFlow.asStateFlow()

    private val currentChannelFlowList = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val currentChannelList: StateFlow<List<com.streamvault.domain.model.Channel>> = currentChannelFlowList.asStateFlow()

    internal val recentChannelsFlow = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val recentChannels: StateFlow<List<com.streamvault.domain.model.Channel>> = recentChannelsFlow.asStateFlow()

    private val _lastVisitedCategory = MutableStateFlow<Category?>(null)
    val lastVisitedCategory: StateFlow<Category?> = _lastVisitedCategory.asStateFlow()

    internal val showCategoryListOverlayFlow = MutableStateFlow(false)
    val showCategoryListOverlay: StateFlow<Boolean> = showCategoryListOverlayFlow.asStateFlow()

    internal val availableCategoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    val availableCategories: StateFlow<List<Category>> = availableCategoriesFlow.asStateFlow()

    internal val activeCategoryIdFlow = MutableStateFlow(-1L)
    val activeCategoryId: StateFlow<Long> = activeCategoryIdFlow.asStateFlow()

    internal val displayChannelNumberFlow = MutableStateFlow(0)
    val displayChannelNumber: StateFlow<Int> = displayChannelNumberFlow.asStateFlow()

    internal val showChannelInfoOverlayFlow = MutableStateFlow(false)
    val showChannelInfoOverlay: StateFlow<Boolean> = showChannelInfoOverlayFlow.asStateFlow()

    internal val numericChannelInputFlow = MutableStateFlow<NumericChannelInputState?>(null)
    val numericChannelInput: StateFlow<NumericChannelInputState?> = numericChannelInputFlow.asStateFlow()

    internal val showDiagnosticsFlow = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = showDiagnosticsFlow.asStateFlow()

    private val _playerNotice = MutableStateFlow<PlayerNoticeState?>(null)
    val playerNotice: StateFlow<PlayerNoticeState?> = _playerNotice.asStateFlow()
    private val _playerDiagnostics = MutableStateFlow(PlayerDiagnosticsUiState())
    val playerDiagnostics: StateFlow<PlayerDiagnosticsUiState> = _playerDiagnostics.asStateFlow()
    private val _seekPreview = MutableStateFlow(SeekPreviewState())
    val seekPreview: StateFlow<SeekPreviewState> = _seekPreview.asStateFlow()
    private val _recordingItems = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordingItems: StateFlow<List<RecordingItem>> = _recordingItems.asStateFlow()
    private val currentChannelFlowRecording = MutableStateFlow<RecordingItem?>(null)
    val currentChannelRecording: StateFlow<RecordingItem?> = currentChannelFlowRecording.asStateFlow()
    private val _timeshiftUiState = MutableStateFlow(PlayerTimeshiftUiState())
    val timeshiftUiState: StateFlow<PlayerTimeshiftUiState> = _timeshiftUiState.asStateFlow()

    internal var channelInfoHideJob: Job? = null
    internal var liveOverlayHideJob: Job? = null
    internal var diagnosticsHideJob: Job? = null
    internal var numericInputCommitJob: Job? = null
    internal var numericInputFeedbackJob: Job? = null
    private var playerNoticeHideJob: Job? = null
    private var mutePersistJob: Job? = null
    private var recoveryJob: Job? = null
    internal var numericInputBuffer: String = ""
    internal val triedAlternativeStreams = mutableSetOf<String>()
    private val failedStreamsThisSession = mutableMapOf<String, Int>()
    private var hasRetriedWithSoftwareDecoder = false
    private var hasRetriedXtreamAuthRefresh = false
    private val probePassedProviderIds = mutableSetOf<Long>()
    private val notifiedRecordingFailureIds = mutableSetOf<String>()
    internal var lastRecordedLivePlaybackKey: Pair<Long, Long>? = null
    private var currentStreamClassLabel: String = "Primary"
    private var lastRecordedVariantObservationSignature: String? = null
    private var prepareRequestVersion: Long = 0L
    internal var currentArtworkUrl: String? = null
    private var currentResolvedPlaybackUrl: String = ""
    internal var pendingCatchUpUrls: List<String> = emptyList()
    internal var channelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP
        set(value) {
            field = value
            rebuildChannelNumberIndex()
        }
    private var activeEpgRequestKey: EpgRequestKey? = null
    internal var playerControlsTimeoutMs: Long = 5_000L
    internal var liveOverlayTimeoutMs: Long = 4_000L
    private var playerNoticeTimeoutMs: Long = 6_000L
    internal var diagnosticsTimeoutMs: Long = 15_000L
    private var preferredDecoderMode: DecoderMode = DecoderMode.AUTO
    private var timeshiftConfig: TimeshiftConfig = TimeshiftConfig()

    // Zapping state
    //
    // Invariant: `currentChannelIndex` is always -1 (no channel loaded) or a valid index
    // into `channelList`. Code that updates either field must maintain this relationship.
    // `channelList` is replaced asynchronously by the playlist flow collector; after
    // replacement, `currentChannelIndex` is recomputed in the same collector block,
    // so the invariant holds at rest. `changeChannel()` verifies the invariant at entry.
    /**
     * Ordered list of channels in the current category, set by the playlist [combine]
     * collector. Linked to [currentChannelIndex] — see invariant comment above.
     */
    internal var channelList: List<com.streamvault.domain.model.Channel> = emptyList()
        set(value) {
            field = value
            rebuildChannelNumberIndex()
        }
    internal var channelNumberIndex: Map<Int, com.streamvault.domain.model.Channel> = emptyMap()
        private set

    private fun rebuildChannelNumberIndex() {
        channelNumberIndex = channelList.withIndex().associate { (index, channel) ->
            resolveChannelNumber(channel, index) to channel
        }
    }

    internal var currentChannelIndex = -1
    internal var previousChannelIndex = -1
    internal var currentCategoryId: Long = -1
    internal var currentProviderId: Long = -1L
    internal var currentCombinedProfileId: Long? = null
    internal var currentCombinedSourceFilterProviderId: Long? = null
    internal var currentContentId: Long = -1L
    internal var currentContentType: ContentType = ContentType.LIVE
    internal var currentTitle: String = ""
    private var currentSeriesId: Long? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null
    internal var isVirtualCategory: Boolean = false
    private var currentCombinedProfileMembers: List<CombinedM3uProfileMember> = emptyList()
    private var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()
    private var lastObservedPlaybackState: PlaybackState = PlaybackState.IDLE

    private var epgJob: Job? = null
    private var playlistJob: Job? = null
    private var recentChannelsJob: Job? = null
    private var lastVisitedCategoryJob: Job? = null
    internal var controlsHideJob: Job? = null
    private var seekPreviewJob: Job? = null
    private var thumbnailPreloadJob: Job? = null
    private var lowBandwidthMonitorJob: Job? = null
    private var progressTrackingJob: Job? = null
    private var tokenRenewalJob: Job? = null
    internal var zapOverlayJob: Job? = null
    private var aspectRatioJob: Job? = null
    internal var zapBufferWatchdogJob: Job? = null
    internal var zapAutoRevertEnabled: Boolean = true
    private var isAppInForeground: Boolean = true
    private var shouldResumeAfterForeground: Boolean = false
    private var seekPreviewRequestVersion: Long = 0L
    private var lastMuteToggleAtMs: Long = 0L

    val castConnectionState: StateFlow<CastConnectionState> = castManager.connectionState

    internal fun logRepositoryFailure(operation: String, result: com.streamvault.domain.model.Result<Unit>) {
        if (result is com.streamvault.domain.model.Result.Error) {
            android.util.Log.w("PlayerVM", "$operation failed: ${result.message}", result.exception)
        }
    }

    private fun applyTimeshiftState(state: LiveTimeshiftState) {
        val backendLabel = when (state.backend) {
            LiveTimeshiftBackend.DISK -> "Disk"
            LiveTimeshiftBackend.MEMORY -> "Memory"
            LiveTimeshiftBackend.NONE -> ""
        }
        val visibleForLiveUi = timeshiftConfig.enabled &&
            state.status != LiveTimeshiftStatus.DISABLED &&
            state.status != LiveTimeshiftStatus.UNSUPPORTED &&
            state.status != LiveTimeshiftStatus.FAILED
        _timeshiftUiState.value = PlayerTimeshiftUiState(
            available = visibleForLiveUi,
            enabledForSession = timeshiftConfig.enabled,
            backendLabel = backendLabel,
            bufferedBehindLiveMs = state.currentOffsetFromLiveMs,
            bufferDepthMs = state.bufferedDurationMs.takeIf { it > 0L } ?: timeshiftConfig.depthMs,
            canSeekToLive = state.canSeekToLive,
            statusMessage = state.message.orEmpty(),
            engineState = state
        )
    }

    private fun maybeStartLiveTimeshift(streamInfoOverride: StreamInfo? = null) {
        if (currentContentType != ContentType.LIVE || !timeshiftConfig.enabled) {
            playerEngine.stopLiveTimeshift()
            return
        }
        if (currentStreamClassLabel == "Catch-up") {
            playerEngine.stopLiveTimeshift()
            return
        }
        val resolvedCandidate = currentResolvedPlaybackUrl.safeTrimmedOrNull()
        val currentCandidate = currentStreamUrl.safeTrimmedOrNull()
        val fallbackUrl = resolvedCandidate ?: currentCandidate ?: run {
            playerEngine.stopLiveTimeshift()
            _timeshiftUiState.update {
                it.copy(
                    available = false,
                    enabledForSession = timeshiftConfig.enabled,
                    statusMessage = "Local live rewind is unavailable for this stream."
                )
            }
            return
        }
        val streamInfo = streamInfoOverride ?: StreamInfo(
            url = fallbackUrl,
            title = playbackTitleFlow.value.ifBlank { currentTitle }
        )
        val channelKey = currentChannel.value?.id?.toString()
            ?: currentContentId.takeIf { it > 0L }?.toString()
            ?: fallbackUrl
        _timeshiftUiState.update {
            it.copy(
                available = true,
                enabledForSession = true,
                statusMessage = "Preparing local live rewind…",
                bufferDepthMs = timeshiftConfig.depthMs
            )
        }
        playerEngine.startLiveTimeshift(streamInfo, channelKey, timeshiftConfig)
    }

    private fun String?.safeTrimmedOrNull(): String? {
        val value = this ?: return null
        return value.trim().takeIf { it.isNotEmpty() }
    }

    init {
        viewModelScope.launch {
            playerEngine.error.collect { error ->
                if (error != null) {
                    handlePlaybackError(error)
                }
            }
        }
        viewModelScope.launch {
            playerEngine.playbackState.collect { state ->
                _playerDiagnostics.update { it.copy(playbackStateLabel = state.name.replace('_', ' ')) }
                if (state == PlaybackState.ENDED && lastObservedPlaybackState != PlaybackState.ENDED) {
                    handlePlaybackEnded()
                }
                lastObservedPlaybackState = state
                if (state == PlaybackState.READY) {
                    zapBufferWatchdogJob?.cancel()
                    dismissRecoveredNoticeIfPresent()
                    if (currentContentType == ContentType.LIVE) {
                        currentChannelFlow.value?.sanitizedForPlayer()?.let { channel ->
                            if (channel.errorCount > 0) {
                                logRepositoryFailure(
                                    operation = "Reset channel error count",
                                    result = channelRepository.resetChannelErrorCount(channel.id)
                                )
                            }
                        }
                    } else {
                        startThumbnailPreload()
                    }
                }
            }
        }
        viewModelScope.launch {
            playerEngine.retryStatus.collect { status ->
                status ?: return@collect
                showRetryNotice(status)
            }
        }
        viewModelScope.launch {
            playerEngine.audioFocusDenied.collect {
                showPlayerNotice(
                    message = "Waiting for audio \u2014 unmute device and press Play",
                    durationMs = 8_000L
                )
            }
        }
        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                handleRecordingStateChanges(previousItems = _recordingItems.value, newItems = items)
                _recordingItems.value = items
                refreshCurrentChannelRecording(items)
            }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerSubtitleTextScale,
                preferencesRepository.playerSubtitleTextColor,
                preferencesRepository.playerSubtitleBackgroundColor
            ) { textScale, textColor, backgroundColor ->
                PlayerSubtitleStyle(
                    textScale = textScale,
                    foregroundColorArgb = textColor,
                    backgroundColorArgb = backgroundColor
                )
            }.collect(playerEngine::setSubtitleStyle)
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerControlsTimeoutSeconds,
                preferencesRepository.playerLiveOverlayTimeoutSeconds,
                preferencesRepository.playerNoticeTimeoutSeconds,
                preferencesRepository.playerDiagnosticsTimeoutSeconds
            ) { controlsSeconds, liveOverlaySeconds, noticeSeconds, diagnosticsSeconds ->
                PlayerUiTimeouts(
                    controlsMs = controlsSeconds * 1000L,
                    liveOverlayMs = liveOverlaySeconds * 1000L,
                    noticeMs = noticeSeconds * 1000L,
                    diagnosticsMs = diagnosticsSeconds * 1000L
                )
            }.collect { timeouts ->
                playerControlsTimeoutMs = timeouts.controlsMs
                liveOverlayTimeoutMs = timeouts.liveOverlayMs
                playerNoticeTimeoutMs = timeouts.noticeMs
                diagnosticsTimeoutMs = timeouts.diagnosticsMs
            }
        }
        viewModelScope.launch {
            preferencesRepository.zapAutoRevert.collect { zapAutoRevertEnabled = it }
        }
        viewModelScope.launch {
            preferencesRepository.playerMediaSessionEnabled.collect(playerEngine::setMediaSessionEnabled)
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerTimeshiftEnabled,
                preferencesRepository.playerTimeshiftDepthMinutes
            ) { enabled, depthMinutes ->
                TimeshiftConfig(enabled = enabled, depthMinutes = depthMinutes)
            }.collect { config ->
                timeshiftConfig = config
                _timeshiftUiState.update { current ->
                    current.copy(
                        enabledForSession = config.enabled,
                        bufferDepthMs = config.depthMs
                    )
                }
                maybeStartLiveTimeshift()
            }
        }
        viewModelScope.launch {
            playerEngine.timeshiftState.collect(::applyTimeshiftState)
        }
        viewModelScope.launch {
            preferencesRepository.playerDecoderMode.collect { mode ->
                preferredDecoderMode = mode
                if (!hasRetriedWithSoftwareDecoder) {
                    playerEngine.setDecoderMode(mode)
                    updateDecoderMode(mode)
                }
            }
        }
        viewModelScope.launch {
            var consecutiveLowBandwidthSeconds = 0
            var noticeShown = false
            playerEngine.playerStats.collect { stats ->
                if (!playerEngine.isPlaying.value || currentContentType != ContentType.LIVE) {
                    consecutiveLowBandwidthSeconds = 0
                    noticeShown = false
                    return@collect
                }
                val bps = stats.bandwidthEstimate
                if (bps in 1 until LOW_BANDWIDTH_THRESHOLD_BPS) {
                    consecutiveLowBandwidthSeconds++
                } else {
                    consecutiveLowBandwidthSeconds = 0
                    noticeShown = false
                }
                if (consecutiveLowBandwidthSeconds >= LOW_BANDWIDTH_DURATION_SECONDS && !noticeShown) {
                    noticeShown = true
                    showPlayerNotice(
                        message = "Network speed is low \u2014 stream quality reduced",
                        recoveryType = PlayerRecoveryType.NETWORK,
                        durationMs = 10_000L
                    )
                }
            }
        }
    }

    private fun handlePlaybackError(error: PlayerError) {
        val requestVersion = prepareRequestVersion
        val playbackUrl = currentPlaybackIdentityUrl()
        recoveryJob?.cancel()
        if (error is PlayerError.DecoderError && !hasRetriedWithSoftwareDecoder) {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return
            hasRetriedWithSoftwareDecoder = true
            android.util.Log.w("PlayerVM", "Decoder error detected. Retrying with software decoder mode.")
            playerEngine.setDecoderMode(DecoderMode.SOFTWARE)
            updateDecoderMode(DecoderMode.SOFTWARE)
            setLastFailureReason(error.message)
            appendRecoveryAction("Switched to software decoder")
            playerEngine.play()
            showPlayerNotice(
                message = "Retrying with software decoding for this stream.",
                recoveryType = PlayerRecoveryType.DECODER,
                actions = buildRecoveryActions(PlayerRecoveryType.DECODER)
            )
            return
        }
        recoveryJob = viewModelScope.launch {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (tryRefreshXtreamPlaybackAfterAuthError(error, requestVersion, playbackUrl)) {
                return@launch
            }

            val recoveryType = classifyPlaybackError(error)
            val channel = currentChannelFlow.value?.sanitizedForPlayer()

            if (recoveryType == PlayerRecoveryType.DRM) {
                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                showPlayerNotice(
                    message = "This channel requires DRM support that is not available. " +
                        "Your subscription may not include this content.",
                    recoveryType = PlayerRecoveryType.DRM,
                    actions = buildRecoveryActions(PlayerRecoveryType.DRM)
                )
                return@launch
            }

            if (currentContentType != ContentType.LIVE || channel == null) {
                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
                return@launch
            }

            if (isCatchUpPlayback()) {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason(error.message)

                val switched = when (recoveryType) {
                    PlayerRecoveryType.NETWORK,
                    PlayerRecoveryType.SOURCE,
                    PlayerRecoveryType.BUFFER_TIMEOUT -> tryNextCatchUpVariantInternal()
                    else -> false
                }

                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                if (switched) {
                    appendRecoveryAction("Trying alternate catch-up URL")
                    showPlayerNotice(
                        message = "Trying another replay path for ${channel.name}.",
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP),
                        isRetryNotice = true
                    )
                    return@launch
                }

                showPlayerNotice(
                    message = resolveCatchUpFailureMessage(
                        channel = channel,
                        archiveRequested = true,
                        programHasArchive = _currentProgram.value?.hasArchive == true
                    ),
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            markStreamFailure(currentStreamUrl)
            setLastFailureReason(error.message)
            logRepositoryFailure(
                operation = "Increment channel error count",
                result = channelRepository.incrementChannelErrorCount(channel.id)
            )

            val switched = when (recoveryType) {
                PlayerRecoveryType.NETWORK,
                PlayerRecoveryType.SOURCE,
                PlayerRecoveryType.BUFFER_TIMEOUT -> tryAlternateStreamInternal(channel)
                else -> false
            }

            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (switched) {
                appendRecoveryAction("Trying alternate stream")
                showPlayerNotice(
                    message = "Trying an alternate stream for ${channel.name}.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType),
                    isRetryNotice = true
                )
                return@launch
            }

            if (fallbackToPreviousChannel("Recovery path exhausted for ${recoveryType.name.lowercase()}")) {
                appendRecoveryAction("Returned to last channel")
                showPlayerNotice(
                    message = "Playback failed on this stream. Returned to the last channel.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            } else {
                showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            }
        }
    }

    internal fun refreshCurrentChannelRecording(items: List<RecordingItem> = _recordingItems.value) {
        val channelId = currentChannelFlow.value?.id ?: -1L
        currentChannelFlowRecording.value = items.firstOrNull {
            it.providerId == currentProviderId &&
                it.channelId == channelId &&
                (it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED)
        }
    }

    private fun handleRecordingStateChanges(
        previousItems: List<RecordingItem>,
        newItems: List<RecordingItem>
    ) {
        val previousStatuses = previousItems.associateBy(RecordingItem::id)
        val failedNow = newItems.firstOrNull { item ->
            previousStatuses[item.id]?.status == RecordingStatus.RECORDING &&
                item.status == RecordingStatus.FAILED &&
                notifiedRecordingFailureIds.add(item.id)
        }

        notifiedRecordingFailureIds.retainAll(
            newItems.asSequence()
                .filter { it.status == RecordingStatus.FAILED }
                .map { it.id }
                .toSet()
        )

        failedNow?.let { item ->
            val title = item.programTitle?.takeIf { it.isNotBlank() } ?: item.channelName
            val detail = item.failureReason?.takeIf { it.isNotBlank() } ?: "The provider stopped serving the recording stream."
            showPlayerNotice(
                message = "Recording failed for $title. $detail",
                durationMs = maxOf(playerNoticeTimeoutMs, 8_000L)
            )
        }
    }

    private data class PlaybackProbeFailure(
        val message: String,
        val recoveryType: PlayerRecoveryType
    )
    
    val playerError: StateFlow<PlayerError?> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.error
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)
    }

    val videoFormat: StateFlow<VideoFormat> = playerEngine.videoFormat
    
    val playerStats = playerEngine.playerStats
    val availableAudioTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableAudioTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val availableSubtitleTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableSubtitleTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val availableVideoQualities: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        playerEngine.availableVideoTracks
            .map { tracks -> tracks as? List<com.streamvault.player.PlayerTrack> ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    val isMuted: StateFlow<Boolean> = playerEngine.isMuted
    val mediaTitle: StateFlow<String?> = playerEngine.mediaTitle
    val playbackSpeed: StateFlow<Float> = playerEngine.playbackSpeed

    val preventStandbyDuringPlayback: StateFlow<Boolean> by lazy(LazyThreadSafetyMode.NONE) {
        preferencesRepository.preventStandbyDuringPlayback
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), true)
    }

    fun selectAudioTrack(trackId: String) {
        playerEngine.selectAudioTrack(trackId)
    }

    fun selectSubtitleTrack(trackId: String?) {
        playerEngine.selectSubtitleTrack(trackId)
    }

    fun selectVideoQuality(trackId: String) {
        playerEngine.selectVideoTrack(trackId)
    }

    fun selectLiveVariant(rawChannelId: Long) {
        val currentChannel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
        val updatedChannel = currentChannel.withSelectedVariant(rawChannelId)?.sanitizedForPlayer() ?: return
        if (updatedChannel.selectedVariantId == currentChannel.selectedVariantId) return

        val requestVersion = beginPlaybackSession()
        triedAlternativeStreams.clear()
        currentContentId = updatedChannel.id
        currentStreamUrl = updatedChannel.streamUrl
        currentTitle = updatedChannel.currentVariant?.originalName ?: updatedChannel.name
        playbackTitleFlow.value = currentTitle
        currentChannelFlow.value = updatedChannel
        if (currentChannelIndex in channelList.indices) {
            channelList = channelList.mapIndexed { index, existing ->
                if (index == currentChannelIndex || existing.logicalGroupId == updatedChannel.logicalGroupId) {
                    updatedChannel
                } else {
                    existing
                }
            }
            currentChannelFlowList.value = channelList
        }
        if (currentChannelIndex >= 0) {
            displayChannelNumberFlow.value = resolveChannelNumber(updatedChannel, currentChannelIndex)
        }
        refreshCurrentChannelRecording()
        updateChannelDiagnostics(updatedChannel)
        updateStreamClass("Variant")
        viewModelScope.launch {
            preferencesRepository.setPreferredLiveVariant(
                providerId = updatedChannel.providerId,
                logicalGroupId = updatedChannel.logicalGroupId,
                rawChannelId = rawChannelId
            )
            val resolvedUrl = resolvePlaybackUrl(
                logicalUrl = updatedChannel.streamUrl,
                internalContentId = updatedChannel.id,
                providerId = updatedChannel.providerId,
                contentType = ContentType.LIVE
            ) ?: return@launch
            if (!isActivePlaybackSession(requestVersion, updatedChannel.streamUrl)) return@launch
            if (currentContentType == ContentType.LIVE) {
                recordLivePlayback(updatedChannel)
                requestEpg(
                    providerId = updatedChannel.providerId,
                    epgChannelId = updatedChannel.epgChannelId,
                    streamId = updatedChannel.streamId,
                    internalChannelId = updatedChannel.id
                )
            }
            val streamInfo = StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
    }

    fun recordLiveVariantObservation(playbackState: PlaybackState, videoFormat: VideoFormat) {
        if (currentContentType != ContentType.LIVE || playbackState != PlaybackState.READY || videoFormat.isEmpty) {
            return
        }
        val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
        val rawChannelId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id
        if (rawChannelId <= 0L) return
        val signature = buildString {
            append(rawChannelId)
            append('|')
            append(videoFormat.width)
            append('|')
            append(videoFormat.height)
            append('|')
            append(videoFormat.bitrate)
            append('|')
            append(videoFormat.frameRate)
        }
        if (signature == lastRecordedVariantObservationSignature) return
        lastRecordedVariantObservationSignature = signature

        viewModelScope.launch {
            val existing = preferencesRepository.liveVariantObservations.first()[rawChannelId]
            preferencesRepository.recordLiveVariantObservation(
                rawChannelId = rawChannelId,
                observedQuality = LiveChannelObservedQuality(
                    lastObservedWidth = videoFormat.width,
                    lastObservedHeight = videoFormat.height,
                    lastObservedBitrate = videoFormat.bitrate,
                    lastObservedFrameRate = videoFormat.frameRate,
                    successCount = (existing?.successCount ?: 0) + 1,
                    lastSuccessfulAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val normalizedSpeed = speed.coerceIn(0.5f, 2f)
        playerEngine.setPlaybackSpeed(normalizedSpeed)
        viewModelScope.launch {
            preferencesRepository.setPlayerPlaybackSpeed(normalizedSpeed)
        }
    }

    fun seekTo(positionMs: Long) {
        playerEngine.seekTo(positionMs)
        clearSeekPreview()
    }

    fun setScrubbingMode(enabled: Boolean) {
        playerEngine.setScrubbingMode(enabled)
        if (!enabled) {
            clearSeekPreview()
        }
    }

    fun updateSeekPreview(positionMs: Long?) {
        if (positionMs == null || currentContentType == ContentType.LIVE) {
            clearSeekPreview()
            return
        }

        val previewPositionMs = positionMs.coerceAtLeast(0L)
        val previewUrl = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }
        val canExtractFrame = previewUrl.isNotBlank() && seekThumbnailProvider.supportsFrameExtraction(previewUrl)

        _seekPreview.update { current ->
            current.copy(
                visible = true,
                positionMs = previewPositionMs,
                artworkUrl = currentArtworkUrl,
                title = currentTitle,
                isLoading = canExtractFrame,
                frameBitmap = if (canExtractFrame) current.frameBitmap else null
            )
        }

        seekPreviewJob?.cancel()
        if (!canExtractFrame) {
            return
        }

        val requestVersion = ++seekPreviewRequestVersion
        seekPreviewJob = viewModelScope.launch {
            delay(120)
            val bitmap = seekThumbnailProvider.loadFrame(previewUrl, previewPositionMs)
            if (requestVersion != seekPreviewRequestVersion) return@launch

            _seekPreview.update { current ->
                if (!current.visible || current.positionMs != previewPositionMs) {
                    current
                } else {
                    current.copy(
                        frameBitmap = bitmap,
                        artworkUrl = currentArtworkUrl,
                        title = currentTitle,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun startThumbnailPreload() {
        thumbnailPreloadJob?.cancel()
        val url = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }
        if (url.isBlank() || !seekThumbnailProvider.supportsFrameExtraction(url)) return
        val durationMs = playerEngine.duration.value
        if (durationMs <= 0L) return
        thumbnailPreloadJob = viewModelScope.launch(Dispatchers.Default) {
            val bucketMs = 10_000L
            var pos = 0L
            while (pos <= durationMs) {
                ensureActive()
                seekThumbnailProvider.loadFrame(url, pos)
                pos += bucketMs
            }
        }
    }

    internal fun beginPlaybackSession(): Long {
        recoveryJob?.cancel()
        thumbnailPreloadJob?.cancel()
        hasRetriedXtreamAuthRefresh = false
        lastRecordedVariantObservationSignature = null
        return ++prepareRequestVersion
    }

    private fun clearSeriesEpisodeContext() {
        currentSeriesId = null
        currentSeasonNumber = null
        currentEpisodeNumber = null
        _currentSeries.value = null
        _currentEpisode.value = null
        _nextEpisode.value = null
    }

    private fun resolveEpisode(
        series: Series,
        episodeId: Long,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Episode? {
        val episodes = series.seasons
            .sanitizedForPlayer()
            .sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        return episodes.firstOrNull { it.id == episodeId }
            ?: episodes.firstOrNull { it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
    }

    private fun findNextEpisode(series: Series, episode: Episode): Episode? {
        val orderedEpisodes = series.seasons
            .sanitizedForPlayer()
            .sortedBy { it.seasonNumber }
            .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        val currentIndex = orderedEpisodes.indexOfFirst { it.id == episode.id }
        return orderedEpisodes.getOrNull(currentIndex + 1)
    }

    private fun buildEpisodePlaybackTitle(episode: Episode): String =
        "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}"

    private fun loadSeriesEpisodeContext(
        requestVersion: Long,
        providerId: Long,
        seriesId: Long,
        episodeId: Long,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) {
        viewModelScope.launch {
            when (val result = seriesRepository.getSeriesDetails(providerId, seriesId)) {
                is Result.Success -> {
                    if (!isActivePlaybackSession(requestVersion)) return@launch
                    val series = result.data.sanitizedForPlayer()
                    val resolvedEpisode = resolveEpisode(series, episodeId, seasonNumber, episodeNumber)
                    _currentSeries.value = series
                    _currentEpisode.value = resolvedEpisode
                    _nextEpisode.value = resolvedEpisode?.let { findNextEpisode(series, it) }
                    currentSeriesId = seriesId
                    currentSeasonNumber = resolvedEpisode?.seasonNumber ?: seasonNumber
                    currentEpisodeNumber = resolvedEpisode?.episodeNumber ?: episodeNumber
                    if (resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
                        currentArtworkUrl = resolvedEpisode.coverUrl
                            ?: currentArtworkUrl
                            ?: series.posterUrl
                            ?: series.backdropUrl
                        currentTitle = buildEpisodePlaybackTitle(resolvedEpisode)
                        playbackTitleFlow.value = currentTitle
                    }
                }

                else -> {
                    if (!isActivePlaybackSession(requestVersion)) return@launch
                    _currentSeries.value = null
                    _currentEpisode.value = null
                    _nextEpisode.value = null
                    currentSeriesId = seriesId
                    currentSeasonNumber = seasonNumber
                    currentEpisodeNumber = episodeNumber
                }
            }
        }
    }

    private fun buildPlaybackHistorySnapshot(
        positionMs: Long,
        durationMs: Long
    ): PlaybackHistory? {
        if (positionMs < 0 || durationMs <= 0 || currentContentId == -1L || currentProviderId == -1L) {
            return null
        }
        return PlaybackHistory(
            contentId = currentContentId,
            contentType = currentContentType,
            providerId = currentProviderId,
            title = currentTitle,
            posterUrl = currentArtworkUrl,
            streamUrl = currentStreamUrl,
            resumePositionMs = positionMs,
            totalDurationMs = durationMs,
            lastWatchedAt = System.currentTimeMillis(),
            seriesId = currentSeriesId,
            seasonNumber = _currentEpisode.value?.seasonNumber ?: currentSeasonNumber,
            episodeNumber = _currentEpisode.value?.episodeNumber ?: currentEpisodeNumber
        )
    }

    private suspend fun persistPlaybackCompletion() {
        val durationMs = playerEngine.duration.value
        val completedHistory = buildPlaybackHistorySnapshot(
            positionMs = durationMs.coerceAtLeast(playerEngine.currentPosition.value),
            durationMs = durationMs
        ) ?: return
        logRepositoryFailure(
            operation = "Mark playback watched",
            result = markAsWatched(completedHistory)
        )
        watchNextManager.refreshWatchNext()
        launcherRecommendationsManager.refreshRecommendations()
    }

    private fun handlePlaybackEnded() {
        if (currentContentType == ContentType.LIVE) return
        viewModelScope.launch {
            persistPlaybackCompletion()
            if (currentContentType == ContentType.SERIES_EPISODE) {
                val position = playerEngine.currentPosition.value
                val duration = playerEngine.duration.value
                if (position > MIN_WATCHED_FOR_AUTO_PLAY_MS || duration > 0L) {
                    _nextEpisode.value?.let { episode ->
                        playEpisode(episode, showResumePrompt = false)
                    }
                }
            }
        }
    }

    private fun currentPlaybackIdentityUrl(): String =
        currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }

    internal fun isActivePlaybackSession(
        requestVersion: Long,
        expectedLogicalUrl: String? = null
    ): Boolean {
        if (requestVersion != prepareRequestVersion) return false
        val expectedUrl = expectedLogicalUrl?.takeIf { it.isNotBlank() } ?: return true
        val activeUrl = currentPlaybackIdentityUrl()
        return activeUrl.isBlank() || activeUrl == expectedUrl || currentStreamUrl == expectedUrl
    }

    internal fun requestEpg(
        providerId: Long,
        epgChannelId: String?,
        streamId: Long = 0L,
        internalChannelId: Long = 0L
    ) {
        val normalizedChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        if (providerId <= 0L || (internalChannelId <= 0L && normalizedChannelId == null && streamId <= 0L)) {
            activeEpgRequestKey = null
            fetchEpg(providerId = -1L, internalChannelId = 0L, epgChannelId = null)
            return
        }

        val key = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = normalizedChannelId,
            streamId = streamId.takeIf { it > 0L } ?: 0L
        )
        if (key == activeEpgRequestKey) return
        activeEpgRequestKey = key
        fetchEpg(
            providerId = key.providerId,
            internalChannelId = key.internalChannelId,
            epgChannelId = key.epgChannelId,
            streamId = key.streamId
        )
    }

    internal suspend fun preparePlayer(
        streamInfo: com.streamvault.domain.model.StreamInfo,
        requestVersion: Long
    ): Boolean {
        if (!isActivePlaybackSession(requestVersion)) return false

        // Fast-path expiry check: if the stream URL already carries an expiration timestamp
        // that is in the past, skip the network probe entirely and surface a clear message.
        val expiry = streamInfo.expirationTime
        if (expiry != null && expiry > 0L && expiry < System.currentTimeMillis()) {
            if (!isActivePlaybackSession(requestVersion)) return false
            val expiryMessage = "This stream's subscription has expired. " +
                "Please renew your subscription with the provider."
            setLastFailureReason(expiryMessage)
            showPlayerNotice(
                message = expiryMessage,
                recoveryType = PlayerRecoveryType.SOURCE,
                actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
            )
            return false
        }

        probePlaybackUrl(streamInfo.url)?.let { failure ->
            if (!isActivePlaybackSession(requestVersion)) return false
            setLastFailureReason(failure.message)
            showPlayerNotice(
                message = failure.message,
                recoveryType = failure.recoveryType,
                actions = buildRecoveryActions(failure.recoveryType)
            )
            return false
        }
        currentProviderId.takeIf { it > 0L }?.let { probePassedProviderIds.add(it) }
        playerEngine.setMuted(preferencesRepository.playerMuted.first())
        playerEngine.setPlaybackSpeed(
            if (currentContentType == ContentType.LIVE) {
                1f
            } else {
                preferencesRepository.playerPlaybackSpeed.first()
            }
        )
        playerEngine.setPreferredAudioLanguage(
            resolvePreferredAudioLanguage(
                preferredAudioLanguage = preferencesRepository.preferredAudioLanguage.first(),
                appLanguage = preferencesRepository.appLanguage.first()
            )
        )
        playerEngine.setNetworkQualityPreferences(
            wifiMaxHeight = preferencesRepository.playerWifiMaxVideoHeight.first(),
            ethernetMaxHeight = preferencesRepository.playerEthernetMaxVideoHeight.first()
        )
        if (!isActivePlaybackSession(requestVersion)) return false
        currentResolvedPlaybackUrl = streamInfo.url
        playerEngine.prepare(streamInfo)
        startTokenRenewalMonitoring(streamInfo.expirationTime)
        return true
    }

    private suspend fun probePlaybackUrl(url: String): PlaybackProbeFailure? {
        if (!shouldProbePlaybackUrl(url)) return null

        return runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Range", "bytes=0-0")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        401, 403 -> PlaybackProbeFailure(
                            message = "This provider stream was rejected (${response.code} Unauthorized/Forbidden).",
                            recoveryType = PlayerRecoveryType.SOURCE
                        )
                        404 -> PlaybackProbeFailure(
                            message = "This provider stream is unavailable right now (404).",
                            recoveryType = PlayerRecoveryType.SOURCE
                        )
                        in 500..599 -> PlaybackProbeFailure(
                            message = "The provider returned a server error for this stream (${response.code}).",
                            recoveryType = PlayerRecoveryType.NETWORK
                        )
                        else -> null
                    }
                }
            }
        }.getOrNull()
    }

    private suspend fun shouldProbePlaybackUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val providerId = currentProviderId.takeIf { it > 0L } ?: return false
        if (providerId in probePassedProviderIds) return false
        val provider = providerRepository.getProvider(providerId) ?: return false
        return (
            provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES ||
                provider.type == com.streamvault.domain.model.ProviderType.STALKER_PORTAL
            ) &&
            (xtreamStreamUrlResolver.isInternalStreamUrl(currentStreamUrl) || xtreamStreamUrlResolver.isInternalStreamUrl(url))
    }

    fun prepare(
        streamUrl: String, 
        epgChannelId: String?, 
        internalChannelId: Long, 
        categoryId: Long = -1, 
        providerId: Long = -1, 
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        contentType: String = "CHANNEL",
        title: String = "",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        showResumePrompt: Boolean = true
    ) {
        val hasArchiveRequest = archiveStartMs != null &&
            archiveEndMs != null &&
            archiveStartMs > 0L &&
            archiveEndMs > archiveStartMs &&
            try { ContentType.valueOf(contentType) } catch (e: Exception) { ContentType.LIVE } == ContentType.LIVE
        val requestVersion = beginPlaybackSession()
        val previousProviderId = currentProviderId
        val previousCategoryId = currentCategoryId
        val previousCombinedProfileId = currentCombinedProfileId
        val previousCombinedSourceFilterProviderId = currentCombinedSourceFilterProviderId
        val shouldReloadPlaylist = categoryId != -1L &&
            (
                categoryId != previousCategoryId ||
                    providerId != previousProviderId ||
                    combinedProfileId != previousCombinedProfileId ||
                    combinedSourceFilterProviderId != previousCombinedSourceFilterProviderId
                )
        clearSeekPreview()
        currentResolvedPlaybackUrl = ""
        currentStreamUrl = streamUrl
        currentContentId = internalChannelId
        currentTitle = title
        playbackTitleFlow.value = title
        currentArtworkUrl = artworkUrl
        currentContentType = try { ContentType.valueOf(contentType) } catch (e: Exception) { ContentType.LIVE }
        currentProviderId = providerId
        currentCombinedProfileId = combinedProfileId?.takeIf { it > 0L }
        currentCombinedSourceFilterProviderId = combinedSourceFilterProviderId?.takeIf { it > 0L }
        currentSeriesId = seriesId?.takeIf { it > 0L }
        currentSeasonNumber = seasonNumber
        currentEpisodeNumber = episodeNumber
        currentStreamClassLabel = if (hasArchiveRequest) "Catch-up" else "Primary"
        if (currentContentType == ContentType.LIVE && currentCombinedProfileId != null) {
            val activeCombinedProfileId = currentCombinedProfileId
            viewModelScope.launch {
                val members = activeCombinedProfileId?.let { combinedM3uRepository.getProfile(it)?.members }.orEmpty()
                if (currentCombinedProfileId == activeCombinedProfileId) {
                    currentCombinedProfileMembers = members
                }
            }
        } else {
            currentCombinedProfileMembers = emptyList()
            combinedCategoriesById = emptyMap()
        }
        if (!hasArchiveRequest) {
            pendingCatchUpUrls = emptyList()
        }
        if (currentContentType == ContentType.SERIES_EPISODE && providerId > 0 && currentSeriesId != null) {
            loadSeriesEpisodeContext(
                requestVersion = requestVersion,
                providerId = providerId,
                seriesId = currentSeriesId ?: -1L,
                episodeId = internalChannelId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )
        } else {
            clearSeriesEpisodeContext()
        }
        if (currentContentType != ContentType.LIVE) {
            lastRecordedLivePlaybackKey = null
            recentChannelsJob?.cancel()
            recentChannelsFlow.value = emptyList()
            lastVisitedCategoryJob?.cancel()
            _lastVisitedCategory.value = null
            playerEngine.stopLiveTimeshift()
        }
        hasRetriedWithSoftwareDecoder = false
        playerEngine.setDecoderMode(preferredDecoderMode)
        updateDecoderMode(preferredDecoderMode)
        updateStreamClass(currentStreamClassLabel)
        
        // Reset tried streams for manual switch
        triedAlternativeStreams.clear()
        if (!hasArchiveRequest) {
            triedAlternativeStreams.add(streamUrl)
        }

        if (!hasArchiveRequest) {
            viewModelScope.launch {
                val streamInfo = resolvePlaybackStreamInfo(streamUrl, internalChannelId, providerId, currentContentType)
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (streamInfo == null) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    showPlayerNotice(message = "No playable stream URL was available.", recoveryType = PlayerRecoveryType.SOURCE)
                    return@launch
                }
                if (!preparePlayer(streamInfo, requestVersion)) return@launch
                maybeStartLiveTimeshift(streamInfo)
            }
        }
        
        // Show context info on entry for both Live and VOD
        openChannelInfoOverlay()

        if (providerId > 0) {
            viewModelScope.launch {
                providerRepository.getProvider(providerId)?.let { provider ->
                    _playerDiagnostics.update {
                        it.copy(
                            providerName = provider.name,
                            providerSourceLabel = when (provider.type) {
                                com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                                com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                                com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
                            }
                        )
                    }
                }
            }
        } else {
            _playerDiagnostics.update { it.copy(providerName = "", providerSourceLabel = "") }
        }
        
        // 1. Check for Resume Position for VODs
        if (showResumePrompt && currentContentType != ContentType.LIVE && currentContentId != -1L && currentProviderId != -1L) {
            viewModelScope.launch {
                val history = playbackHistoryRepository.getPlaybackHistory(currentContentId, currentContentType, currentProviderId)
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (history != null && history.resumePositionMs > 5000L && !isPlaybackComplete(history.resumePositionMs, history.totalDurationMs)) {
                    playerEngine.pause()
                    _resumePrompt.value = ResumePromptState(
                        show = true,
                        positionMs = history.resumePositionMs,
                        title = currentTitle
                    )
                }
            }
        }
        
        // Load playlist if context changed
        if (shouldReloadPlaylist) {
            currentCategoryId = categoryId
            activeCategoryIdFlow.value = categoryId
            isVirtualCategory = isVirtual
            loadPlaylist(categoryId, providerId, isVirtual, internalChannelId)
        } else {
            // If playlist already loaded, just update index
            if (channelList.isNotEmpty() && internalChannelId != -1L) {
                currentChannelIndex = channelList.indexOfFirst { it.id == internalChannelId }
                if (currentChannelIndex == -1) {
                    currentChannelIndex = channelList.indexOfFirst { it.streamUrl == streamUrl }
                }
            }
        }

        if (currentContentType == ContentType.LIVE && hasArchiveRequest) {
            playerEngine.stopLiveTimeshift()
            viewModelScope.launch {
                val catchUpUrls = try {
                    providerRepository.buildCatchUpUrls(
                        providerId = currentProviderId,
                        streamId = currentContentId,
                        start = archiveStartMs / 1000L,
                        end = archiveEndMs / 1000L
                    )
                } catch (e: CredentialDecryptionException) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    setLastFailureReason(e.message ?: CredentialDecryptionException.MESSAGE)
                    showPlayerNotice(
                        message = e.message ?: CredentialDecryptionException.MESSAGE,
                        recoveryType = PlayerRecoveryType.SOURCE
                    )
                    return@launch
                }
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (catchUpUrls.isNotEmpty()) {
                    startCatchUpPlayback(
                        urls = catchUpUrls,
                        title = archiveTitle?.takeIf { it.isNotBlank() } ?: currentTitle,
                        recoveryAction = "Opened catch-up stream",
                        requestVersionOverride = requestVersion
                    )
                } else {
                    val reason = resolveCatchUpFailureMessage(currentChannelFlow.value, archiveRequested = true, programHasArchive = true)
                    setLastFailureReason(reason)
                    showPlayerNotice(
                        message = reason,
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                    )
                }
            }
        }

        // Fetch EPG if ID provided
        if (currentContentType == ContentType.LIVE) {
            requestEpg(
                providerId = currentProviderId,
                epgChannelId = epgChannelId,
                internalChannelId = internalChannelId
            )
        } else {
            requestEpg(providerId = -1L, epgChannelId = null)
        }
        observeRecentChannels()
        observeLastVisitedCategory()

        // Load Aspect Ratio safely (fallback to FIT if none saved)
        aspectRatioJob?.cancel()
        _aspectRatio.value = AspectRatio.FIT
        if (internalChannelId != -1L) {
            aspectRatioJob = viewModelScope.launch {
                preferencesRepository.getAspectRatioForChannel(internalChannelId).collect { savedRatio ->
                    _aspectRatio.value = try {
                        savedRatio?.let { AspectRatio.valueOf(it) } ?: AspectRatio.FIT
                    } catch (e: Exception) {
                        AspectRatio.FIT
                    }
                }
            }
            
            // Fetch Channel for tracking alternative streams (video qualities)
            viewModelScope.launch {
                val channel = channelRepository.getChannel(internalChannelId)
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                currentChannelFlow.value = channel
                refreshCurrentChannelRecording()
                if (channel != null) {
                    currentTitle = channel.name.ifBlank { currentTitle }
                    playbackTitleFlow.value = currentTitle
                    currentStreamUrl = if (currentStreamClassLabel == "Catch-up") currentStreamUrl else channel.streamUrl
                    updateStreamClass(
                        when {
                            currentStreamClassLabel == "Catch-up" -> "Catch-up"
                            streamUrl == channel.streamUrl -> "Primary"
                            channel.alternativeStreams.contains(streamUrl) -> "Alternate"
                            else -> "Direct"
                        }
                    )
                    if (currentContentType == ContentType.LIVE) {
                        recordLivePlayback(channel)
                        requestEpg(
                            providerId = currentProviderId,
                            epgChannelId = channel.epgChannelId,
                            streamId = channel.streamId,
                            internalChannelId = channel.id
                        )
                    }
                    updateChannelDiagnostics(channel)
                }
            }
        }

        // 2. Start Progress Tracking for VODs
        startProgressTracking()
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        if (currentContentType == ContentType.LIVE) return

        progressTrackingJob = viewModelScope.launch {
            while (true) {
                delay(5000) // Track every 5 seconds
                if (!isAppInForeground || !playerEngine.isPlaying.value) continue
                persistPlaybackProgress()
            }
        }
    }

    private suspend fun persistPlaybackProgress() {
        val pos = playerEngine.currentPosition.value
        val dur = playerEngine.duration.value

        if (pos > 0 && dur > 0) {
            val history = buildPlaybackHistorySnapshot(pos, dur) ?: return
            logRepositoryFailure(
                operation = "Persist playback resume position",
                result = playbackHistoryRepository.updateResumePosition(history)
            )
            watchNextManager.refreshWatchNext()
            launcherRecommendationsManager.refreshRecommendations()
        }
    }

    private fun startTokenRenewalMonitoring(expirationTime: Long?) {
        tokenRenewalJob?.cancel()
        tokenRenewalJob = null
        val expiry = expirationTime?.takeIf { it > 0L } ?: return
        val requestVersion = prepareRequestVersion
        tokenRenewalJob = viewModelScope.launch {
            while (true) {
                delay(TOKEN_RENEWAL_CHECK_INTERVAL_MS)
                if (!playerEngine.isPlaying.value) continue
                val remaining = expiry - System.currentTimeMillis()
                if (remaining > TOKEN_RENEWAL_LEAD_MS) continue
                if (!isActivePlaybackSession(requestVersion)) return@launch
                val refreshed = resolvePlaybackStreamInfo(
                    logicalUrl = currentStreamUrl,
                    internalContentId = currentContentId,
                    providerId = currentProviderId,
                    contentType = currentContentType
                ) ?: return@launch
                if (!isActivePlaybackSession(requestVersion)) return@launch
                currentResolvedPlaybackUrl = refreshed.url
                playerEngine.renewStreamUrl(refreshed)
                startTokenRenewalMonitoring(refreshed.expirationTime)
                return@launch
            }
        }
    }

    fun onAppBackgrounded() {
        if (!isAppInForeground) return
        isAppInForeground = false
        shouldResumeAfterForeground = playerEngine.isPlaying.value
        if (shouldResumeAfterForeground) {
            playerEngine.pause()
        }
        if (currentContentType != ContentType.LIVE) {
            viewModelScope.launch { persistPlaybackProgress() }
        }
    }

    fun onAppForegrounded() {
        if (isAppInForeground) return
        isAppInForeground = true
        if (shouldResumeAfterForeground && !_resumePrompt.value.show) {
            playerEngine.play()
        }
        shouldResumeAfterForeground = false
    }

    fun onPlayerScreenDisposed() {
        if (currentContentType != ContentType.LIVE) {
            viewModelScope.launch { persistPlaybackProgress() }
        }
        playerEngine.stopLiveTimeshift()
    }

    fun handOffPlaybackToMultiView() {
        if (currentContentType != ContentType.LIVE) {
            viewModelScope.launch { persistPlaybackProgress() }
        }
        playerEngine.stopLiveTimeshift()
        playerEngine.release()
    }

    private fun fetchEpg(
        providerId: Long,
        internalChannelId: Long,
        epgChannelId: String?,
        streamId: Long = 0L
    ) {
        epgJob?.cancel()
        if (providerId <= 0L || (internalChannelId <= 0L && epgChannelId == null && streamId <= 0L)) {
            clearEpgState()
            return
        }

        val requestKey = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = epgChannelId,
            streamId = streamId
        )

        epgJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val start = now - (24 * 60 * 60 * 1000L)
                val end = now + (6 * 60 * 60 * 1000L)
                val programs = epgRepository.getResolvedProgramsForPlaybackChannel(
                    providerId = providerId,
                    internalChannelId = internalChannelId,
                    epgChannelId = epgChannelId,
                    streamId = streamId,
                    startTime = start,
                    endTime = end
                )

                if (activeEpgRequestKey != requestKey) return@launch

                if (programs.isNotEmpty()) {
                    applyProgramTimeline(programs, now)
                } else {
                    applyRemoteProgramFallback(providerId, epgChannelId, streamId, now)
                    if (activeEpgRequestKey != requestKey) return@launch
                }

                delay(PLAYER_EPG_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun applyProgramTimeline(programs: List<Program>, now: Long) {
        val catchUpSupported = currentChannelFlow.value?.catchUpSupported == true
        val sortedPrograms = programs.sortedBy { it.startTime }
        val current = sortedPrograms.firstOrNull { it.startTime <= now && it.endTime > now }
        _currentProgram.value = current
        _nextProgram.value = sortedPrograms.firstOrNull { it.startTime > now }
        _programHistory.value = sortedPrograms
            .filter { it.endTime <= now && (it.hasArchive || catchUpSupported) }
            .sortedByDescending { it.startTime }
            .take(MAX_PROGRAM_HISTORY_ITEMS)
        _upcomingPrograms.value = sortedPrograms
            .filter { it.endTime > now || it == current }
            .sortedBy { it.startTime }
            .take(MAX_UPCOMING_PROGRAM_ITEMS)
    }

    private suspend fun applyRemoteProgramFallback(
        providerId: Long,
        epgChannelId: String?,
        streamId: Long,
        now: Long
    ) {
        if (streamId <= 0L) {
            clearEpgState()
            return
        }

        val result = providerRepository.getProgramsForLiveStream(
            providerId = providerId,
            streamId = streamId,
            epgChannelId = epgChannelId,
            limit = 12
        )
        val programs = (result as? com.streamvault.domain.model.Result.Success)?.data
            ?.sortedBy { it.startTime }
            .orEmpty()

        if (programs.isEmpty()) {
            clearEpgState()
            return
        }

        applyProgramTimeline(programs, now)
    }

    private fun clearEpgState() {
        _currentProgram.value = null
        _nextProgram.value = null
        _programHistory.value = emptyList()
        _upcomingPrograms.value = emptyList()
    }

    internal fun loadPlaylist(categoryId: Long, providerId: Long, isVirtual: Boolean, initialChannelId: Long) {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            val flows = currentCombinedProfileId?.let { profileId ->
                observeCombinedLivePlaylist(profileId, categoryId)
            } ?: if (isVirtual) {
                if (categoryId == VirtualCategoryIds.RECENT) {
                    playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 24)
                        .map { history ->
                            history.asSequence()
                                .filter { it.contentType == ContentType.LIVE }
                                .sortedByDescending { it.lastWatchedAt }
                                .distinctBy { it.contentId }
                                .map { it.contentId }
                                .toList()
                        }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                unsorted.orderedByRequestedRawIds(ids)
                            }
                        }
                } else if (categoryId == VirtualCategoryIds.FAVORITES) {
                    // Global Favorites — preserve user-defined position order
                    favoriteRepository.getFavorites(currentProviderId, com.streamvault.domain.model.ContentType.LIVE)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                unsorted.orderedByRequestedRawIds(ids)
                            }
                        }
                } else {
                    val groupId = if (categoryId < 0) -categoryId else categoryId
                    favoriteRepository.getFavoritesByGroup(groupId)
                        .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else channelRepository.getChannelsByIds(ids).map { unsorted ->
                                unsorted.orderedByRequestedRawIds(ids)
                            }
                        }
                }
            } else {
                channelRepository.getChannelsByNumber(providerId, categoryId)
            }
            
            combine(flows, preferencesRepository.liveChannelNumberingMode) { channels, numberingMode ->
                val displayedChannels = when (numberingMode) {
                    ChannelNumberingMode.GROUP -> channels.mapIndexed { index, channel ->
                        channel.copy(number = index + 1)
                    }
                    ChannelNumberingMode.PROVIDER -> channels
                    ChannelNumberingMode.HIDDEN -> channels.map { it.copy(number = 0) }
                }
                numberingMode to displayedChannels.sanitizedChannelsForPlayer()
            }.collect { (numberingMode, displayedChannels) ->
                channelNumberingMode = numberingMode
                channelList = displayedChannels
                currentChannelFlowList.value = displayedChannels
                // Recalculate index based on current content ID (keeps overlay correct after zap/auto-revert)
                val targetId = if (currentContentId != -1L) currentContentId else initialChannelId
                if (targetId != -1L) {
                    currentChannelIndex = channelList.indexOfFirst { it.id == targetId }
                }
                if (currentChannelIndex == -1) {
                    currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
                }
                
                if (currentChannelIndex != -1) {
                currentChannelFlow.value = channelList[currentChannelIndex].sanitizedForPlayer()
                    refreshCurrentChannelRecording()
                    val ch = channelList[currentChannelIndex]
                    displayChannelNumberFlow.value = resolveChannelNumber(ch, currentChannelIndex)
                } else if (displayedChannels.isNotEmpty() && currentContentId != -1L) {
                    // Channel was removed from the provider playlist
                    showPlayerNotice(
                        message = "Channel not found in current playlist",
                        recoveryType = PlayerRecoveryType.SOURCE,
                        actions = listOf(PlayerNoticeAction.OPEN_GUIDE)
                    )
                }
            }
        }
    }
    
    // Store current URL to find index later
    internal var currentStreamUrl: String = ""

    private fun observeCombinedLivePlaylist(profileId: Long, categoryId: Long): Flow<List<com.streamvault.domain.model.Channel>> = when {
        categoryId == ChannelRepository.ALL_CHANNELS_ID -> {
            combinedM3uRepository.getCombinedCategories(profileId).flatMapLatest { combinedCategories ->
                combinedCategoriesById = combinedCategories.associateBy { it.category.id }
                val flows = combinedCategories.map { combinedM3uRepository.getCombinedChannels(profileId, it) }
                if (flows.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(flows) { arrays ->
                        arrays.toList().flatMap { it }
                    }.map(::applyCombinedSourceProviderFilter)
                }
            }
        }

        categoryId == VirtualCategoryIds.RECENT -> {
            combinedProviderIdsFlow(profileId)
                .flatMapLatest { providerIds -> observeRecentLiveIds(effectiveCombinedProviderIds(providerIds), 24) }
                .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
        }

        categoryId == VirtualCategoryIds.FAVORITES -> {
            combinedProviderIdsFlow(profileId)
                .flatMapLatest { providerIds -> observeLiveFavorites(effectiveCombinedProviderIds(providerIds)) }
                .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
        }

        categoryId < 0L -> {
            favoriteRepository.getFavoritesByGroup(-categoryId)
                .map { favorites ->
                    favorites
                        .sortedBy { it.position }
                        .let { groupFavorites ->
                            currentCombinedSourceFilterProviderId?.let { selectedProviderId ->
                                groupFavorites.filter { it.providerId == selectedProviderId }
                            } ?: groupFavorites
                        }
                        .map { it.contentId }
                }
                .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
        }

        else -> {
            combinedM3uRepository.getCombinedCategories(profileId).flatMapLatest { combinedCategories ->
                combinedCategoriesById = combinedCategories.associateBy { it.category.id }
                val combinedCategory = combinedCategoriesById[categoryId]
                if (combinedCategory == null) {
                    flowOf(emptyList())
                } else {
                    combinedM3uRepository.getCombinedChannels(profileId, combinedCategory)
                        .map(::applyCombinedSourceProviderFilter)
                }
            }
        }
    }

    private fun applyCombinedSourceProviderFilter(channels: List<com.streamvault.domain.model.Channel>): List<com.streamvault.domain.model.Channel> {
        val selectedProviderId = currentCombinedSourceFilterProviderId ?: return channels
        return channels.filter { it.providerId == selectedProviderId }
    }

    private fun loadLiveChannelsByOrderedIds(ids: List<Long>, providerId: Long? = null): Flow<List<com.streamvault.domain.model.Channel>> =
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            channelRepository.getChannelsByIds(ids).map { unsorted ->
                val filtered = providerId?.let { requiredProviderId ->
                    unsorted.filter { it.providerId == requiredProviderId }
                } ?: unsorted
                filtered.orderedByRequestedRawIds(ids)
            }
        }

    private fun combinedProviderIdsFlow(profileId: Long): Flow<List<Long>> = flow {
        emit(combinedM3uRepository.getProfile(profileId)?.members.orEmpty())
    }.map { members ->
        currentCombinedProfileMembers = members
        members.filter { it.enabled }.map { it.providerId }
    }

    private fun effectiveCombinedProviderIds(providerIds: List<Long>): List<Long> =
        currentCombinedSourceFilterProviderId?.let { selectedProviderId ->
            providerIds.filter { it == selectedProviderId }
        } ?: providerIds

    private fun observeLiveFavorites(providerIds: List<Long>): Flow<List<Favorite>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> favoriteRepository.getFavorites(providerIds.first(), ContentType.LIVE)
        else -> favoriteRepository.getFavorites(providerIds, ContentType.LIVE)
    }

    private fun observeRecentLiveIds(providerIds: List<Long>, limit: Int): Flow<List<Long>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> playbackHistoryRepository.getRecentlyWatchedByProvider(providerIds.first(), limit)
            .map { history ->
                history.asSequence()
                    .filter { it.contentType == ContentType.LIVE }
                    .sortedByDescending { it.lastWatchedAt }
                    .distinctBy { it.contentId }
                    .map { it.contentId }
                    .take(limit)
                    .toList()
            }
        else -> combine(providerIds.map { providerId ->
            playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit)
        }) { histories ->
            histories.toList()
                .flatMap { it }
                .asSequence()
                .filter { it.contentType == ContentType.LIVE }
                .sortedByDescending { it.lastWatchedAt }
                .distinctBy { it.providerId to it.contentId }
                .map { it.contentId }
                .take(limit)
                .toList()
        }
    }

    private fun observeRecentChannels() {
        recentChannelsJob?.cancel()
        if (currentContentType != ContentType.LIVE || (currentProviderId <= 0 && currentCombinedProfileId == null)) {
            recentChannelsFlow.value = emptyList()
            return
        }

        recentChannelsJob = viewModelScope.launch {
            val recentFlow = currentCombinedProfileId?.let { profileId ->
                combinedProviderIdsFlow(profileId)
                    .flatMapLatest { providerIds -> observeRecentLiveIds(effectiveCombinedProviderIds(providerIds), 12) }
                    .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
            } ?: playbackHistoryRepository.getRecentlyWatchedByProvider(currentProviderId, limit = 12)
                .map { history ->
                    history.asSequence()
                        .filter { it.contentType == ContentType.LIVE }
                        .sortedByDescending { it.lastWatchedAt }
                        .distinctBy { it.contentId }
                        .map { it.contentId }
                        .toList()
                }
                .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids) }

            combine(
                recentFlow,
                preferencesRepository.liveChannelNumberingMode
            ) { channels, numberingMode ->
                numberingMode to channels
            }.collect { (numberingMode, channels) ->
                channelNumberingMode = numberingMode
                val currentListNumbers = channelList.withIndex().associate { (index, channel) ->
                    channel.id to resolveChannelNumber(channel, index)
                }
                recentChannelsFlow.value = channels
                        .filterNot { it.id == currentContentId }
                        .map { channel ->
                            currentListNumbers[channel.id]?.let { number ->
                                channel.copy(number = number)
                            } ?: channel
                        }
                }
        }
    }

    private fun observeLastVisitedCategory() {
        lastVisitedCategoryJob?.cancel()
        if (currentContentType != ContentType.LIVE || (currentProviderId <= 0 && currentCombinedProfileId == null)) {
            _lastVisitedCategory.value = null
            return
        }

        lastVisitedCategoryJob = viewModelScope.launch {
            val categoriesFlow = currentCombinedProfileId?.let { profileId ->
                combinedProviderIdsFlow(profileId).flatMapLatest { providerIds ->
                    combine(
                        combinedM3uRepository.getCombinedCategories(profileId),
                        getCustomCategories(providerIds, ContentType.LIVE)
                    ) { combinedCategories, customCategories ->
                        combinedCategoriesById = combinedCategories.associateBy { it.category.id }
                        buildList {
                            val favoritesCategory = customCategories.find { it.id == VirtualCategoryIds.FAVORITES }
                            if (favoritesCategory != null) {
                                add(favoritesCategory)
                            }
                            add(
                                Category(
                                    id = VirtualCategoryIds.RECENT,
                                    name = "Recent",
                                    type = ContentType.LIVE,
                                    isVirtual = true,
                                    count = 0
                                )
                            )
                            addAll(customCategories.filter { it.id != VirtualCategoryIds.FAVORITES })
                            add(
                                Category(
                                    id = ChannelRepository.ALL_CHANNELS_ID,
                                    name = "All Channels",
                                    type = ContentType.LIVE,
                                    count = combinedCategories.sumOf { it.category.count }
                                )
                            )
                            addAll(combinedCategories.map { it.category })
                        } to null
                    }
                }
            } ?: combine(
                channelRepository.getCategories(currentProviderId),
                getCustomCategories(currentProviderId, ContentType.LIVE),
                preferencesRepository.getLastLiveCategoryId(currentProviderId)
            ) { providerCategories, customCategories, lastVisitedCategoryId ->
                val allCategories = customCategories + providerCategories
                val lastVisited = if (lastVisitedCategoryId == null || lastVisitedCategoryId == VirtualCategoryIds.RECENT) {
                    null
                } else {
                    allCategories.firstOrNull { it.id == lastVisitedCategoryId }
                }
                allCategories to lastVisited
            }

            categoriesFlow.collect { (allCategories, lastVisited) ->
                availableCategoriesFlow.value = allCategories
                _lastVisitedCategory.value = lastVisited
            }
        }
    }

    fun openLastVisitedCategory() {
        val category = _lastVisitedCategory.value ?: return
        if (currentContentType != ContentType.LIVE) return

        currentCategoryId = category.id
        activeCategoryIdFlow.value = category.id
        isVirtualCategory = category.isVirtual
        loadPlaylist(
            categoryId = category.id,
            providerId = currentProviderId,
            isVirtual = category.isVirtual,
            initialChannelId = currentContentId
        )
        openChannelListOverlay()
    }

    private fun resolveCurrentLiveChannelIndex(): Int {
        if (channelList.isEmpty()) return -1

        val currentIndexMatchesChannel = currentChannelIndex in channelList.indices && run {
            val currentChannel = channelList[currentChannelIndex]
            currentChannel.id == currentContentId || currentChannel.streamUrl == currentStreamUrl
        }
        if (currentIndexMatchesChannel) {
            return currentChannelIndex
        }

        currentChannelIndex = when {
            currentContentId > 0 -> channelList.indexOfFirst { it.id == currentContentId }
            else -> -1
        }

        if (currentChannelIndex == -1) {
            currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
        }

        return currentChannelIndex
    }

    internal fun wrappedChannelIndex(offset: Int): Int {
        val resolvedIndex = resolveCurrentLiveChannelIndex()
        if (resolvedIndex == -1) return -1
        val size = channelList.size
        return ((resolvedIndex + offset) % size + size) % size
    }

    private fun classifyPlaybackError(error: PlayerError): PlayerRecoveryType = when (error) {
        is PlayerError.NetworkError -> {
            if (error.message.contains("timeout", ignoreCase = true)) {
                PlayerRecoveryType.BUFFER_TIMEOUT
            } else {
                PlayerRecoveryType.NETWORK
            }
        }
        is PlayerError.SourceError -> PlayerRecoveryType.SOURCE
        is PlayerError.DecoderError -> PlayerRecoveryType.DECODER
        is PlayerError.DrmError -> PlayerRecoveryType.DRM
        is PlayerError.UnknownError -> {
            if (error.message.contains("timeout", ignoreCase = true)) {
                PlayerRecoveryType.BUFFER_TIMEOUT
            } else {
                PlayerRecoveryType.UNKNOWN
            }
        }
    }

    private fun resolvePlaybackErrorMessage(error: PlayerError): String = when (classifyPlaybackError(error)) {
        PlayerRecoveryType.NETWORK -> "This stream is not responding right now. You can retry or try another source."
        PlayerRecoveryType.SOURCE -> "We couldn't start this stream on the available paths."
        PlayerRecoveryType.DECODER -> "This stream could not play in the current decoder mode."
        PlayerRecoveryType.DRM -> "Playback requires valid DRM credentials or a supported device security level."
        PlayerRecoveryType.BUFFER_TIMEOUT -> "Playback stayed stuck buffering for too long on this stream."
        PlayerRecoveryType.CATCH_UP -> "Replay is unavailable for the selected program."
        PlayerRecoveryType.UNKNOWN -> error.message.ifBlank { "Playback failed for an unknown reason." }
    }

    private fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> {
        val actions = mutableListOf(PlayerNoticeAction.RETRY)
        if (hasAlternateStream()) {
            actions += PlayerNoticeAction.ALTERNATE_STREAM
        }
        if (hasLastChannel()) {
            actions += PlayerNoticeAction.LAST_CHANNEL
        }
        if (recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE) {
            actions += PlayerNoticeAction.OPEN_GUIDE
        }
        return actions
    }

    private fun markStreamFailure(streamUrl: String) {
        if (streamUrl.isBlank()) return
        failedStreamsThisSession[streamUrl] = (failedStreamsThisSession[streamUrl] ?: 0) + 1
    }

    private fun updateDecoderMode(mode: DecoderMode) {
        _playerDiagnostics.update { it.copy(decoderMode = mode) }
    }

    internal fun updateStreamClass(label: String) {
        currentStreamClassLabel = label
        _isCatchUpPlayback.value = (label == "Catch-up")
        _playerDiagnostics.update { it.copy(streamClassLabel = label) }
    }

    private fun updateChannelDiagnostics(channel: com.streamvault.domain.model.Channel) {
        val archiveLabel = when {
            channel.catchUpSupported && (channel.streamId > 0L || !channel.catchUpSource.isNullOrBlank()) ->
                "Catch-up supported (${channel.catchUpDays} days)"
            channel.catchUpSupported ->
                "Provider advertises catch-up, but replay metadata is incomplete."
            else -> "No archive support advertised"
        }
        val hints = buildList {
            if (channel.errorCount > 0) {
                add("This channel has failed ${channel.errorCount} time(s) recently.")
            }
            if (channel.alternativeStreams.isNotEmpty()) {
                add("${channel.alternativeStreams.size} alternate stream path(s) available.")
            }
            if (channel.catchUpSupported && channel.streamId <= 0L && channel.catchUpSource.isNullOrBlank()) {
                add("Replay may fail because this provider did not expose a catch-up template.")
            }
        }
        _playerDiagnostics.update {
            it.copy(
                alternativeStreamCount = channel.alternativeStreams.size,
                channelErrorCount = channel.errorCount,
                archiveSupportLabel = archiveLabel,
                troubleshootingHints = hints.take(4)
            )
        }
    }

    private suspend fun tryRefreshXtreamPlaybackAfterAuthError(
        error: PlayerError,
        requestVersion: Long,
        playbackUrl: String
    ): Boolean {
        if (hasRetriedXtreamAuthRefresh) return false
        if (error !is PlayerError.NetworkError) return false
        if (!isAuthExpiryPlaybackError(error.message)) return false
        if (!isXtreamPlaybackSession()) return false

        val refreshedStreamInfo = resolvePlaybackStreamInfo(
            logicalUrl = currentStreamUrl,
            internalContentId = currentContentId,
            providerId = currentProviderId,
            contentType = currentContentType
        ) ?: return false

        if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

        hasRetriedXtreamAuthRefresh = true
        probePassedProviderIds.remove(currentProviderId)
        setLastFailureReason(error.message)
        appendRecoveryAction("Refreshed provider playback URL after auth failure")
        showPlayerNotice(
            message = "Refreshing the provider playback URL…",
            recoveryType = PlayerRecoveryType.NETWORK,
            actions = buildRecoveryActions(PlayerRecoveryType.NETWORK),
            isRetryNotice = true
        )
        if (!preparePlayer(refreshedStreamInfo, requestVersion)) return true
        playerEngine.play()
        return true
    }

    private suspend fun isXtreamPlaybackSession(): Boolean {
        val providerId = currentProviderId.takeIf { it > 0L } ?: return false
        val provider = providerRepository.getProvider(providerId) ?: return false
        if (
            provider.type != ProviderType.XTREAM_CODES &&
            provider.type != ProviderType.STALKER_PORTAL
        ) {
            return false
        }
        return xtreamStreamUrlResolver.isInternalStreamUrl(currentStreamUrl) ||
            xtreamStreamUrlResolver.isInternalStreamUrl(currentResolvedPlaybackUrl)
    }

    private fun isAuthExpiryPlaybackError(message: String?): Boolean {
        val normalized = message.orEmpty().lowercase(Locale.ROOT)
        return "401" in normalized ||
            "403" in normalized ||
            "unauthorized" in normalized ||
            "forbidden" in normalized ||
            "authentication" in normalized ||
            "token" in normalized ||
            "expired" in normalized
    }

    private fun setLastFailureReason(message: String?) {
        _playerDiagnostics.update { it.copy(lastFailureReason = message?.takeIf { reason -> reason.isNotBlank() }) }
    }

    private fun appendRecoveryAction(action: String) {
        if (action.isBlank()) return
        _playerDiagnostics.update { state ->
            state.copy(recentRecoveryActions = (listOf(action) + state.recentRecoveryActions).distinct().take(5))
        }
    }

    fun hasAlternateStream(): Boolean {
        if (isCatchUpPlayback()) {
            return pendingCatchUpUrls.any { altUrl ->
                altUrl != currentStreamUrl &&
                    altUrl !in triedAlternativeStreams &&
                    (failedStreamsThisSession[altUrl] ?: 0) == 0
            }
        }
        if (currentContentType != ContentType.LIVE) return false
        val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
        return nextLiveVariant(channel) != null || channel.alternativeStreams.any { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        }
    }

    fun tryAlternateStream(): Boolean {
        if (isCatchUpPlayback()) {
            return tryNextCatchUpVariantInternal()
        }
        if (currentContentType != ContentType.LIVE) return false
        val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
        return tryAlternateStreamInternal(channel)
    }

    private fun tryAlternateStreamInternal(channel: com.streamvault.domain.model.Channel): Boolean {
        nextLiveVariant(channel)?.let { nextVariant ->
            val updatedChannel = channel.withSelectedVariant(nextVariant.rawChannelId)?.sanitizedForPlayer() ?: return@let
            val requestVersion = beginPlaybackSession()
            triedAlternativeStreams.add(nextVariant.streamUrl)
            currentContentId = updatedChannel.id
            currentStreamUrl = updatedChannel.streamUrl
            currentTitle = nextVariant.originalName.ifBlank { updatedChannel.name }
            playbackTitleFlow.value = currentTitle
            currentChannelFlow.value = updatedChannel
            if (currentChannelIndex in channelList.indices) {
                channelList = channelList.mapIndexed { index, existing ->
                    if (index == currentChannelIndex || existing.logicalGroupId == updatedChannel.logicalGroupId) {
                        updatedChannel
                    } else {
                        existing
                    }
                }
                currentChannelFlowList.value = channelList
            }
            if (currentChannelIndex >= 0) {
                displayChannelNumberFlow.value = resolveChannelNumber(updatedChannel, currentChannelIndex)
            }
            refreshCurrentChannelRecording()
            updateChannelDiagnostics(updatedChannel)
            updateStreamClass("Variant")
            viewModelScope.launch {
                preferencesRepository.setPreferredLiveVariant(
                    providerId = updatedChannel.providerId,
                    logicalGroupId = updatedChannel.logicalGroupId,
                    rawChannelId = nextVariant.rawChannelId
                )
                val resolvedUrl = resolvePlaybackUrl(nextVariant.streamUrl, updatedChannel.id, updatedChannel.providerId, ContentType.LIVE)
                    ?: return@launch
                if (!isActivePlaybackSession(requestVersion, nextVariant.streamUrl)) return@launch
                recordLivePlayback(updatedChannel)
                requestEpg(
                    providerId = updatedChannel.providerId,
                    epgChannelId = updatedChannel.epgChannelId,
                    streamId = updatedChannel.streamId,
                    internalChannelId = updatedChannel.id
                )
                val streamInfo = com.streamvault.domain.model.StreamInfo(
                    url = resolvedUrl,
                    title = currentTitle,
                    streamType = com.streamvault.domain.model.StreamType.UNKNOWN
                )
                if (!preparePlayer(streamInfo, requestVersion)) return@launch
                playerEngine.play()
            }
            return true
        }

        val nextStream = channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        } ?: channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
        } ?: return false

        val requestVersion = beginPlaybackSession()
        triedAlternativeStreams.add(nextStream)
        currentStreamUrl = nextStream
        updateStreamClass("Alternate")
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(nextStream, channel.id, channel.providerId, ContentType.LIVE)
                ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    private fun nextLiveVariant(channel: com.streamvault.domain.model.Channel): com.streamvault.domain.model.LiveChannelVariant? {
        val currentVariantId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id
        return channel.variants.firstOrNull { variant ->
            variant.rawChannelId != currentVariantId &&
                variant.streamUrl.isNotBlank() &&
                variant.streamUrl != currentStreamUrl &&
                variant.streamUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[variant.streamUrl] ?: 0) == 0
        } ?: channel.variants.firstOrNull { variant ->
            variant.rawChannelId != currentVariantId &&
                variant.streamUrl.isNotBlank() &&
                variant.streamUrl != currentStreamUrl &&
                variant.streamUrl !in triedAlternativeStreams
        }
    }

    private fun isCatchUpPlayback(): Boolean = currentStreamClassLabel == "Catch-up"

    private fun nextCatchUpVariant(): String? {
        return pendingCatchUpUrls.firstOrNull { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        } ?: pendingCatchUpUrls.firstOrNull { altUrl ->
            altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
        }
    }

    private fun tryNextCatchUpVariantInternal(): Boolean {
        val nextStream = nextCatchUpVariant() ?: return false
        val requestVersion = beginPlaybackSession()
        triedAlternativeStreams.add(nextStream)
        currentStreamUrl = nextStream
        updateStreamClass("Catch-up")
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(nextStream, currentContentId, currentProviderId, ContentType.LIVE)
                ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            if (!preparePlayer(streamInfo, requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    internal fun scheduleZapBufferWatchdog(targetIndex: Int) {
        if (!zapAutoRevertEnabled) return
        zapBufferWatchdogJob?.cancel()
        val requestVersion = prepareRequestVersion
        zapBufferWatchdogJob = viewModelScope.launch {
            // Check every second for 15 s; back off immediately once the stream starts playing
            repeat(15) {
                delay(1000)
                if (!isActivePlaybackSession(requestVersion)) return@launch
                if (currentChannelIndex != targetIndex) return@launch
                val state = playerEngine.playbackState.value
                if (state == PlaybackState.READY || state == PlaybackState.ENDED) return@launch
            }
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val stillOnTarget = currentChannelIndex == targetIndex
            val state = playerEngine.playbackState.value
            val stalled = state == PlaybackState.BUFFERING || state == PlaybackState.ERROR
            if (stillOnTarget && stalled) {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason("Channel timed out in buffering state")
                appendRecoveryAction("Buffer watchdog triggered")
                val recovered = fallbackToPreviousChannel("Channel timed out in buffering state")
                showPlayerNotice(
                    message = if (recovered) {
                        "That channel stalled too long. Returned to the last channel."
                    } else {
                        "That channel stalled too long. Try another source or open the guide."
                    },
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
                )
            }
        }
    }

    private fun fallbackToPreviousChannel(reason: String): Boolean {
        val fallbackIndex = previousChannelIndex
        if (fallbackIndex in channelList.indices && fallbackIndex != currentChannelIndex) {
            android.util.Log.w("PlayerVM", "Falling back to previous channel: $reason")
            // Save before changeChannel overwrites previousChannelIndex with the bad channel's index
            val savedPrevious = previousChannelIndex
            changeChannel(fallbackIndex, isAutoFallback = true)
            // Restore so that zapToLastChannel and a spurious second watchdog don't point at the bad channel
            previousChannelIndex = savedPrevious
            return true
        }
        return false
    }

    fun play() {
        if (currentContentType == ContentType.LIVE &&
            timeshiftConfig.enabled &&
            timeshiftUiState.value.engineState.status == LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
        ) {
            playerEngine.resumeTimeshift()
        } else {
            playerEngine.play()
        }
    }

    fun pause() {
        if (currentContentType == ContentType.LIVE && timeshiftConfig.enabled) {
            playerEngine.pauseTimeshift()
        } else {
            playerEngine.pause()
        }
    }

    fun seekForward() = playerEngine.seekForward()
    fun seekBackward() = playerEngine.seekBackward()
    fun seekToLiveEdge() = playerEngine.seekToLiveEdge()

    fun playEpisode(episode: Episode, showResumePrompt: Boolean = true) {
        prepare(
            streamUrl = episode.streamUrl,
            epgChannelId = null,
            internalChannelId = episode.id,
            categoryId = -1,
            providerId = episode.providerId,
            isVirtual = false,
            contentType = ContentType.SERIES_EPISODE.name,
            title = buildEpisodePlaybackTitle(episode),
            artworkUrl = episode.coverUrl ?: _currentSeries.value?.posterUrl ?: _currentSeries.value?.backdropUrl,
            seriesId = currentSeriesId ?: episode.seriesId.takeIf { it > 0L },
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            showResumePrompt = showResumePrompt
        )
    }

    fun toggleMute() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMuteToggleAtMs < MUTE_TOGGLE_DEBOUNCE_MS) return
        lastMuteToggleAtMs = now
        playerEngine.toggleMute()
        val muted = playerEngine.isMuted.value
        mutePersistJob?.cancel()
        mutePersistJob = viewModelScope.launch {
            preferencesRepository.setPlayerMuted(muted)
        }
    }

    fun toggleControls() {
        closeChannelInfoOverlay()
        showControlsFlow.value = !showControlsFlow.value
        if (!showControlsFlow.value) {
            clearSeekPreview()
        }
    }

    private fun clearSeekPreview() {
        seekPreviewJob?.cancel()
        seekPreviewRequestVersion++
        _seekPreview.value = SeekPreviewState()
    }

    fun toggleAspectRatio() {
        val nextRatio = when (_aspectRatio.value) {
            AspectRatio.FIT -> AspectRatio.FILL
            AspectRatio.FILL -> AspectRatio.ZOOM
            AspectRatio.ZOOM -> AspectRatio.FIT
        }
        _aspectRatio.value = nextRatio

        // Save instantly if we have a valid channel ID
        if (currentContentId != -1L) {
            viewModelScope.launch {
                preferencesRepository.setAspectRatioForChannel(currentContentId, nextRatio.name)
            }
        }
    }

    private suspend fun startCatchUpPlayback(
        urls: List<String>,
        title: String,
        recoveryAction: String,
        requestVersionOverride: Long? = null
    ) {
        val requestVersion = requestVersionOverride ?: beginPlaybackSession()
        val candidates = urls
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val primaryUrl = candidates.firstOrNull() ?: return

        currentTitle = title
        pendingCatchUpUrls = candidates
        triedAlternativeStreams.clear()
        triedAlternativeStreams.add(primaryUrl)
        currentStreamUrl = primaryUrl
        updateStreamClass("Catch-up")
        appendRecoveryAction(recoveryAction)

        val catchupStream = com.streamvault.domain.model.StreamInfo(
            url = primaryUrl,
            title = currentTitle,
            streamType = com.streamvault.domain.model.StreamType.UNKNOWN
        )
        if (!preparePlayer(catchupStream, requestVersion)) return
        playerEngine.play()
    }

    fun playCatchUp(program: Program) {
        viewModelScope.launch {
            val requestVersion = prepareRequestVersion
            val start = program.startTime / 1000L
            val end = program.endTime / 1000L
            val streamId = currentChannelFlow.value?.id ?: 0L
            val providerId = currentProviderId
            
            if (providerId == -1L || streamId == 0L) {
                setLastFailureReason("Catch-up playback needs a valid live channel context.")
                showPlayerNotice(
                    message = "Catch-up playback needs a valid live channel context.",
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            val catchUpUrls = try {
                providerRepository.buildCatchUpUrls(providerId, streamId, start, end)
            } catch (e: CredentialDecryptionException) {
                if (!isActivePlaybackSession(requestVersion)) return@launch
                setLastFailureReason(e.message ?: CredentialDecryptionException.MESSAGE)
                showPlayerNotice(
                    message = e.message ?: CredentialDecryptionException.MESSAGE,
                    recoveryType = PlayerRecoveryType.SOURCE,
                    actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
                )
                return@launch
            }
            if (!isActivePlaybackSession(requestVersion)) return@launch
            if (catchUpUrls.isNotEmpty()) {
                startCatchUpPlayback(
                    urls = catchUpUrls,
                    title = "${currentChannelFlow.value?.name ?: ""}: ${program.title}",
                    recoveryAction = "Started program replay"
                )
            } else {
                val reason = resolveCatchUpFailureMessage(currentChannelFlow.value, archiveRequested = true, programHasArchive = program.hasArchive)
                setLastFailureReason(reason)
                showPlayerNotice(
                    message = reason,
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
            }
        }
    }

    private fun resolveCatchUpFailureMessage(
        channel: com.streamvault.domain.model.Channel?,
        archiveRequested: Boolean,
        programHasArchive: Boolean
    ): String {
        if (!archiveRequested || channel == null) {
            return "Catch-up playback needs a valid live channel context."
        }
        return when {
            !channel.catchUpSupported && !programHasArchive ->
                "This channel does not advertise archive support on the current provider."
            channel.streamId <= 0L && channel.catchUpSource.isNullOrBlank() ->
                "The provider advertises catch-up, but did not expose replay metadata for this channel."
            else ->
                "Replay is unavailable for the selected program right now."
        }
    }

    fun dismissPlayerNotice() {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = null
    }

    private fun dismissRecoveredNoticeIfPresent() {
        val notice = _playerNotice.value ?: return
        if (notice.isRetryNotice || notice.recoveryType != PlayerRecoveryType.UNKNOWN) {
            dismissPlayerNotice()
        }
    }

    fun runPlayerNoticeAction(action: PlayerNoticeAction) {
        when (action) {
            PlayerNoticeAction.RETRY -> {
                appendRecoveryAction("Manual retry")
                retryStream(currentStreamUrl, currentChannelFlow.value?.epgChannelId)
            }
            PlayerNoticeAction.LAST_CHANNEL -> {
                appendRecoveryAction("Returned to last channel")
                zapToLastChannel()
            }
            PlayerNoticeAction.ALTERNATE_STREAM -> {
                appendRecoveryAction("Manual alternate stream")
                tryAlternateStream()
            }
            PlayerNoticeAction.OPEN_GUIDE -> {
                appendRecoveryAction("Opened guide from recovery")
                openEpgOverlay()
            }
        }
        dismissPlayerNotice()
    }

    internal fun showPlayerNotice(
        message: String,
        recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
        actions: List<PlayerNoticeAction> = emptyList(),
        durationMs: Long = playerNoticeTimeoutMs,
        isRetryNotice: Boolean = false
    ) {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = PlayerNoticeState(
            message = message,
            recoveryType = recoveryType,
            actions = actions.distinct(),
            isRetryNotice = isRetryNotice
        )
        playerNoticeHideJob = viewModelScope.launch {
            delay(durationMs)
            if (_playerNotice.value?.message == message) {
                _playerNotice.value = null
            }
        }
    }

    private fun showRetryNotice(status: com.streamvault.player.PlayerRetryStatus) {
        val formatLabel = currentPlaybackFormatLabel()
        val message = "Retrying $formatLabel ${status.attempt}/${status.maxAttempts} in ${status.delayMs / 1000}s..."
        showPlayerNotice(
            message = message,
            recoveryType = PlayerRecoveryType.NETWORK,
            durationMs = maxOf(playerNoticeTimeoutMs, status.delayMs + 1500L),
            isRetryNotice = true
        )
    }

    private fun currentPlaybackFormatLabel(): String {
        val url = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }.lowercase(Locale.ROOT)
        return when {
            url.contains("ext=m3u8") || url.endsWith(".m3u8") -> "HLS"
            url.contains("ext=ts") || url.endsWith(".ts") -> "TS"
            else -> "stream"
        }
    }

    fun restartCurrentProgram() {
        val program = _currentProgram.value ?: return
        if (program.hasArchive || (currentChannelFlow.value?.catchUpSupported == true)) {
            playCatchUp(program)
        }
    }

    fun retryStream(streamUrl: String, epgChannelId: String?) {
        if (isCatchUpPlayback()) {
            val requestVersion = beginPlaybackSession()
            viewModelScope.launch {
                val resolvedUrl = resolvePlaybackUrl(streamUrl, currentContentId, currentProviderId, currentContentType)
                    ?: return@launch
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                val streamInfo = com.streamvault.domain.model.StreamInfo(
                    url = resolvedUrl,
                    title = currentTitle,
                    streamType = com.streamvault.domain.model.StreamType.UNKNOWN
                )
                if (!preparePlayer(streamInfo, requestVersion)) return@launch
                playerEngine.play()
            }
            return
        }
        val currentId = if (currentChannelIndex != -1 && channelList.isNotEmpty()) channelList[currentChannelIndex].id else -1L
        prepare(
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            internalChannelId = currentId,
            categoryId = currentCategoryId,
            providerId = currentProviderId,
            isVirtual = isVirtualCategory,
            combinedProfileId = currentCombinedProfileId,
            combinedSourceFilterProviderId = currentCombinedSourceFilterProviderId,
            contentType = currentContentType.name,
            title = currentTitle
        )
    }

    internal suspend fun resolvePlaybackStreamInfo(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): com.streamvault.domain.model.StreamInfo? {
        var fallbackStreamId: Long? = null
        var fallbackContainerExtension: String? = null

        if (providerId > 0L && internalContentId > 0L) {
            when (contentType) {
                ContentType.LIVE -> {
                    channelRepository.getChannel(internalContentId)?.let { channel ->
                        fallbackStreamId = channel.streamId.takeIf { it > 0L }
                        channelRepository.getStreamInfo(channel).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }

                ContentType.MOVIE -> {
                    movieRepository.getMovie(internalContentId)?.let { movie ->
                        fallbackStreamId = movie.streamId.takeIf { it > 0L }
                        fallbackContainerExtension = movie.containerExtension
                        movieRepository.getStreamInfo(movie).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }

                ContentType.SERIES,
                ContentType.SERIES_EPISODE -> {
                    val episode = when {
                        _currentEpisode.value?.id == internalContentId -> _currentEpisode.value
                        else -> _currentSeries.value
                            ?.seasons
                            .sanitizedForPlayer()
                            ?.asSequence()
                            ?.flatMap { it.episodes.asSequence() }
                            ?.firstOrNull { it.id == internalContentId }
                    }
                    episode?.let {
                        fallbackStreamId = it.episodeId.takeIf { episodeId -> episodeId > 0L } ?: it.id
                        fallbackContainerExtension = it.containerExtension
                        seriesRepository.getEpisodeStreamInfo(it).getOrNull()?.let { resolved ->
                            return resolved.copy(title = resolved.title ?: currentTitle)
                        }
                    }
                }
            }
        }

        try {
            xtreamStreamUrlResolver.resolveWithMetadata(
                url = logicalUrl,
                fallbackProviderId = providerId.takeIf { it > 0 },
                fallbackStreamId = fallbackStreamId,
                fallbackContentType = contentType,
                fallbackContainerExtension = fallbackContainerExtension
            )?.let { resolved ->
                val ext = resolved.containerExtension ?: fallbackContainerExtension
                return com.streamvault.domain.model.StreamInfo(
                    url = resolved.url,
                    title = currentTitle,
                    streamType = com.streamvault.domain.model.StreamType.fromContainerExtension(ext),
                    containerExtension = ext,
                    expirationTime = resolved.expirationTime
                )
            }
        } catch (e: CredentialDecryptionException) {
            val message = e.message ?: CredentialDecryptionException.MESSAGE
            setLastFailureReason(message)
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.SOURCE)
            return null
        }

        return logicalUrl.takeIf { it.isNotBlank() }?.let {
            com.streamvault.domain.model.StreamInfo(
                url = it,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.fromContainerExtension(fallbackContainerExtension),
                containerExtension = fallbackContainerExtension
            )
        }
    }

    internal suspend fun resolvePlaybackUrl(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): String? = resolvePlaybackStreamInfo(
        logicalUrl = logicalUrl,
        internalContentId = internalContentId,
        providerId = providerId,
        contentType = contentType
    )?.url

    fun dismissResumePrompt(resume: Boolean) {
        val prompt = _resumePrompt.value
        _resumePrompt.value = ResumePromptState() // hide
        if (resume && prompt.positionMs > 0) {
            playerEngine.seekTo(prompt.positionMs)
        }
        playerEngine.play()
    }

    override fun onCleared() {
        super.onCleared()
        onPlayerScreenDisposed()
        channelInfoHideJob?.cancel()
        liveOverlayHideJob?.cancel()
        diagnosticsHideJob?.cancel()
        numericInputCommitJob?.cancel()
        numericInputFeedbackJob?.cancel()
        playerNoticeHideJob?.cancel()
        epgJob?.cancel()
        playlistJob?.cancel()
        controlsHideJob?.cancel()
        zapOverlayJob?.cancel()
        zapBufferWatchdogJob?.cancel()
        progressTrackingJob?.cancel()
        tokenRenewalJob?.cancel()
        aspectRatioJob?.cancel()
        recentChannelsJob?.cancel()
        lastVisitedCategoryJob?.cancel()
        seekThumbnailProvider.clearCache()
        playerEngine.release()
    }
}
