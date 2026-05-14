package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.app.cast.CastMediaRequest
import com.streamvault.app.cast.CastStartResult
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import kotlinx.coroutines.launch

fun PlayerViewModel.castCurrentMedia(onRouteSelectionRequired: () -> Unit) {
    viewModelScope.launch {
        if (currentStreamUrl.isCastUnsupportedProtocol()) {
            showPlayerNotice(
                message = "RTSP and RTMP streams are not supported for casting.",
                recoveryType = PlayerRecoveryType.SOURCE
            )
            return@launch
        }

        val request = buildCastRequest()
        if (request == null) {
            showPlayerNotice(
                message = "This item cannot be sent to a Cast receiver.",
                recoveryType = PlayerRecoveryType.SOURCE
            )
            return@launch
        }

        when (castManager.startCasting(request)) {
            CastStartResult.STARTED -> {
                playerEngine.pause()
                showPlayerNotice(
                    message = "Casting to the connected device.",
                    recoveryType = PlayerRecoveryType.NETWORK
                )
            }

            CastStartResult.ROUTE_SELECTION_REQUIRED -> onRouteSelectionRequired()

            CastStartResult.UNAVAILABLE -> showPlayerNotice(
                message = "Google Cast is unavailable on this device.",
                recoveryType = PlayerRecoveryType.SOURCE
            )

            CastStartResult.UNSUPPORTED -> showPlayerNotice(
                message = "This stream format is not supported by Google Cast.",
                recoveryType = PlayerRecoveryType.SOURCE
            )
        }
    }
}

fun PlayerViewModel.stopCasting() {
    castManager.stopCasting()
    showPlayerNotice(
        message = "Cast session disconnected.",
        recoveryType = PlayerRecoveryType.NETWORK
    )
}

fun PlayerViewModel.startManualRecording() {
    val channel = currentChannel.value
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
                scheduledEndMs = currentProgram.value?.endTime ?: (now + 30 * 60_000L),
                programTitle = currentProgram.value?.title
            )
        )
        if (result is Result.Error) {
            showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
        } else {
            showPlayerNotice(message = "Recording started for ${channel.name}.")
        }
    }
}

fun PlayerViewModel.scheduleRecording() {
    scheduleRecordingInternal(RecordingRecurrence.NONE)
}

fun PlayerViewModel.scheduleDailyRecording() {
    scheduleRecordingInternal(RecordingRecurrence.DAILY)
}

fun PlayerViewModel.scheduleWeeklyRecording() {
    scheduleRecordingInternal(RecordingRecurrence.WEEKLY)
}

private fun PlayerViewModel.scheduleRecordingInternal(recurrence: RecordingRecurrence) {
    viewModelScope.launch {
        val result = scheduleRecordingUseCase(
            ScheduleRecordingCommand(
                contentType = currentContentType,
                providerId = currentProviderId,
                channel = currentChannel.value,
                streamUrl = currentStreamUrl,
                currentProgram = currentProgram.value,
                nextProgram = nextProgram.value,
                recurrence = recurrence
            )
        )
        if (result is Result.Error) {
            showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
        } else {
            val recurrenceLabel = when (recurrence) {
                RecordingRecurrence.NONE -> ""
                RecordingRecurrence.DAILY -> " daily"
                RecordingRecurrence.WEEKLY -> " weekly"
            }
            val scheduledItem = (result as? Result.Success)?.data
            val title = scheduledItem?.programTitle ?: "Recording"
            showPlayerNotice(message = "$title scheduled$recurrenceLabel.")
        }
    }
}

fun PlayerViewModel.stopCurrentRecording() {
    val recording = currentChannelRecording.value ?: return
    viewModelScope.launch {
        val result = recordingManager.stopRecording(recording.id)
        if (result is Result.Error) {
            showPlayerNotice(message = result.message)
        } else {
            showPlayerNotice(message = "Recording stopped.")
        }
    }
}

internal suspend fun PlayerViewModel.buildCastRequest(): CastMediaRequest? {
    return when (currentContentType) {
        ContentType.LIVE -> {
            val channel = currentChannel.value ?: return null
            // Use preferStableUrl = true for Cast: the credential-based portal URL
            // does not expire, unlike the tokenized direct-source CDN URL.
            val streamInfo = channelRepository.getStreamInfo(channel, preferStableUrl = true)
                .getOrNull() ?: return null
            streamInfo.toCastRequest(
                title = mediaTitle.value ?: channel.name,
                subtitle = currentProgram.value?.title,
                artworkUrl = channel.logoUrl ?: currentArtworkUrl,
                isLive = true,
                startPositionMs = 0L
            )
        }

        ContentType.MOVIE -> {
            val movie = movieRepository.getMovie(currentContentId)
            val streamInfo = movie?.let { movieRepository.getStreamInfo(it).getOrNull() }
                ?: return directCastRequest()
            streamInfo.toCastRequest(
                title = currentTitle.ifBlank { movie.name },
                subtitle = movie.genre,
                artworkUrl = currentArtworkUrl ?: movie.posterUrl ?: movie.backdropUrl,
                isLive = false,
                startPositionMs = playerEngine.currentPosition.value
            )
        }

        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> directCastRequest()
    }
}

internal fun PlayerViewModel.directCastRequest(): CastMediaRequest? {
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return null
    return StreamInfo(url = url).toCastRequest(
        title = currentTitle,
        subtitle = null,
        artworkUrl = currentArtworkUrl,
        isLive = false,
        startPositionMs = playerEngine.currentPosition.value
    )
}

internal fun StreamInfo.toCastRequest(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    isLive: Boolean,
    startPositionMs: Long
): CastMediaRequest? {
    val resolvedUrl = url.takeIf { it.isNotBlank() } ?: return null
    val mimeType = when (inferCastStreamType()) {
        StreamType.HLS -> "application/x-mpegURL"
        StreamType.DASH -> "application/dash+xml"
        StreamType.SMOOTH_STREAMING -> "application/vnd.ms-sstr+xml"
        StreamType.MPEG_TS -> "video/mp2t"
        StreamType.RTSP -> return null
        StreamType.PROGRESSIVE,
        StreamType.UNKNOWN -> "video/*"
    }
    return CastMediaRequest(
        url = resolvedUrl,
        title = title.ifBlank { this.title ?: "StreamVault" },
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        mimeType = mimeType,
        isLive = isLive,
        startPositionMs = if (isLive) 0L else startPositionMs
    )
}

internal fun StreamInfo.inferCastStreamType(): StreamType {
    if (streamType != StreamType.UNKNOWN) {
        return streamType
    }
    val normalizedUrl = url.lowercase()
    return when {
        normalizedUrl.endsWith(".m3u8") -> StreamType.HLS
        normalizedUrl.endsWith(".mpd") -> StreamType.DASH
        normalizedUrl.contains(".isml/manifest") || normalizedUrl.contains(".ism/manifest") ||
            normalizedUrl.endsWith(".ism") || normalizedUrl.endsWith(".isml") -> StreamType.SMOOTH_STREAMING
        normalizedUrl.endsWith(".ts") -> StreamType.MPEG_TS
        normalizedUrl.startsWith("rtsp") -> StreamType.RTSP
        else -> StreamType.PROGRESSIVE
    }
}

private fun String.isCastUnsupportedProtocol(): Boolean {
    val normalizedUrl = trim().lowercase()
    return normalizedUrl.startsWith("rtsp://") ||
        normalizedUrl.startsWith("rtsps://") ||
        normalizedUrl.startsWith("rtmp://") ||
        normalizedUrl.startsWith("rtmps://")
}
