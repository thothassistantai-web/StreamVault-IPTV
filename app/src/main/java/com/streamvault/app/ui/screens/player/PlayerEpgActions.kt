package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EPG_REFRESH_INTERVAL_MS = 30_000L
internal const val EPG_ZAP_DEBOUNCE_MS = 400L

internal fun PlayerViewModel.scheduleEpgFetch(
    providerId: Long,
    internalChannelId: Long,
    epgChannelId: String?,
    streamId: Long = 0L,
) {
    epgDebounceJob?.cancel()
    epgJob?.cancel()
    if (providerId <= 0L || (internalChannelId <= 0L && epgChannelId == null && streamId <= 0L)) {
        clearEpgState()
        return
    }

    val requestKey = EpgRequestKey(
        providerId = providerId,
        internalChannelId = internalChannelId,
        epgChannelId = epgChannelId,
        streamId = streamId,
    )

    epgDebounceJob = viewModelScope.launch {
        delay(EPG_ZAP_DEBOUNCE_MS)
        if (activeEpgRequestKey != requestKey) return@launch
        fetchEpg(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = epgChannelId,
            streamId = streamId,
        )
    }
}

internal fun PlayerViewModel.fetchEpg(
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

    val cachedPrograms = playerEpgTimelineCache.getStale(requestKey)
    if (!cachedPrograms.isNullOrEmpty()) {
        applyProgramTimeline(cachedPrograms, System.currentTimeMillis())
    }

    epgJob = viewModelScope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            val start = now - (24 * 60 * 60 * 1000L)
            val end = now + (6 * 60 * 60 * 1000L)

            val programs = playerEpgTimelineCache.getFresh(requestKey)
                ?: epgRepository.getResolvedProgramsForPlaybackChannel(
                    providerId = providerId,
                    internalChannelId = internalChannelId,
                    epgChannelId = epgChannelId,
                    streamId = streamId,
                    startTime = start,
                    endTime = end
                ).also { loaded ->
                    if (loaded.isNotEmpty()) {
                        playerEpgTimelineCache.put(requestKey, loaded)
                    }
                }

            if (activeEpgRequestKey != requestKey) return@launch

            if (programs.isNotEmpty()) {
                applyProgramTimeline(programs, now)
            } else {
                applyRemoteProgramFallback(providerId, epgChannelId, streamId, now, requestKey)
                if (activeEpgRequestKey != requestKey) return@launch
            }

            delay(EPG_REFRESH_INTERVAL_MS)
        }
    }
}

internal suspend fun PlayerViewModel.applyRemoteProgramFallback(
    providerId: Long,
    epgChannelId: String?,
    streamId: Long,
    now: Long,
    requestKey: EpgRequestKey? = null,
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
    requestKey?.let { playerEpgTimelineCache.put(it, programs) }
}
