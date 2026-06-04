package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.app.player.LiveTranslationClient
import com.streamvault.app.player.LiveTranslationSession
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun PlayerViewModel.toggleLiveTranslation() {
    viewModelScope.launch {
        val nextEnabled = !liveTranslationEnabled.value
        preferencesRepository.setPlayerLiveTranslationEnabled(nextEnabled)
        if (!nextEnabled) {
            stopLiveTranslationSession()
        } else {
            evaluateLiveTranslationSession()
        }
    }
}

internal suspend fun PlayerViewModel.evaluateLiveTranslationSession() {
    val enabled = preferencesRepository.playerLiveTranslationEnabled.first()
    if (currentProviderId <= 0L) {
        stopLiveTranslationSession()
        return
    }
    val provider = providerRepository.getProvider(currentProviderId)
    if (!shouldEnableLiveTranslationSession(enabled, currentContentType, provider?.type)) {
        stopLiveTranslationSession()
        return
    }
    if (currentResolvedStreamInfo == null) {
        stopLiveTranslationSession()
        return
    }
    val endpoint = preferencesRepository.playerLiveTranslationEndpoint.first()
    val session = LiveTranslationSession(
        scope = viewModelScope,
        playerEngine = playerEngine,
        client = LiveTranslationClient(okHttpClient, endpoint),
        logicalUrl = currentStreamUrl,
        providerId = currentProviderId,
        contentId = currentContentId,
        onSourceLanguageDetected = { language ->
            _liveTranslationDetectedLanguage.value = language
        },
        onError = { message ->
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.NETWORK)
        }
    )
    stopLiveTranslationSession(clearEnabledState = false)
    liveTranslationSession = session
    session.start()
}

internal fun PlayerViewModel.stopLiveTranslationSession(clearEnabledState: Boolean = true) {
    liveTranslationSession?.stop()
    liveTranslationSession = null
    playerEngine.clearInjectedSubtitleCues()
    _liveTranslationDetectedLanguage.value = null
    if (clearEnabledState) {
        _liveTranslationEnabled.value = false
    }
}

internal fun shouldEnableLiveTranslationSession(
    enabledPreference: Boolean,
    contentType: ContentType,
    providerType: ProviderType?
): Boolean {
    return enabledPreference &&
        contentType == ContentType.LIVE &&
        (providerType == ProviderType.XTREAM_CODES || providerType == ProviderType.M3U)
}
