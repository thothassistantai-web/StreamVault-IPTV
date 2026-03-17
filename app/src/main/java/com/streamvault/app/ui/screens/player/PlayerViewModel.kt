package com.streamvault.app.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResumePromptState(
    val show: Boolean = false,
    val positionMs: Long = 0L,
    val title: String = ""
)

data class NumericChannelInputState(
    val input: String = "",
    val matchedChannelName: String? = null,
    val invalid: Boolean = false
)

data class PlayerNoticeState(
    val message: String = "",
    val recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
    val actions: List<PlayerNoticeAction> = emptyList()
)

data class PlayerDiagnosticsUiState(
    val providerName: String = "",
    val providerSourceLabel: String = "",
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val streamClassLabel: String = "Primary",
    val playbackStateLabel: String = "Idle",
    val alternativeStreamCount: Int = 0,
    val channelErrorCount: Int = 0,
    val archiveSupportLabel: String = "",
    val lastFailureReason: String? = null,
    val recentRecoveryActions: List<String> = emptyList(),
    val troubleshootingHints: List<String> = emptyList()
)

enum class PlayerRecoveryType {
    NETWORK,
    SOURCE,
    DECODER,
    CATCH_UP,
    BUFFER_TIMEOUT,
    UNKNOWN
}

enum class PlayerNoticeAction {
    RETRY,
    LAST_CHANNEL,
    ALTERNATE_STREAM,
    OPEN_GUIDE
}

