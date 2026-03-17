package com.streamvault.app.ui.screens.player.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.screens.player.NumericChannelInputState
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PlayerActionSpec(
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    title: String,
    contentType: String,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    playButtonFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    clockLabelOverride: String? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                title = title,
                contentType = contentType,
                clockLabelOverride = clockLabelOverride,
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            PlayerBottomBar(
                title = title,
                contentType = contentType,
                currentProgram = currentProgram,
                currentChannelName = currentChannelName,
                displayChannelNumber = displayChannelNumber,
                currentPosition = currentPosition,
                duration = duration,
                aspectRatioLabel = aspectRatioLabel,
                subtitleTrackCount = subtitleTrackCount,
                audioTrackCount = audioTrackCount,
                videoQualityCount = videoQualityCount,
                currentRecordingStatus = currentRecordingStatus,
                modifier = Modifier.align(Alignment.BottomCenter),
                onRestartProgram = onRestartProgram,
                onOpenArchive = onOpenArchive,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onScheduleRecording = onScheduleRecording,
                onToggleAspectRatio = onToggleAspectRatio,
                onOpenSubtitleTracks = onOpenSubtitleTracks,
                onOpenAudioTracks = onOpenAudioTracks,
                onOpenVideoTracks = onOpenVideoTracks,
                onOpenSplitScreen = onOpenSplitScreen
            )

            if (contentType != "LIVE") {
                PlayerTransportBar(
                    isPlaying = isPlaying,
                    playButtonFocusRequester = playButtonFocusRequester,
                    onSeekBackward = onSeekBackward,
                    onSeekForward = onSeekForward,
                    onTogglePlayPause = onTogglePlayPause,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun PlayerZapOverlay(
    visible: Boolean,
    displayChannelNumber: Int,
    channelName: String?,
    programTitle: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally(),
        exit = fadeOut() + slideOutHorizontally(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(32.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.84f), Color.Transparent)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(18.dp)
                .widthIn(min = 320.dp, max = 460.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayChannelNumber.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = channelName.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!programTitle.isNullOrBlank()) {
                        Text(
                            text = programTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerNumericInputOverlay(
    state: NumericChannelInputState?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && state != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        val inputState = state ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(14.dp))
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = inputState.input,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (inputState.invalid) ErrorColor else Primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        inputState.invalid -> stringResource(R.string.player_channel_not_found)
                        !inputState.matchedChannelName.isNullOrBlank() -> inputState.matchedChannelName
                        else -> stringResource(R.string.player_type_channel_number)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PlayerAspectRatioToast(
    aspectRatioLabel: String,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(aspectRatioLabel) {
        show = true
        delay(2000)
        show = false
    }

    AnimatedVisibility(
        visible = show && !controlsVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(Primary.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.player_aspect_ratio_label, aspectRatioLabel),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PlayerResolutionBadge(
    visible: Boolean,
    resolutionLabel: String,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = resolutionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    contentType: String,
    clockLabelOverride: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                )
            )
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                PlayerMetaPill(
                    text = when (contentType) {
                        "LIVE" -> stringResource(R.string.nav_live_tv)
                        "MOVIE" -> stringResource(R.string.player_type_movie)
                        else -> stringResource(R.string.player_type_series)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (contentType != "LIVE") {
                    Text(
                        text = if (contentType == "MOVIE") {
                            stringResource(R.string.player_type_movie)
                        } else {
                            stringResource(R.string.player_type_series)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val currentTime = remember(clockLabelOverride) {
                    mutableStateOf(
                        clockLabelOverride ?: SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    )
                }
                LaunchedEffect(clockLabelOverride) {
                    if (clockLabelOverride == null) {
                        while (true) {
                            delay(10_000)
                            currentTime.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        }
                    } else {
                        currentTime.value = clockLabelOverride
                    }
                }
                Text(
                    text = currentTime.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(end = 16.dp)
                )

                Surface(
                    onClick = onClose,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        focusedContainerColor = Primary.copy(alpha = 0.9f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.player_close),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(
    title: String,
    contentType: String,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(248.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))
                )
            )
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF0C1624).copy(alpha = 0.92f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Primary.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
            if (contentType == "LIVE") {
                PlayerLiveInfo(
                    currentProgram = currentProgram,
                    currentChannelName = currentChannelName,
                    displayChannelNumber = displayChannelNumber,
                    aspectRatioLabel = aspectRatioLabel,
                    subtitleTrackCount = subtitleTrackCount,
                    audioTrackCount = audioTrackCount,
                    videoQualityCount = videoQualityCount,
                    currentRecordingStatus = currentRecordingStatus,
                    onRestartProgram = onRestartProgram,
                    onOpenArchive = onOpenArchive,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onScheduleRecording = onScheduleRecording,
                    onToggleAspectRatio = onToggleAspectRatio,
                    onOpenSubtitleTracks = onOpenSubtitleTracks,
                    onOpenAudioTracks = onOpenAudioTracks,
                    onOpenVideoTracks = onOpenVideoTracks,
                    onOpenSplitScreen = onOpenSplitScreen
                )
            } else if (contentType != "LIVE") {
                PlayerVodInfo(
                    title = title,
                    currentPosition = currentPosition,
                    duration = duration,
                    aspectRatioLabel = aspectRatioLabel,
                    subtitleTrackCount = subtitleTrackCount,
                    audioTrackCount = audioTrackCount,
                    videoQualityCount = videoQualityCount,
                    onToggleAspectRatio = onToggleAspectRatio,
                    onOpenSubtitleTracks = onOpenSubtitleTracks,
                    onOpenAudioTracks = onOpenAudioTracks,
                    onOpenVideoTracks = onOpenVideoTracks
                )
            }
            }
        }
    }
}

@Composable
private fun PlayerLiveInfo(
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: RecordingStatus?,
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenSplitScreen: () -> Unit
) {
    val primaryActions = buildList {
        if (currentProgram?.hasArchive == true) {
            add(PlayerActionSpec(stringResource(R.string.player_restart), onRestartProgram))
            add(PlayerActionSpec(stringResource(R.string.player_archive), onOpenArchive))
        }
        if (currentRecordingStatus == RecordingStatus.RECORDING) {
            add(PlayerActionSpec(stringResource(R.string.player_stop_recording), onStopRecording))
        } else {
            add(PlayerActionSpec(stringResource(R.string.player_record), onStartRecording))
            add(PlayerActionSpec(stringResource(R.string.player_schedule_recording), onScheduleRecording))
        }
    }
    val secondaryActions = buildList {
        add(PlayerActionSpec(stringResource(R.string.player_aspect_ratio_label, aspectRatioLabel), onToggleAspectRatio))
        if (subtitleTrackCount > 0) {
            add(PlayerActionSpec(stringResource(R.string.player_subs), onOpenSubtitleTracks))
        }
        if (audioTrackCount > 1) {
            add(PlayerActionSpec(stringResource(R.string.player_audio), onOpenAudioTracks))
        }
        if (videoQualityCount > 1) {
            add(PlayerActionSpec(stringResource(R.string.player_video_quality), onOpenVideoTracks))
        }
        add(PlayerActionSpec(stringResource(R.string.multiview_nav), onOpenSplitScreen))
    }

    Row(verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerMetaPill(text = stringResource(R.string.player_live_now), accent = true)
                PlayerMetaPill(text = stringResource(R.string.player_live_channel, displayChannelNumber))
                if (currentProgram?.hasArchive == true) {
                    PlayerMetaPill(text = stringResource(R.string.player_archive_badge))
                }
            }
            Text(
                text = currentProgram?.title ?: currentChannelName.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1
            )
            Text(
                text = if (currentProgram != null) {
                    stringResource(R.string.channel_number_name_format, displayChannelNumber, currentChannelName.orEmpty())
                } else {
                    stringResource(R.string.player_no_guide_data)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    val start = currentProgram?.startTime ?: 0L
    val end = currentProgram?.endTime ?: 0L
    if (start > 0 && end > 0) {
        val now = System.currentTimeMillis()
        val progress = (now - start).toFloat() / (end - start)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = Primary,
            trackColor = Color.White.copy(alpha = 0.2f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    } else {
        Text(
            text = currentChannelName?.let { stringResource(R.string.channel_number_name_format, displayChannelNumber, it) }.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.82f)
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    PlayerQuickActionRows(primaryActions, secondaryActions)
}

@Composable
private fun PlayerVodInfo(
    title: String,
    currentPosition: Long,
    duration: Long,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit
) {
    val actions = buildList {
        add(PlayerActionSpec(stringResource(R.string.player_aspect_ratio_label, aspectRatioLabel), onToggleAspectRatio))
        if (subtitleTrackCount > 0) {
            add(PlayerActionSpec(stringResource(R.string.player_subs), onOpenSubtitleTracks))
        }
        if (audioTrackCount > 1) {
            add(PlayerActionSpec(stringResource(R.string.player_audio), onOpenAudioTracks))
        }
        if (videoQualityCount > 1) {
            add(PlayerActionSpec(stringResource(R.string.player_video_quality), onOpenVideoTracks))
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlayerMetaPill(text = stringResource(R.string.player_playback_label), accent = true)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 1
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDuration(currentPosition),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
        Slider(
            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
            onValueChange = { },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            colors = SliderDefaults.colors(
                activeTrackColor = Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }

    PlayerQuickActionRows(actions, emptyList())
}

@Composable
private fun PlayerTransportBar(
    isPlaying: Boolean,
    playButtonFocusRequester: FocusRequester,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playPauseDescription = if (isPlaying) {
        stringResource(R.string.player_pause)
    } else {
        stringResource(R.string.player_play)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.52f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_playback_label),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            PlayerTransportButton(
                label = "\u23EA",
                contentDescription = stringResource(R.string.player_rewind),
                onClick = onSeekBackward
            )
            Surface(
                onClick = onTogglePlayPause,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.8f),
                    focusedContainerColor = Primary
                ),
                modifier = Modifier
                    .size(80.dp)
                    .focusRequester(playButtonFocusRequester)
                    .semantics { contentDescription = playPauseDescription }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (isPlaying) {
                        Text(
                            text = "II",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.player_play),
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }
            PlayerTransportButton(
                label = "\u23E9",
                contentDescription = stringResource(R.string.player_forward),
                onClick = onSeekForward
            )
        }
    }
}

@Composable
private fun PlayerQuickSettingsButton(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Primary.copy(alpha = 0.9f)
        )
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun PlayerTransportButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .size(56.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun PlayerMetaPill(
    text: String,
    accent: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (accent) Primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PlayerQuickActionRows(
    primaryActions: List<PlayerActionSpec>,
    secondaryActions: List<PlayerActionSpec>
) {
    val rows = listOf(primaryActions, secondaryActions).filter { it.isNotEmpty() }
    if (rows.isEmpty()) return

    Spacer(modifier = Modifier.height(14.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index == 1) {
                    PlayerMetaPill(text = stringResource(R.string.player_more_controls))
                }
                row.forEach { action ->
                    PlayerQuickSettingsButton(action.label, action.onClick)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", remainingMinutes, seconds)
    }
}
