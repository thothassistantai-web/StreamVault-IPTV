package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIFECYCLE_TOKEN_RENEWAL_LEAD_MS = 60_000L
private const val LIFECYCLE_TOKEN_RENEWAL_CHECK_INTERVAL_MS = 10_000L

internal fun PlayerViewModel.startProgressTracking() {
    progressTrackingJob?.cancel()
    if (currentContentType == ContentType.LIVE) return

    progressTrackingJob = viewModelScope.launch {
        while (true) {
            delay(5000)
            if (!isAppInForeground || !playerEngine.isPlaying.value) continue
            persistPlaybackProgress()
        }
    }
}

internal suspend fun PlayerViewModel.persistPlaybackProgress() {
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

internal fun PlayerViewModel.startTokenRenewalMonitoring(expirationTime: Long?) {
    tokenRenewalJob?.cancel()
    tokenRenewalJob = null
    val expiry = expirationTime?.takeIf { it > 0L } ?: return
    val requestVersion = prepareRequestVersion
    tokenRenewalJob = viewModelScope.launch {
        while (true) {
            delay(LIFECYCLE_TOKEN_RENEWAL_CHECK_INTERVAL_MS)
            if (!playerEngine.isPlaying.value) continue
            val remaining = expiry - System.currentTimeMillis()
            if (remaining > LIFECYCLE_TOKEN_RENEWAL_LEAD_MS) continue
            if (!isActivePlaybackSession(requestVersion)) return@launch
            val refreshed = resolvePlaybackStreamInfo(
                logicalUrl = currentStreamUrl,
                internalContentId = currentContentId,
                providerId = currentProviderId,
                contentType = currentContentType
            ) ?: return@launch
            if (!isActivePlaybackSession(requestVersion)) return@launch
            currentResolvedPlaybackUrl = refreshed.url
            currentResolvedStreamInfo = refreshed
            playerEngine.renewStreamUrl(refreshed)
            startTokenRenewalMonitoring(refreshed.expirationTime)
            return@launch
        }
    }
}

fun PlayerViewModel.onAppBackgrounded() {
    if (!isAppInForeground) return
    isAppInForeground = false
    shouldResumeAfterForeground = playerEngine.isPlaying.value
    if (shouldResumeAfterForeground) {
        playerEngine.pause()
    }
    if (currentContentType != ContentType.LIVE) {
        viewModelScope.launch {
            persistPlaybackProgress()
            playbackHistoryRepository.flushPendingProgress()
        }
    }
}

fun PlayerViewModel.onAppForegrounded() {
    if (isAppInForeground) return
    isAppInForeground = true
    if (shouldResumeAfterForeground && !resumePrompt.value.show) {
        playerEngine.play()
    }
    shouldResumeAfterForeground = false
}

fun PlayerViewModel.onPlayerScreenDisposed() {
    if (currentContentType != ContentType.LIVE) {
        viewModelScope.launch {
            persistPlaybackProgress()
            playbackHistoryRepository.flushPendingProgress()
        }
    }
    playerEngine.stopLiveTimeshift()
    stopActiveStalkerPlaybackFetchDeferral()
    clearPlaybackTimers()
}

internal fun PlayerViewModel.clearPlaybackTimers() {
    stopPlaybackTimerJob?.cancel()
    idleStandbyTimerJob?.cancel()
    stopPlaybackTimerJob = null
    idleStandbyTimerJob = null
    stopPlaybackTimerEndsAtMs = 0L
    idleStandbyTimerEndsAtMs = 0L
    playbackTimerDefaultsApplied = false
    sleepTimerExitEmitted = false
    _sleepTimerUiState.value = SleepTimerUiState()
}

internal fun PlayerViewModel.stopActiveStalkerPlaybackFetchDeferral() {
    val providerId = activeStalkerPlaybackProviderId ?: return
    activeStalkerPlaybackProviderId = null
    viewModelScope.launch {
        syncManager.noteStalkerPlaybackStopped(providerId)
    }
}

internal suspend fun PlayerViewModel.synchronizeStalkerPlaybackFetchDeferral(isPlaying: Boolean) {
    if (!isPlaying) {
        stopActiveStalkerPlaybackFetchDeferral()
        return
    }

    val providerId = currentProviderId.takeIf { it > 0L } ?: run {
        stopActiveStalkerPlaybackFetchDeferral()
        return
    }
    val provider = providerRepository.getProvider(providerId)
    if (provider?.type != ProviderType.STALKER_PORTAL) {
        stopActiveStalkerPlaybackFetchDeferral()
        return
    }
    if (activeStalkerPlaybackProviderId == providerId) return

    stopActiveStalkerPlaybackFetchDeferral()
    activeStalkerPlaybackProviderId = providerId
    syncManager.noteStalkerPlaybackStarted(providerId)
}

fun PlayerViewModel.handOffPlaybackToMultiView() {
    if (currentContentType != ContentType.LIVE) {
        viewModelScope.launch { persistPlaybackProgress() }
    }
    playerEngine.stopLiveTimeshift()
    stopActiveStalkerPlaybackFetchDeferral()
    livePreviewHandoffManager.clear(playerEngine)
}

internal fun PlayerViewModel.cleanupAfterCleared(mainPlayerEngine: PlayerEngine) {
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
    thumbnailPreloadJob?.cancel()
    inFlightThumbnailPreloadKey = null
    lastCompletedThumbnailPreloadKey = null
    seekThumbnailProvider.clearCache()
    livePreviewHandoffManager.clear(playerEngine)
    if (playerEngine === mainPlayerEngine) {
        mainPlayerEngine.resetForReuse()
    } else {
        playerEngine.release()
        mainPlayerEngine.resetForReuse()
    }
}