enum class AspectRatio(val modeName: String) {
    FIT("Fit"),
    FILL("Stretch"),
    ZOOM("Zoom")
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel @Inject constructor(
    @MainPlayerEngine
    val playerEngine: PlayerEngine,
    private val epgRepository: EpgRepository,
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val favoriteRepository: com.streamvault.domain.repository.FavoriteRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: com.streamvault.domain.repository.ProviderRepository,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val getCustomCategories: GetCustomCategories,
    private val recordingManager: RecordingManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : ViewModel() {

    private val _showControls = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = _showControls.asStateFlow()

    private val _showZapOverlay = MutableStateFlow(false)
    val showZapOverlay: StateFlow<Boolean> = _showZapOverlay.asStateFlow()
    
    private val _currentProgram = MutableStateFlow<Program?>(null)
    val currentProgram: StateFlow<Program?> = _currentProgram.asStateFlow()

    private val _nextProgram = MutableStateFlow<Program?>(null)
    val nextProgram: StateFlow<Program?> = _nextProgram.asStateFlow()

    private val _programHistory = MutableStateFlow<List<Program>>(emptyList())
    val programHistory: StateFlow<List<Program>> = _programHistory.asStateFlow()

    private val _upcomingPrograms = MutableStateFlow<List<Program>>(emptyList())
    val upcomingPrograms: StateFlow<List<Program>> = _upcomingPrograms.asStateFlow()

    private val _currentChannel = MutableStateFlow<com.streamvault.domain.model.Channel?>(null)
    val currentChannel: StateFlow<com.streamvault.domain.model.Channel?> = _currentChannel.asStateFlow()
    
    private val _resumePrompt = MutableStateFlow(ResumePromptState())
    val resumePrompt: StateFlow<ResumePromptState> = _resumePrompt.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    private val _showChannelListOverlay = MutableStateFlow(false)
    val showChannelListOverlay: StateFlow<Boolean> = _showChannelListOverlay.asStateFlow()

    private val _showEpgOverlay = MutableStateFlow(false)
    val showEpgOverlay: StateFlow<Boolean> = _showEpgOverlay.asStateFlow()

    private val _currentChannelList = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val currentChannelList: StateFlow<List<com.streamvault.domain.model.Channel>> = _currentChannelList.asStateFlow()

    private val _recentChannels = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val recentChannels: StateFlow<List<com.streamvault.domain.model.Channel>> = _recentChannels.asStateFlow()

    private val _lastVisitedCategory = MutableStateFlow<Category?>(null)
    val lastVisitedCategory: StateFlow<Category?> = _lastVisitedCategory.asStateFlow()

    private val _showCategoryListOverlay = MutableStateFlow(false)
    val showCategoryListOverlay: StateFlow<Boolean> = _showCategoryListOverlay.asStateFlow()

    private val _availableCategories = MutableStateFlow<List<Category>>(emptyList())
    val availableCategories: StateFlow<List<Category>> = _availableCategories.asStateFlow()

    private val _activeCategoryId = MutableStateFlow(-1L)
    val activeCategoryId: StateFlow<Long> = _activeCategoryId.asStateFlow()

    private val _displayChannelNumber = MutableStateFlow(0)
    val displayChannelNumber: StateFlow<Int> = _displayChannelNumber.asStateFlow()

    private val _showChannelInfoOverlay = MutableStateFlow(false)
    val showChannelInfoOverlay: StateFlow<Boolean> = _showChannelInfoOverlay.asStateFlow()

    private val _numericChannelInput = MutableStateFlow<NumericChannelInputState?>(null)
    val numericChannelInput: StateFlow<NumericChannelInputState?> = _numericChannelInput.asStateFlow()

    private val _showDiagnostics = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = _showDiagnostics.asStateFlow()

    private val _playerNotice = MutableStateFlow<PlayerNoticeState?>(null)
    val playerNotice: StateFlow<PlayerNoticeState?> = _playerNotice.asStateFlow()
    private val _playerDiagnostics = MutableStateFlow(PlayerDiagnosticsUiState())
    val playerDiagnostics: StateFlow<PlayerDiagnosticsUiState> = _playerDiagnostics.asStateFlow()
    private val _recordingItems = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordingItems: StateFlow<List<RecordingItem>> = _recordingItems.asStateFlow()
    private val _currentChannelRecording = MutableStateFlow<RecordingItem?>(null)
    val currentChannelRecording: StateFlow<RecordingItem?> = _currentChannelRecording.asStateFlow()

    private var channelInfoHideJob: Job? = null
    private var liveOverlayHideJob: Job? = null
    private var diagnosticsHideJob: Job? = null
    private var numericInputCommitJob: Job? = null
    private var numericInputFeedbackJob: Job? = null
    private var playerNoticeHideJob: Job? = null
    private var numericInputBuffer: String = ""
    private val triedAlternativeStreams = mutableSetOf<String>()
    private val failedStreamsThisSession = mutableMapOf<String, Int>()
    private var hasRetriedWithSoftwareDecoder = false
    private var lastRecordedLivePlaybackKey: Pair<Long, Long>? = null
    private var currentStreamClassLabel: String = "Primary"
    private var prepareRequestVersion: Long = 0L

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
                if (state == PlaybackState.READY && currentContentType == ContentType.LIVE) {
                    zapBufferWatchdogJob?.cancel()
                    _currentChannel.value?.let { channel ->
                        if (channel.errorCount > 0) {
                            channelRepository.resetChannelErrorCount(channel.id)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _recordingItems.value = items
                refreshCurrentChannelRecording(items)
            }
        }
    }

    private fun handlePlaybackError(error: PlayerError) {
        if (error is PlayerError.DecoderError && !hasRetriedWithSoftwareDecoder) {
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
        val recoveryType = classifyPlaybackError(error)
        val channel = _currentChannel.value

        if (currentContentType != ContentType.LIVE || channel == null) {
            showPlayerNotice(
                message = resolvePlaybackErrorMessage(error),
                recoveryType = recoveryType,
                actions = buildRecoveryActions(recoveryType)
            )
            return
        }

        viewModelScope.launch {
            markStreamFailure(currentStreamUrl)
            setLastFailureReason(error.message)
            channelRepository.incrementChannelErrorCount(channel.id)

            val switched = when (recoveryType) {
                PlayerRecoveryType.NETWORK,
                PlayerRecoveryType.SOURCE,
                PlayerRecoveryType.BUFFER_TIMEOUT -> tryAlternateStreamInternal(channel)
                else -> false
            }

            if (switched) {
                appendRecoveryAction("Trying alternate stream")
                showPlayerNotice(
                    message = "Trying an alternate stream for ${channel.name}.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
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

    fun openChannelListOverlay() {
        clearNumericChannelInput()
        _showChannelListOverlay.value = true
        _showCategoryListOverlay.value = false
        _showEpgOverlay.value = false
        _showChannelInfoOverlay.value = false
        _showControls.value = false
        scheduleLiveOverlayAutoHide()
    }

    fun openCategoryListOverlay() {
        if (currentProviderId <= 0 || _availableCategories.value.isEmpty()) return
        _showCategoryListOverlay.value = true
        _showChannelListOverlay.value = false
        scheduleLiveOverlayAutoHide()
    }

    fun selectCategoryFromOverlay(category: Category) {
        _showCategoryListOverlay.value = false
        currentCategoryId = category.id
        _activeCategoryId.value = category.id
        isVirtualCategory = category.isVirtual
        loadPlaylist(
            categoryId = category.id,
            providerId = currentProviderId,
            isVirtual = category.isVirtual,
            initialChannelId = currentContentId
        )
        openChannelListOverlay()
    }

    fun openEpgOverlay() {
        clearNumericChannelInput()
        _showEpgOverlay.value = true
        _showChannelListOverlay.value = false
        _showChannelInfoOverlay.value = false
        _showControls.value = false
        scheduleLiveOverlayAutoHide()
    }

    fun openChannelInfoOverlay() {
        clearNumericChannelInput()
        _showChannelInfoOverlay.value = true
        _showChannelListOverlay.value = false
        _showEpgOverlay.value = false
        _showControls.value = false
        channelInfoHideJob?.cancel()
        scheduleLiveOverlayAutoHide()
    }

    fun closeChannelInfoOverlay() {
        channelInfoHideJob?.cancel()
        _showChannelInfoOverlay.value = false
        if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
    }

    fun closeOverlays() {
        clearNumericChannelInput()
        _showChannelListOverlay.value = false
        _showCategoryListOverlay.value = false
        _showEpgOverlay.value = false
        _showChannelInfoOverlay.value = false
        _showDiagnostics.value = false
        channelInfoHideJob?.cancel()
        clearLiveOverlayAutoHide()
        clearDiagnosticsAutoHide()
    }

    private fun refreshCurrentChannelRecording(items: List<RecordingItem> = _recordingItems.value) {
        val channelId = _currentChannel.value?.id ?: -1L
        _currentChannelRecording.value = items.firstOrNull {
            it.providerId == currentProviderId &&
                it.channelId == channelId &&
                (it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED)
        }
    }

    fun toggleDiagnostics() {
        _showDiagnostics.value = !_showDiagnostics.value
        if (_showDiagnostics.value) {
            scheduleDiagnosticsAutoHide()
        } else {
            clearDiagnosticsAutoHide()
        }
    }

    fun onLiveOverlayInteraction() {
        if (hasVisibleTransientLiveOverlay()) {
            scheduleLiveOverlayAutoHide()
        }
        if (_showDiagnostics.value) {
            scheduleDiagnosticsAutoHide()
        }
    }

    // Zapping state
    private var channelList: List<com.streamvault.domain.model.Channel> = emptyList()
    private var currentChannelIndex = -1
    private var previousChannelIndex = -1
    private var currentCategoryId: Long = -1
    private var currentProviderId: Long = -1L
    private var currentContentId: Long = -1L
    private var currentContentType: ContentType = ContentType.LIVE
    private var currentTitle: String = ""
    private var isVirtualCategory: Boolean = false
    
    private var epgJob: Job? = null
    private var playlistJob: Job? = null
    private var recentChannelsJob: Job? = null
    private var lastVisitedCategoryJob: Job? = null
    private var controlsHideJob: Job? = null
    private var progressTrackingJob: Job? = null
    private var zapOverlayJob: Job? = null
    private var aspectRatioJob: Job? = null
    private var zapBufferWatchdogJob: Job? = null
    private var isAppInForeground: Boolean = true
    private var shouldResumeAfterForeground: Boolean = false
    
    val playerError: StateFlow<PlayerError?> = playerEngine.error
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

    val videoFormat: StateFlow<VideoFormat> = playerEngine.videoFormat
    
    val playerStats = playerEngine.playerStats
    val availableAudioTracks = playerEngine.availableAudioTracks
    val availableSubtitleTracks = playerEngine.availableSubtitleTracks

    private val _availableVideoQualities = MutableStateFlow<List<com.streamvault.player.PlayerTrack>>(emptyList())
    val availableVideoQualities: StateFlow<List<com.streamvault.player.PlayerTrack>> = _availableVideoQualities.asStateFlow()

    fun selectAudioTrack(trackId: String) {
        playerEngine.selectAudioTrack(trackId)
    }

    fun selectSubtitleTrack(trackId: String?) {
        playerEngine.selectSubtitleTrack(trackId)
    }

    fun selectVideoQuality(url: String) {
        prepare(
            streamUrl = url,
            epgChannelId = _currentChannel.value?.epgChannelId,
            internalChannelId = currentContentId,
            categoryId = currentCategoryId,
            providerId = currentProviderId,
            isVirtual = isVirtualCategory,
            contentType = currentContentType.name,
            title = currentTitle
        )
    }

    fun prepare(
        streamUrl: String, 
        epgChannelId: String?, 
        internalChannelId: Long, 
        categoryId: Long = -1, 
        providerId: Long = -1, 
        isVirtual: Boolean = false,
        contentType: String = "CHANNEL",
        title: String = "",
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null
    ) {
        val requestVersion = ++prepareRequestVersion
        currentStreamUrl = streamUrl
        currentProviderId = providerId
        currentContentId = internalChannelId
        currentTitle = title
        currentContentType = try { ContentType.valueOf(contentType) } catch (e: Exception) { ContentType.LIVE }
        currentStreamClassLabel = if (archiveStartMs != null && archiveEndMs != null) "Catch-up" else "Primary"
        if (currentContentType != ContentType.LIVE) {
            lastRecordedLivePlaybackKey = null
            recentChannelsJob?.cancel()
            _recentChannels.value = emptyList()
            lastVisitedCategoryJob?.cancel()
            _lastVisitedCategory.value = null
        }
        hasRetriedWithSoftwareDecoder = false
        playerEngine.setDecoderMode(DecoderMode.AUTO)
        updateDecoderMode(DecoderMode.AUTO)
        updateStreamClass(currentStreamClassLabel)
        
        // Reset tried streams for manual switch
        triedAlternativeStreams.clear()
        triedAlternativeStreams.add(streamUrl)

        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(streamUrl, internalChannelId, providerId, currentContentType)
            if (requestVersion != prepareRequestVersion) return@launch
            if (resolvedUrl.isNullOrBlank()) {
                showPlayerNotice(message = "No playable stream URL was available.", recoveryType = PlayerRecoveryType.SOURCE)
                return@launch
            }
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            playerEngine.prepare(streamInfo)
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
                            }
                        )
                    }
                }
            }
        } else {
            _playerDiagnostics.update { it.copy(providerName = "", providerSourceLabel = "") }
        }
        
        // 1. Check for Resume Position for VODs
        if (currentContentType != ContentType.LIVE && currentContentId != -1L && currentProviderId != -1L) {
            viewModelScope.launch {
                val history = playbackHistoryRepository.getPlaybackHistory(currentContentId, currentContentType, currentProviderId)
                if (history != null && history.resumePositionMs > 5000L && history.totalDurationMs > 0 && history.resumePositionMs < history.totalDurationMs * 0.95) {
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
        if (categoryId != -1L && (categoryId != currentCategoryId || providerId != currentProviderId)) {
            currentCategoryId = categoryId
            _activeCategoryId.value = categoryId
            currentProviderId = providerId
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

        if (
            currentContentType == ContentType.LIVE &&
            archiveStartMs != null &&
            archiveEndMs != null &&
            archiveStartMs > 0L &&
            archiveEndMs > archiveStartMs
        ) {
            viewModelScope.launch {
                val catchUpUrl = providerRepository.buildCatchUpUrl(
                    providerId = currentProviderId,
                    streamId = currentContentId,
                    start = archiveStartMs / 1000L,
                    end = archiveEndMs / 1000L
                )
                if (catchUpUrl != null) {
                    currentTitle = archiveTitle?.takeIf { it.isNotBlank() } ?: currentTitle
                    currentStreamUrl = catchUpUrl
                    updateStreamClass("Catch-up")
                    appendRecoveryAction("Opened catch-up stream")
                    val catchupStream = com.streamvault.domain.model.StreamInfo(
                        url = catchUpUrl,
                        title = currentTitle,
                        streamType = com.streamvault.domain.model.StreamType.UNKNOWN
                    )
                    playerEngine.prepare(catchupStream)
                    playerEngine.play()
                    _showControls.value = true
                } else {
                    val reason = resolveCatchUpFailureMessage(_currentChannel.value, archiveRequested = true, programHasArchive = true)
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
        fetchEpg(currentProviderId, epgChannelId)
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
                _currentChannel.value = channel
                refreshCurrentChannelRecording()
                if (channel != null) {
                    currentTitle = channel.name.ifBlank { currentTitle }
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
                    }
                    updateChannelDiagnostics(channel)
                }
                if (channel != null && channel.alternativeStreams.isNotEmpty()) {
                    val tracks = mutableListOf<com.streamvault.player.PlayerTrack>()
                    tracks.add(
                        com.streamvault.player.PlayerTrack(
                            id = channel.streamUrl,
                            name = "Primary / Default",
                            language = null,
                            type = com.streamvault.player.TrackType.VIDEO,
                            isSelected = streamUrl == channel.streamUrl
                        )
                    )
                    channel.alternativeStreams.forEachIndexed { index, altUrl ->
                        tracks.add(
                            com.streamvault.player.PlayerTrack(
                                id = altUrl,
                                name = "Alternative ${index + 1}",
                                language = null,
                                type = com.streamvault.player.TrackType.VIDEO,
                                isSelected = streamUrl == altUrl
                            )
                        )
                    }
                    _availableVideoQualities.value = tracks as List<com.streamvault.player.PlayerTrack> // Ensure cast
                } else {
                    _availableVideoQualities.value = emptyList()
                }
            }
        } else {
             _availableVideoQualities.value = emptyList()
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

        if (pos > 0 && dur > 0 && currentContentId != -1L && currentProviderId != -1L) {
            val history = PlaybackHistory(
                contentId = currentContentId,
                contentType = currentContentType,
                providerId = currentProviderId,
                title = currentTitle,
                streamUrl = currentStreamUrl,
                resumePositionMs = pos,
                totalDurationMs = dur,
                lastWatchedAt = System.currentTimeMillis()
            )
            playbackHistoryRepository.updateResumePosition(history)
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
    }

    private fun fetchEpg(providerId: Long, epgChannelId: String?) {
        epgJob?.cancel()
        if (providerId > 0 && epgChannelId != null) {
            epgJob = viewModelScope.launch {
                launch {
                    epgRepository.getNowAndNext(providerId, epgChannelId).collect { (now, next) ->
                        _currentProgram.value = now
                        _nextProgram.value = next
                    }
                }
                launch {
                    // Fetch last 24 hours.
                    val now = System.currentTimeMillis()
                    val start = now - (24 * 60 * 60 * 1000L)
                    epgRepository.getProgramsForChannel(providerId, epgChannelId, start, now).collect { programs ->
                        _programHistory.value = programs
                            .filter { it.hasArchive }
                            .sortedByDescending { it.startTime }
                    }
                }
                launch {
                    // Fetch next 6 hours.
                    val now = System.currentTimeMillis()
                    val end = now + (6 * 60 * 60 * 1000L)
                    epgRepository.getProgramsForChannel(providerId, epgChannelId, now, end).collect { programs ->
                        _upcomingPrograms.value = programs.sortedBy { it.startTime }
                    }
                }
            }
        } else {
            _currentProgram.value = null
            _nextProgram.value = null
            _programHistory.value = emptyList()
            _upcomingPrograms.value = emptyList()
        }
    }

    private fun loadPlaylist(categoryId: Long, providerId: Long, isVirtual: Boolean, initialChannelId: Long) {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            val flows = if (isVirtual) {
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
                                val map = unsorted.associateBy { it.id }
                                ids.mapNotNull { map[it] }
                            }
                        }
                } else if (categoryId == VirtualCategoryIds.FAVORITES) {
                    // Global Favorites
                    favoriteRepository.getFavorites(com.streamvault.domain.model.ContentType.LIVE)
                        .map { favorites -> favorites.map { it.contentId } }
                        .flatMapLatest { ids -> 
                            if (ids.isEmpty()) flowOf(emptyList()) 
                            else channelRepository.getChannelsByIds(ids)
                        }
                } else {
                    val groupId = if (categoryId < 0) -categoryId else categoryId
                    favoriteRepository.getFavoritesByGroup(groupId)
                        .map { favorites -> favorites.map { it.contentId } }
                        .flatMapLatest { ids -> 
                            if (ids.isEmpty()) flowOf(emptyList()) 
                            else channelRepository.getChannelsByIds(ids)
                        }
                }
            } else {
                channelRepository.getChannelsByCategory(providerId, categoryId)
            }
            
            flows.collect { channels ->
                val numberedChannels = channels.mapIndexed { index, channel ->
                    channel.copy(number = index + 1)
                }
                channelList = numberedChannels
                _currentChannelList.value = numberedChannels
                // Recalculate index based on initial ID or URL
                if (initialChannelId != -1L) {
                    currentChannelIndex = channelList.indexOfFirst { it.id == initialChannelId }
                }
                if (currentChannelIndex == -1) {
                    currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
                }
                
                if (currentChannelIndex != -1) {
                    _currentChannel.value = channelList[currentChannelIndex]
                    refreshCurrentChannelRecording()
                    val ch = channelList[currentChannelIndex]
                    _displayChannelNumber.value = resolveChannelNumber(ch, currentChannelIndex)
                }
            }
        }
    }
    
    // Store current URL to find index later
    private var currentStreamUrl: String = ""

    private fun observeRecentChannels() {
        recentChannelsJob?.cancel()
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) {
            _recentChannels.value = emptyList()
            return
        }

        recentChannelsJob = viewModelScope.launch {
            playbackHistoryRepository.getRecentlyWatchedByProvider(currentProviderId, limit = 12)
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
                    else channelRepository.getChannelsByIds(ids).map { channels ->
                        val channelMap = channels.associateBy { it.id }
                        ids.mapNotNull { channelMap[it] }
                    }
                }
                .collect { channels ->
                    _recentChannels.value = channels.filterNot { it.id == currentContentId }
                }
        }
    }

    private fun observeLastVisitedCategory() {
        lastVisitedCategoryJob?.cancel()
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) {
            _lastVisitedCategory.value = null
            return
        }

        lastVisitedCategoryJob = viewModelScope.launch {
            combine(
                channelRepository.getCategories(currentProviderId),
                getCustomCategories(ContentType.LIVE),
                preferencesRepository.getLastLiveCategoryId(currentProviderId)
            ) { providerCategories, customCategories, lastVisitedCategoryId ->
                val allCategories = customCategories + providerCategories
                val lastVisited = if (lastVisitedCategoryId == null || lastVisitedCategoryId == VirtualCategoryIds.RECENT) {
                    null
                } else {
                    allCategories.firstOrNull { it.id == lastVisitedCategoryId }
                }
                Pair(allCategories, lastVisited)
            }.collect { (allCategories, lastVisited) ->
                _availableCategories.value = allCategories
                _lastVisitedCategory.value = lastVisited
            }
        }
    }

