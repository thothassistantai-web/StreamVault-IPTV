package com.streamvault.app.ui.screens.player.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.screens.player.PlayerNoticeAction
import com.streamvault.app.ui.screens.player.PlayerNoticeState
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.PrimaryLight
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType

@Composable
fun PlayerNoticeBanner(
    notice: PlayerNoticeState?,
    onDismiss: () -> Unit,
    onAction: (PlayerNoticeAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notice == null) return

    Surface(
        onClick = onDismiss,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xCC5B1F1F),
            focusedContainerColor = Color(0xFFE45757)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            if (notice.actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice.actions.forEach { action ->
                        Surface(
                            onClick = { onAction(action) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                focusedContainerColor = Color.White.copy(alpha = 0.22f)
                            )
                        ) {
                            Text(
                                text = playerNoticeActionLabel(action),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerErrorOverlay(
    playerError: PlayerError?,
    contentType: String,
    hasAlternateStream: Boolean,
    hasLastChannel: Boolean,
    onAction: (PlayerNoticeAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorMessage = when (playerError) {
        is PlayerError.NetworkError -> stringResource(R.string.player_error_network)
        is PlayerError.SourceError -> stringResource(R.string.player_error_source)
        is PlayerError.DecoderError -> stringResource(R.string.player_error_decoder)
        is PlayerError.UnknownError -> playerError.message ?: stringResource(R.string.player_error_unknown)
        null -> stringResource(R.string.player_error_unknown)
    }
    val recoveryActions = buildList {
        add(PlayerNoticeAction.RETRY)
        if (contentType == "LIVE" && hasAlternateStream) add(PlayerNoticeAction.ALTERNATE_STREAM)
        if (contentType == "LIVE" && hasLastChannel) add(PlayerNoticeAction.LAST_CHANNEL)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = ErrorColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                recoveryActions.forEach { action ->
                    Surface(
                        onClick = { onAction(action) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (action == PlayerNoticeAction.RETRY) Primary else Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = if (action == PlayerNoticeAction.RETRY) PrimaryLight else Color.White.copy(alpha = 0.18f)
                        )
                    ) {
                        Text(
                            text = playerNoticeActionLabel(action),
                            color = if (action == PlayerNoticeAction.RETRY) OnBackground else Color.White,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (contentType == "LIVE" && hasLastChannel) {
                    stringResource(R.string.player_error_live_hint)
                } else {
                    stringResource(R.string.player_error_back)
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }
}

@Composable
fun PlayerTrackSelectionDialog(
    trackType: TrackType?,
    audioTracks: List<PlayerTrack>,
    subtitleTracks: List<PlayerTrack>,
    videoTracks: List<PlayerTrack>,
    onDismiss: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectVideo: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit
) {
    if (trackType == null) return

    val tracks = when (trackType) {
        TrackType.AUDIO -> audioTracks
        TrackType.VIDEO -> videoTracks
        TrackType.TEXT -> subtitleTracks
    }
    val title = when (trackType) {
        TrackType.AUDIO -> stringResource(R.string.player_track_audio)
        TrackType.VIDEO -> stringResource(R.string.player_video_quality)
        TrackType.TEXT -> stringResource(R.string.player_track_subs)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 400.dp)
                .background(SurfaceElevated, RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trackType == TrackType.TEXT) {
                    item {
                        TrackSelectionItem(
                            name = stringResource(R.string.player_track_off),
                            isSelected = tracks.none { it.isSelected },
                            onClick = {
                                onSelectSubtitle(null)
                                onDismiss()
                            }
                        )
                    }
                }

                items(tracks, key = { it.id }) { track ->
                    TrackSelectionItem(
                        name = track.name,
                        isSelected = track.isSelected,
                        onClick = {
                            when (trackType) {
                                TrackType.AUDIO -> onSelectAudio(track.id)
                                TrackType.VIDEO -> onSelectVideo(track.id)
                                TrackType.TEXT -> onSelectSubtitle(track.id)
                            }
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else Color.Transparent,
            focusedContainerColor = SurfaceHighlight
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) Primary else Color.White,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Text(
                    text = stringResource(R.string.player_selected),
                    color = Primary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun PlayerResumePrompt(
    title: String,
    onStartOver: () -> Unit,
    onResume: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .background(SurfaceElevated, RoundedCornerShape(12.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.player_resume_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.player_resume_desc, title),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    onClick = onStartOver,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.player_resume_start_over),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                }

                Surface(
                    onClick = onResume,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = PrimaryLight
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.player_resume),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun playerNoticeActionLabel(action: PlayerNoticeAction): String =
    when (action) {
        PlayerNoticeAction.RETRY -> stringResource(R.string.player_retry)
        PlayerNoticeAction.LAST_CHANNEL -> stringResource(R.string.player_last_channel_action)
        PlayerNoticeAction.ALTERNATE_STREAM -> stringResource(R.string.player_try_alternate_stream)
        PlayerNoticeAction.OPEN_GUIDE -> stringResource(R.string.player_open_guide_action)
    }
