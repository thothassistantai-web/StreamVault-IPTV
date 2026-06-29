package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val PROVIDER_AUTH_RETRY_GRACE_MS = 1_200L

internal fun PlayerViewModel.buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> {
    return buildPlayerRecoveryActions(
        hasAlternateStream = hasAlternateStream(),
        hasLastChannel = hasLastChannel(),
        shouldOfferGuide = recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE
    )
}

internal fun shouldAttemptProviderAuthRetry(
    providerType: ProviderType,
    contentType: ContentType
): Boolean = contentType == ContentType.LIVE &&
    (providerType == ProviderType.XTREAM_CODES || providerType == ProviderType.STALKER_PORTAL)

internal fun shouldCooldownLivePreloadAfterError(message: String?): Boolean {
    val normalized = message.orEmpty().lowercase(Locale.ROOT)
    return "401" in normalized ||
        "403" in normalized ||
        "429" in normalized ||
        "509" in normalized ||
        "forbidden" in normalized ||
        "unauthorized" in normalized ||
        "too many" in normalized ||
        "max connection" in normalized ||
        "provider limit" in normalized ||
        "connection limit" in normalized
}

internal fun PlayerViewModel.cooldownLivePreloadForCurrentProvider(reason: String) {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    if (livePreloadCooldownProviderIds.add(providerId)) {
        appendRecoveryAction("Disabled live preload for provider: $reason")
        playerEngine.preload(null)
    }
}

internal suspend fun PlayerViewModel.tryRefreshXtreamPlaybackAfterAuthError(
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
    probePassedPlaybackKeys.remove(
        resolvePlaybackProbeCacheKey(
            currentStreamUrl = currentStreamUrl,
            url = playbackUrl
        )
    )
    setLastFailureReason(error.message)
    cooldownLivePreloadForCurrentProvider("auth or provider-limit response")
    appendRecoveryAction("Retrying provider playback from a fresh live URL")
    delay(PROVIDER_AUTH_RETRY_GRACE_MS)
    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return true
    currentResolvedPlaybackUrl = ""
    currentResolvedStreamInfo = null
    playerEngine.preload(null)
    if (!preparePlayer(refreshedStreamInfo, requestVersion, probeBeforePlayback = false)) return true
    playerEngine.play()
    return true
}

internal suspend fun PlayerViewModel.isXtreamPlaybackSession(): Boolean {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return false
    val provider = providerRepository.getProvider(providerId) ?: return false
    return shouldAttemptProviderAuthRetry(provider.type, currentContentType)
}

internal fun PlayerViewModel.fallbackToPreviousChannel(reason: String): Boolean {
    val fallbackIndex = previousChannelIndex
    if (fallbackIndex in channelList.indices && fallbackIndex != currentChannelIndex) {
        android.util.Log.w("PlayerVM", "Falling back to previous channel: $reason")
        val savedPrevious = previousChannelIndex
        changeChannel(fallbackIndex, isAutoFallback = true)
        previousChannelIndex = savedPrevious
        return true
    }
    return false
}

internal fun PlayerViewModel.scheduleZapBufferWatchdog(targetIndex: Int) {
    if (!zapAutoRevertEnabled) return
    zapBufferWatchdogJob?.cancel()
    val requestVersion = prepareRequestVersion
    val isGatewayChannel = pluginManager.isGatewayManagedUrl(currentStreamUrl)
    val baseTimeoutMs = if (isGatewayChannel) 50_000L else 15_000L
    val maxTimeoutMs = if (isGatewayChannel) 75_000L else 20_000L
    zapBufferWatchdogJob = viewModelScope.launch {
        var deadlineMs = SystemClock.elapsedRealtime() + baseTimeoutMs
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            delay(1_000)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            if (currentChannelIndex != targetIndex) return@launch
            val state = playerEngine.playbackState.value
            if (state == PlaybackState.READY || state == PlaybackState.ENDED) return@launch
            if (playerEngine.retryStatus.value != null) {
                deadlineMs = minOf(
                    maxTimeoutMs,
                    maxOf(deadlineMs, SystemClock.elapsedRealtime() + 10_000L)
                )
            }
        }
        if (!isActivePlaybackSession(requestVersion)) return@launch
        val stillOnTarget = currentChannelIndex == targetIndex
        val state = playerEngine.playbackState.value
        val stalled = state == PlaybackState.BUFFERING || state == PlaybackState.ERROR
        if (stillOnTarget && stalled && playerEngine.retryStatus.value == null) {
            markStreamFailure(currentStreamUrl)
            setLastFailureReason("Channel timed out in buffering state")
            appendRecoveryAction("Buffer watchdog triggered")
            val recovered = if (isGatewayChannel) {
                false
            } else {
                fallbackToPreviousChannel("Channel timed out in buffering state")
            }
            showPlayerNotice(
                message = if (recovered) {
                    "That channel stalled too long. Returned to the last channel."
                } else if (isGatewayChannel) {
                    "Gateway stream stalled too long. Check StepDaddy Gateway or retry this channel."
                } else {
                    "That channel stalled too long. Try another source or open the guide."
                },
                recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
            )
        }
    }
}