    fun openLastVisitedCategory() {
        val category = _lastVisitedCategory.value ?: return
        if (currentContentType != ContentType.LIVE || currentProviderId <= 0) return

        currentCategoryId = category.id
        _activeCategoryId.value = category.id
        isVirtualCategory = category.isVirtual
        loadPlaylist(
            categoryId = category.id,
            providerId = currentProviderId,
            isVirtual = category.isVirtual,
            initialChannelId = currentContentId
        )
        openChannelListOverlay()
    }

    fun playNext() {
        clearNumericChannelInput()
        if (channelList.isEmpty()) return
        
        if (currentChannelIndex == -1) {
             currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
             if (currentChannelIndex == -1) return
        }
        
        val nextIndex = (currentChannelIndex + 1) % channelList.size
        changeChannel(nextIndex)
    }

    fun playPrevious() {
        clearNumericChannelInput()
        if (channelList.isEmpty()) return
        
        if (currentChannelIndex == -1) {
             currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
             if (currentChannelIndex == -1) return
        }
        
        val prevIndex = if (currentChannelIndex - 1 < 0) channelList.size - 1 else currentChannelIndex - 1
        changeChannel(prevIndex)
    }

    fun zapToChannel(channelId: Long) {
        clearNumericChannelInput()
        if (channelList.isEmpty()) return
        val index = channelList.indexOfFirst { it.id == channelId }
        if (index != -1) {
            changeChannel(index)
            closeOverlays()
        }
    }

    fun zapToLastChannel() {
        clearNumericChannelInput()
        if (channelList.isEmpty() || previousChannelIndex == -1) return
        changeChannel(previousChannelIndex)
    }

    fun hasLastChannel(): Boolean =
        currentContentType == ContentType.LIVE &&
            previousChannelIndex in channelList.indices &&
            previousChannelIndex != currentChannelIndex

    fun hasPendingNumericChannelInput(): Boolean = numericInputBuffer.isNotBlank()

    fun inputNumericChannelDigit(digit: Int) {
        if (currentContentType != ContentType.LIVE || channelList.isEmpty() || digit !in 0..9) return

        if (numericInputBuffer.isEmpty() && digit == 0) {
            zapToLastChannel()
            return
        }

        numericInputBuffer = if (numericInputBuffer.length >= 4) {
            digit.toString()
        } else {
            numericInputBuffer + digit.toString()
        }

        val exactMatch = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
        val previewMatch = exactMatch ?: resolveChannelByPrefix(numericInputBuffer)

        _numericChannelInput.value = NumericChannelInputState(
            input = numericInputBuffer,
            matchedChannelName = previewMatch?.name,
            invalid = false
        )

        scheduleNumericChannelCommit()
    }

    fun commitNumericChannelInput() {
        numericInputCommitJob?.cancel()
        if (numericInputBuffer.isBlank()) return

        val targetChannel = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
        if (targetChannel != null) {
            val targetIndex = channelList.indexOfFirst { it.id == targetChannel.id }
            if (targetIndex != -1) {
                changeChannel(targetIndex)
            }
            clearNumericChannelInput()
            return
        }

        _numericChannelInput.value = NumericChannelInputState(
            input = numericInputBuffer,
            matchedChannelName = null,
            invalid = true
        )

        numericInputFeedbackJob?.cancel()
        numericInputFeedbackJob = viewModelScope.launch {
            delay(900)
            clearNumericChannelInput()
        }
    }

    fun clearNumericChannelInput() {
        numericInputCommitJob?.cancel()
        numericInputFeedbackJob?.cancel()
        numericInputBuffer = ""
        _numericChannelInput.value = null
    }

    private fun changeChannel(index: Int) {
        clearNumericChannelInput()
        if (currentChannelIndex != -1 && currentChannelIndex != index) {
            previousChannelIndex = currentChannelIndex
        }
        val channel = channelList[index]
        currentChannelIndex = index
        currentContentId = channel.id
        currentTitle = channel.name
        currentStreamUrl = channel.streamUrl
        updateStreamClass("Primary")
        _currentChannel.value = channel
        refreshCurrentChannelRecording()
        _displayChannelNumber.value = resolveChannelNumber(channel, index)
        _recentChannels.update { channels -> channels.filterNot { it.id == channel.id } }

        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(channel.streamUrl, channel.id, channel.providerId, ContentType.LIVE)
                ?: return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            playerEngine.prepare(streamInfo)
            playerEngine.play()
        }
        
        fetchEpg(currentProviderId, channel.epgChannelId)
        
        // Show bottom live info overlay
        _showZapOverlay.value = false
        _showControls.value = false 
        openChannelInfoOverlay()
        
        // Reset tried streams for manual switch
        triedAlternativeStreams.clear()
        triedAlternativeStreams.add(channel.streamUrl)
        if (currentContentType == ContentType.LIVE) {
            recordLivePlayback(channel)
            scheduleZapBufferWatchdog(index)
        }
    }

    private fun recordLivePlayback(channel: com.streamvault.domain.model.Channel) {
        if (currentProviderId <= 0 || currentContentType != ContentType.LIVE) return

        val playbackKey = currentProviderId to channel.id
        if (lastRecordedLivePlaybackKey == playbackKey) return
        lastRecordedLivePlaybackKey = playbackKey

        viewModelScope.launch {
            playbackHistoryRepository.recordPlayback(
                PlaybackHistory(
                    contentId = channel.id,
                    contentType = ContentType.LIVE,
                    providerId = currentProviderId,
                    title = channel.name,
                    streamUrl = channel.streamUrl,
                    lastWatchedAt = System.currentTimeMillis()
                )
            )
        }
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
        is PlayerError.UnknownError -> {
            if (error.message.contains("timeout", ignoreCase = true)) {
                PlayerRecoveryType.BUFFER_TIMEOUT
            } else {
                PlayerRecoveryType.UNKNOWN
            }
        }
    }

    private fun resolvePlaybackErrorMessage(error: PlayerError): String = when (classifyPlaybackError(error)) {
        PlayerRecoveryType.NETWORK -> "The stream stopped responding. Check the network or try another source."
        PlayerRecoveryType.SOURCE -> "This stream is unavailable from the provider right now."
        PlayerRecoveryType.DECODER -> "Playback failed in the current decoder path."
        PlayerRecoveryType.BUFFER_TIMEOUT -> "Playback stalled for too long on this stream."
        PlayerRecoveryType.CATCH_UP -> "Replay is unavailable for the selected program."
        PlayerRecoveryType.UNKNOWN -> error.message.ifBlank { "Playback failed for an unknown reason." }
    }

    private fun buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> = buildList {
        add(PlayerNoticeAction.RETRY)
        if (hasAlternateStream()) {
            add(PlayerNoticeAction.ALTERNATE_STREAM)
        }
        if (hasLastChannel()) {
            add(PlayerNoticeAction.LAST_CHANNEL)
        }
        if (recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE) {
            add(PlayerNoticeAction.OPEN_GUIDE)
        }
    }

    private fun markStreamFailure(streamUrl: String) {
        if (streamUrl.isBlank()) return
        failedStreamsThisSession[streamUrl] = (failedStreamsThisSession[streamUrl] ?: 0) + 1
    }

    private fun updateDecoderMode(mode: DecoderMode) {
        _playerDiagnostics.update { it.copy(decoderMode = mode) }
    }

    private fun updateStreamClass(label: String) {
        currentStreamClassLabel = label
        _playerDiagnostics.update { it.copy(streamClassLabel = label) }
    }

    private fun updateChannelDiagnostics(channel: com.streamvault.domain.model.Channel) {
        val archiveLabel = when {
            channel.catchUpSupported && channel.catchUpSource.isNullOrBlank() -> "Provider advertises catch-up, but replay metadata is incomplete."
            channel.catchUpSupported -> "Catch-up supported (${channel.catchUpDays} days)"
            else -> "No archive support advertised"
        }
        val hints = buildList {
            if (channel.errorCount > 0) {
                add("This channel has failed ${channel.errorCount} time(s) recently.")
            }
            if (channel.alternativeStreams.isNotEmpty()) {
                add("${channel.alternativeStreams.size} alternate stream path(s) available.")
            }
            if (channel.catchUpSupported && channel.catchUpSource.isNullOrBlank()) {
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
        val channel = _currentChannel.value ?: return false
        return channel.alternativeStreams.any { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        }
    }

    fun tryAlternateStream(): Boolean {
        val channel = _currentChannel.value ?: return false
        return tryAlternateStreamInternal(channel)
    }

    private fun tryAlternateStreamInternal(channel: com.streamvault.domain.model.Channel): Boolean {
        val nextStream = channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl &&
                altUrl !in triedAlternativeStreams &&
                (failedStreamsThisSession[altUrl] ?: 0) == 0
        } ?: channel.alternativeStreams.firstOrNull { altUrl ->
            altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
        } ?: return false

        triedAlternativeStreams.add(nextStream)
        currentStreamUrl = nextStream
        updateStreamClass("Alternate")
        viewModelScope.launch {
            val resolvedUrl = resolvePlaybackUrl(nextStream, channel.id, channel.providerId, ContentType.LIVE)
                ?: return@launch
            val streamInfo = com.streamvault.domain.model.StreamInfo(
                url = resolvedUrl,
                title = currentTitle,
                streamType = com.streamvault.domain.model.StreamType.UNKNOWN
            )
            playerEngine.prepare(streamInfo)
            playerEngine.play()
        }
        return true
    }

    private fun scheduleZapBufferWatchdog(targetIndex: Int) {
        zapBufferWatchdogJob?.cancel()
        zapBufferWatchdogJob = viewModelScope.launch {
            delay(9000)
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
            changeChannel(fallbackIndex)
            return true
        }
        return false
    }

    private fun scheduleNumericChannelCommit() {
        numericInputCommitJob?.cancel()
        numericInputCommitJob = viewModelScope.launch {
            delay(1300)
            commitNumericChannelInput()
        }
    }

    private fun resolveChannelByNumber(number: Int?): com.streamvault.domain.model.Channel? {
        if (number == null) return null
        return channelList
            .withIndex()
            .firstOrNull { (index, channel) -> resolveChannelNumber(channel, index) == number }
            ?.value
    }

    private fun resolveChannelByPrefix(prefix: String): com.streamvault.domain.model.Channel? {
        return channelList
            .withIndex()
            .firstOrNull { (index, channel) ->
                resolveChannelNumber(channel, index).toString().startsWith(prefix)
            }
            ?.value
    }

    private fun resolveChannelNumber(
        channel: com.streamvault.domain.model.Channel,
        index: Int
    ): Int = index + 1

    fun play() = playerEngine.play()
    fun pause() = playerEngine.pause()
    fun seekForward() = playerEngine.seekForward()
    fun seekBackward() = playerEngine.seekBackward()

    fun toggleControls() {
        closeChannelInfoOverlay()
        _showControls.value = !_showControls.value
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

    fun playCatchUp(program: Program) {
        viewModelScope.launch {
            val start = program.startTime / 1000L
            val end = program.endTime / 1000L
            val streamId = _currentChannel.value?.id ?: 0L
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

            val catchUpUrl = providerRepository.buildCatchUpUrl(providerId, streamId, start, end)
            if (catchUpUrl != null) {
                // Update metadata for player
                currentTitle = "${_currentChannel.value?.name ?: ""}: ${program.title}"
                currentStreamUrl = catchUpUrl
                updateStreamClass("Catch-up")
                appendRecoveryAction("Started program replay")
                
                val streamInfo = com.streamvault.domain.model.StreamInfo(
                    url = catchUpUrl,
                    title = currentTitle,
                    streamType = com.streamvault.domain.model.StreamType.UNKNOWN
                )
                playerEngine.prepare(streamInfo)
                playerEngine.play()
                _showControls.value = true
            } else {
                val reason = resolveCatchUpFailureMessage(_currentChannel.value, archiveRequested = true, programHasArchive = program.hasArchive)
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
            channel.catchUpSupported && channel.catchUpSource.isNullOrBlank() ->
                "The provider marks this channel as catch-up capable, but no replay template was supplied."
            channel.streamId <= 0L ->
                "The provider did not expose a replay stream identifier for this channel."
            else ->
                "The provider did not return a replay stream for the selected program."
        }
    }

    fun startManualRecording() {
        val channel = _currentChannel.value
        if (currentContentType != ContentType.LIVE || channel == null || currentProviderId <= 0) {
            showPlayerNotice(message = "Recording needs a valid live channel context.")
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = recordingManager.startManualRecording(
                RecordingRequest(
                    providerId = currentProviderId,
                    channelId = channel.id,
                    channelName = channel.name,
                    streamUrl = currentStreamUrl,
                    scheduledStartMs = now,
                    scheduledEndMs = _currentProgram.value?.endTime ?: (now + 30 * 60_000L),
                    programTitle = _currentProgram.value?.title
                )
            )
            if (result is com.streamvault.domain.model.Result.Error) {
                showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
            } else {
                showPlayerNotice(message = "Recording started for ${channel.name}.")
            }
        }
    }

    fun scheduleRecording() {
        val channel = _currentChannel.value
        val targetProgram = _nextProgram.value ?: _currentProgram.value
        if (currentContentType != ContentType.LIVE || channel == null || currentProviderId <= 0 || targetProgram == null) {
            showPlayerNotice(message = "Recording needs guide timing for the current live channel.")
            return
        }
        viewModelScope.launch {
            val result = recordingManager.scheduleRecording(
                RecordingRequest(
                    providerId = currentProviderId,
                    channelId = channel.id,
                    channelName = channel.name,
                    streamUrl = currentStreamUrl,
                    scheduledStartMs = maxOf(System.currentTimeMillis(), targetProgram.startTime),
                    scheduledEndMs = targetProgram.endTime,
                    programTitle = targetProgram.title
                )
            )
            if (result is com.streamvault.domain.model.Result.Error) {
                showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
            } else {
                showPlayerNotice(message = "Recording scheduled for ${targetProgram.title}.")
            }
        }
    }

    fun stopCurrentRecording() {
        val recording = _currentChannelRecording.value ?: return
        viewModelScope.launch {
            val result = recordingManager.stopRecording(recording.id)
            if (result is com.streamvault.domain.model.Result.Error) {
                showPlayerNotice(message = result.message)
            } else {
                showPlayerNotice(message = "Recording stopped.")
            }
        }
    }

    fun dismissPlayerNotice() {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = null
    }

    fun runPlayerNoticeAction(action: PlayerNoticeAction) {
        when (action) {
            PlayerNoticeAction.RETRY -> {
                appendRecoveryAction("Manual retry")
                retryStream(currentStreamUrl, _currentChannel.value?.epgChannelId)
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

    private fun showPlayerNotice(
        message: String,
        recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
        actions: List<PlayerNoticeAction> = emptyList(),
        durationMs: Long = if (actions.isNotEmpty()) 8000L else 3600L
    ) {
        playerNoticeHideJob?.cancel()
        _playerNotice.value = PlayerNoticeState(
            message = message,
            recoveryType = recoveryType,
            actions = actions.distinct()
        )
        playerNoticeHideJob = viewModelScope.launch {
            delay(durationMs)
            if (_playerNotice.value?.message == message) {
                _playerNotice.value = null
            }
        }
    }

    fun restartCurrentProgram() {
        val program = _currentProgram.value ?: return
        if (program.hasArchive || (_currentChannel.value?.catchUpSupported == true)) {
            playCatchUp(program)
        }
    }

    fun hideControlsAfterDelay() {
        // Cancel previous job to prevent race condition
        controlsHideJob?.cancel()
        controlsHideJob = viewModelScope.launch {
            delay(5000)
            _showControls.value = false
        }
    }

    private fun hideZapOverlayAfterDelay() {
        zapOverlayJob?.cancel()
        zapOverlayJob = viewModelScope.launch {
            delay(4000)
            _showZapOverlay.value = false
        }
    }

    private fun hasVisibleTransientLiveOverlay(): Boolean =
        _showChannelInfoOverlay.value ||
            _showChannelListOverlay.value ||
            _showEpgOverlay.value

    private fun clearLiveOverlayAutoHide() {
        liveOverlayHideJob?.cancel()
        liveOverlayHideJob = null
    }

    private fun clearDiagnosticsAutoHide() {
        diagnosticsHideJob?.cancel()
        diagnosticsHideJob = null
    }

    private fun scheduleLiveOverlayAutoHide() {
        if (currentContentType != ContentType.LIVE) {
            clearLiveOverlayAutoHide()
            return
        }
        liveOverlayHideJob?.cancel()
        liveOverlayHideJob = viewModelScope.launch {
            delay(4000)
            _showChannelInfoOverlay.value = false
            _showChannelListOverlay.value = false
            _showEpgOverlay.value = false
        }
    }

    private fun scheduleDiagnosticsAutoHide() {
        if (currentContentType != ContentType.LIVE) {
            clearDiagnosticsAutoHide()
            return
        }
        diagnosticsHideJob?.cancel()
        diagnosticsHideJob = viewModelScope.launch {
            delay(15000)
            _showDiagnostics.value = false
        }
    }
    
    fun retryStream(streamUrl: String, epgChannelId: String?) {
        val currentId = if (currentChannelIndex != -1 && channelList.isNotEmpty()) channelList[currentChannelIndex].id else -1L
        prepare(streamUrl, epgChannelId, currentId, currentCategoryId, currentProviderId, isVirtualCategory, currentContentType.name, currentTitle)
    }

    private suspend fun resolvePlaybackUrl(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): String? {
        xtreamStreamUrlResolver.resolve(
            url = logicalUrl,
            fallbackProviderId = providerId.takeIf { it > 0 },
            fallbackContentType = contentType
        )?.let { return it }

        if (providerId <= 0L || internalContentId <= 0L) {
            return logicalUrl.takeIf { it.isNotBlank() }
        }

        return when (contentType) {
            ContentType.LIVE -> channelRepository.getChannel(internalContentId)?.let { channel ->
                channelRepository.getStreamUrl(channel).getOrNull()
            }
            ContentType.MOVIE -> movieRepository.getMovie(internalContentId)?.let { movie ->
                movieRepository.getStreamUrl(movie).getOrNull()
            }
            ContentType.SERIES, ContentType.SERIES_EPISODE -> logicalUrl.takeIf { it.isNotBlank() }
        }
    }

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
        aspectRatioJob?.cancel()
        recentChannelsJob?.cancel()
        lastVisitedCategoryJob?.cancel()
        playerEngine.release()
    }
}
