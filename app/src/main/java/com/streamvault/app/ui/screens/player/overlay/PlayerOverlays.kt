package com.streamvault.app.ui.screens.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.screens.player.PlayerDiagnosticsUiState
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.app.ui.design.AppColors.Brand as Primary
import com.streamvault.app.ui.design.AppColors.SurfaceElevated as SurfaceVariant
import com.streamvault.app.ui.design.AppColors.TextSecondary as TextSecondary
import com.streamvault.app.ui.design.AppColors.TextTertiary as OnSurfaceDim
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.player.PlayerStats
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun PlayerOverlayPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        border = Border(
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                AppColors.Focus.copy(alpha = 0.05f)
            )
        ),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = AppColors.Canvas.copy(alpha = 0.38f)
        )
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppColors.BrandMuted.copy(alpha = 0.06f),
                            AppColors.SurfaceElevated.copy(alpha = 0.34f),
                            AppColors.Surface.copy(alpha = 0.28f)
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun PlayerMetaRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = AppColors.TextTertiary,
            modifier = Modifier.weight(0.44f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(0.56f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerOverlaySectionLabel(text: String) {
    Text(
        text = text,
        color = Primary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun ChannelInfoOverlay(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    nextProgram: Program?,
    focusRequester: FocusRequester,
    lastVisitedCategoryName: String?,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit,
    onOpenFullEpg: () -> Unit,
    onOpenLastGroup: () -> Unit,
    currentRecordingStatus: RecordingStatus?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onRestartProgram: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    isPlaying: Boolean,
    currentAspectRatio: String,
    isDiagnosticsEnabled: Boolean,
    onOpenSplitScreen: () -> Unit = {}
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    BackHandler { onDismiss() }

    PlayerOverlayPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentChannel != null) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.SurfaceEmphasis.copy(alpha = 0.46f))
                                .border(1.dp, AppColors.Focus.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!currentChannel.logoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = rememberCrossfadeImageModel(currentChannel.logoUrl),
                                    contentDescription = currentChannel.name,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            } else {
                                Text(
                                    text = currentChannel.name.take(2).uppercase(Locale.getDefault()),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AppColors.TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(
                                label = stringResource(R.string.player_live_channel, displayChannelNumber),
                                containerColor = AppColors.BrandMuted
                            )
                            if (currentChannel != null) {
                                Text(
                                    text = currentChannel.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (currentChannel?.catchUpSupported == true) {
                                StatusPill(
                                    label = stringResource(R.string.player_catchup_badge),
                                    containerColor = AppColors.Live
                                )
                            }
                        }

                        if (currentProgram != null) {
                            Text(
                                text = currentProgram.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = AppColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.player_time_range_minutes,
                                        timeFormat.format(Date(currentProgram.startTime)),
                                        timeFormat.format(Date(currentProgram.endTime)),
                                        currentProgram.durationMinutes
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextSecondary,
                                    maxLines = 1
                                )
                                val now = System.currentTimeMillis()
                                val start = currentProgram.startTime
                                val end = currentProgram.endTime
                                if (start in 1..<end) {
                                    val progress = (now - start).toFloat() / (end - start)
                                    val remainingMin = ((end - now) / 60000).toInt().coerceAtLeast(0)
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(999.dp)),
                                        color = Primary,
                                        trackColor = AppColors.SurfaceEmphasis.copy(alpha = 0.45f)
                                    )
                                    Text(
                                        text = stringResource(R.string.player_minutes_remaining, remainingMin),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnSurfaceDim,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (nextProgram != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_next_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary
                                    )
                                    Text(
                                        text = nextProgram.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = timeFormat.format(Date(nextProgram.startTime)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.TextTertiary
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.player_no_guide_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionButton(
                    icon = stringResource(R.string.player_action_playback),
                    label = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                    onClick = {
                        onOverlayInteracted()
                        onTogglePlayPause()
                    },
                    onInteraction = onOverlayInteracted,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.20f),
                        focusedContainerColor = Primary,
                        pressedContainerColor = Primary.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.focusRequester(focusRequester)
                )
                QuickActionButton(
                    icon = if (currentRecordingStatus == RecordingStatus.RECORDING) {
                        stringResource(R.string.player_stop_recording)
                    } else {
                        stringResource(R.string.player_record)
                    },
                    label = if (currentRecordingStatus == RecordingStatus.RECORDING) {
                        stringResource(R.string.player_stop_recording)
                    } else {
                        stringResource(R.string.player_record)
                    },
                    onClick = {
                        onOverlayInteracted()
                        if (currentRecordingStatus == RecordingStatus.RECORDING) {
                            onStopRecording()
                        } else {
                            onStartRecording()
                        }
                    },
                    onInteraction = onOverlayInteracted
                )
                if (currentChannel?.catchUpSupported == true && currentProgram?.hasArchive == true) {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_restart_short),
                        label = stringResource(R.string.player_restart),
                        onClick = {
                            onOverlayInteracted()
                            onRestartProgram()
                            onDismiss()
                        },
                        onInteraction = onOverlayInteracted
                    )
                }
                QuickActionButton(
                    icon = stringResource(R.string.player_action_view),
                    label = currentAspectRatio,
                    onClick = {
                        onOverlayInteracted()
                        onToggleAspectRatio()
                    },
                    onInteraction = onOverlayInteracted
                )
                if (!lastVisitedCategoryName.isNullOrBlank()) {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_group),
                        label = stringResource(R.string.player_last_group_label, lastVisitedCategoryName),
                        onClick = {
                            onOverlayInteracted()
                            onOpenLastGroup()
                        },
                        onInteraction = onOverlayInteracted
                    )
                }
                QuickActionButton(
                    icon = stringResource(R.string.player_action_guide),
                    label = stringResource(R.string.player_full_epg),
                    onClick = {
                        onOverlayInteracted()
                        onOpenFullEpg()
                    },
                    onInteraction = onOverlayInteracted
                )
                QuickActionButton(
                    icon = stringResource(R.string.player_action_split),
                    label = stringResource(R.string.multiview_nav),
                    onClick = {
                        onOverlayInteracted()
                        onDismiss()
                        onOpenSplitScreen()
                    },
                    onInteraction = onOverlayInteracted
                )
                QuickActionButton(
                    icon = stringResource(R.string.player_action_diagnostics),
                    label = stringResource(R.string.player_stats),
                    onClick = {
                        onOverlayInteracted()
                        onToggleDiagnostics()
                    },
                    onInteraction = onOverlayInteracted
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    colors: androidx.tv.material3.ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = AppColors.SurfaceEmphasis,
        focusedContainerColor = Primary.copy(alpha = 0.85f)
    ),
    onInteraction: () -> Unit = {},
    onClick: () -> Unit
) {
    Surface(
        onClick = {
            onInteraction()
            onClick()
        },
        modifier = modifier
            .widthIn(min = 92.dp, max = 176.dp)
            .onFocusChanged {
                if (it.isFocused) onInteraction()
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = colors,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                },
                label = "iconToggle"
            ) { targetIcon ->
                Text(
                    text = targetIcon.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.BrandStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChannelListOverlay(
    channels: List<Channel>,
    recentChannels: List<Channel>,
    currentChannelId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    preferredFocusedChannelId: Long? = null,
    onFocusedChannelChange: (Long) -> Unit = {},
    lastVisitedCategoryName: String? = null,
    onOpenLastGroup: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onSelectChannel: (Long) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(channels, currentChannelId) {
        channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    }
    val preferredIndex = remember(channels, currentChannelId, preferredFocusedChannelId) {
        val focused = preferredFocusedChannelId?.let { channelId ->
            channels.indexOfFirst { it.id == channelId }.takeIf { it >= 0 }
        }
        focused ?: currentIndex
    }
    val canScrollUp by remember { derivedStateOf { listState.canScrollBackward } }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val scope = rememberCoroutineScope()
    // Count header items before the channels list so we can map channel index → lazy index
    val headerItemCount = remember(lastVisitedCategoryName, recentChannels) {
        var count = 2 // title row + channel list hint (always present)
        if (!lastVisitedCategoryName.isNullOrBlank()) count++ // last group hint
        if (recentChannels.isNotEmpty()) count++ // recent channels row
        count
    }

    LaunchedEffect(channels, preferredIndex) {
        if (channels.isNotEmpty()) {
            listState.scrollToItem(preferredIndex)
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .width(500.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(20.dp)
        ) {
            PlayerOverlayPanel(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.player_channel_list_title, channels.size),
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary
                            )
                            if (!lastVisitedCategoryName.isNullOrBlank()) {
                                Surface(
                                    onClick = {
                                        onOverlayInteracted()
                                        onOpenLastGroup()
                                    },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = AppColors.SurfaceEmphasis,
                                        focusedContainerColor = Primary
                                    ),
                                    modifier = Modifier.onFocusChanged {
                                        if (it.isFocused) onOverlayInteracted()
                                    }
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_last_group_label, lastVisitedCategoryName),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (!lastVisitedCategoryName.isNullOrBlank()) {
                        item {
                            Text(
                                text = stringResource(R.string.player_last_group_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.player_channel_list_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (recentChannels.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.player_recent_channels),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = OnSurfaceDim,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    itemsIndexed(recentChannels, key = { _, channel -> channel.id }) { index, channel ->
                                        Surface(
                                            onClick = {
                                                onOverlayInteracted()
                                                onSelectChannel(channel.id)
                                                onDismiss()
                                            },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = AppColors.SurfaceEmphasis,
                                                focusedContainerColor = Primary
                                            ),
                                            modifier = Modifier.onFocusChanged {
                                                if (it.isFocused) onOverlayInteracted()
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val recentNumber = (index + 1).toString().padStart(2, '0')
                                                Text(
                                                    text = recentNumber,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.White.copy(alpha = 0.75f)
                                                )
                                                Text(
                                                    text = channel.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(channels.size) { index ->
                        val channel = channels[index]
                        val isSelected = channel.id == currentChannelId
                        val shouldRequestFocus = preferredFocusedChannelId?.let { it == channel.id } ?: isSelected
                        val channelNumber = index + 1
                        var isFocused by remember { mutableStateOf(false) }
                        val bgColor = when {
                            isFocused -> Primary
                            isSelected -> Primary.copy(alpha = 0.20f)
                            else -> AppColors.Surface.copy(alpha = 0.68f)
                        }

                        Surface(
                            onClick = {
                                onSelectChannel(channel.id)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                    if (focusState.isFocused) {
                                        onOverlayInteracted()
                                        onFocusedChannelChange(channel.id)
                                        // Scroll-ahead: when focused item is at the viewport
                                        // edge, instantly shift the window by 1 so the next
                                        // item is already visible before the next D-pad press.
                                        scope.launch {
                                            val visible = listState.layoutInfo.visibleItemsInfo
                                            if (visible.isEmpty()) return@launch
                                            val myLazyIndex = headerItemCount + index
                                            when {
                                                myLazyIndex >= visible.last().index && index < channels.size - 1 -> {
                                                    listState.scrollToItem(listState.firstVisibleItemIndex + 1)
                                                }
                                                myLazyIndex <= visible.first().index && index > 0 -> {
                                                    val newFirst = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                                    listState.scrollToItem(newFirst)
                                                }
                                            }
                                        }
                                    }
                                }
                                .then(
                                    if (shouldRequestFocus) Modifier.focusRequester(overlayFocusRequester)
                                    else Modifier
                                ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = bgColor,
                                focusedContainerColor = bgColor
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (isSelected) {
                                    StatusPill(
                                        label = stringResource(R.string.player_channel_selected),
                                        containerColor = AppColors.BrandMuted
                                    )
                                } else {
                                    Spacer(Modifier.width(42.dp))
                                }
                                Text(
                                    text = channelNumber.toString().padStart(2, '0'),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (channel.catchUpSupported) {
                                    StatusPill(
                                        label = stringResource(R.string.player_archive_badge),
                                        containerColor = AppColors.Warning,
                                        contentColor = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Up arrow — fades in when there are channels scrolled above the visible area
            AnimatedVisibility(
                visible = canScrollUp,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(AppColors.Canvas.copy(alpha = 0.9f), Color.Transparent)
                            ),
                            RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = "\u25b2",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Down arrow — fades in when there are channels below the visible area
            AnimatedVisibility(
                visible = canScrollDown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, AppColors.Canvas.copy(alpha = 0.9f))
                            ),
                            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
                        ),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = "\u25bc",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Vertical categories tab — fixed to the left edge, always visible
            Surface(
                onClick = {
                    onOverlayInteracted()
                    onOpenCategories()
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-28).dp)
                    .onFocusChanged { if (it.isFocused) onOverlayInteracted() },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.92f),
                    focusedContainerColor = Primary
                )
            ) {
                Column(
                    modifier = Modifier
                        .width(28.dp)
                        .height(96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "\u25c4",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
fun EpgOverlay(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    nextProgram: Program?,
    upcomingPrograms: List<Program>,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    preferredFocusedProgramToken: Long? = null,
    onFocusedProgramChange: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onPlayCatchUp: (Program) -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val listState = rememberLazyListState()
    val filteredUpcoming = remember(upcomingPrograms, currentProgram, nextProgram) {
        upcomingPrograms.filter { it.id != currentProgram?.id && it.id != nextProgram?.id }
    }
    val displayPrograms = remember(filteredUpcoming, nextProgram) {
        if (nextProgram != null) listOf(nextProgram) + filteredUpcoming else filteredUpcoming
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        PlayerOverlayPanel(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(520.dp)
                .padding(24.dp)
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.epg_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentChannel != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.channel_number_name_format, displayChannelNumber, currentChannel.name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (currentChannel.catchUpSupported) {
                            Spacer(Modifier.height(8.dp))
                            StatusPill(
                                label = stringResource(R.string.epg_catchup_available, currentChannel.catchUpDays),
                                containerColor = AppColors.BrandMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.player_epg_overlay_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }

                item {
                    androidx.compose.material3.HorizontalDivider(color = SurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.epg_now_playing),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (currentProgram != null) {
                        Text(
                            currentProgram.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.time_range_format, timeFormat.format(Date(currentProgram.startTime)), timeFormat.format(Date(currentProgram.endTime))),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.label_duration_min, currentProgram.durationMinutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                            if (currentProgram.lang.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = currentProgram.lang.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val now = System.currentTimeMillis()
                        val start = currentProgram.startTime
                        val end = currentProgram.endTime
                        if (start in 1..<end) {
                            val progress = (now - start).toFloat() / (end - start)
                            val remainingMin = ((end - now) / 60000).toInt().coerceAtLeast(0)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = Primary,
                                trackColor = AppColors.SurfaceEmphasis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.player_minutes_remaining, remainingMin),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        if (!currentProgram.description.isNullOrEmpty()) {
                            val description = currentProgram.description.orEmpty()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(stringResource(R.string.epg_no_info), color = OnSurfaceDim)
                    }
                }

                if (upcomingPrograms.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.HorizontalDivider(color = SurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.epg_upcoming_schedule),
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(displayPrograms.size) { index ->
                        val program = displayPrograms[index]
                        val isNext = index == 0 && nextProgram != null
                        val focusToken = if (program.id > 0) program.id else program.startTime
                        val shouldRequestFocus = preferredFocusedProgramToken?.let { it == focusToken } ?: (index == 0)

                        Surface(
                            onClick = {
                                if (program.hasArchive || currentChannel?.catchUpSupported == true) {
                                    onOverlayInteracted()
                                    onPlayCatchUp(program)
                                }
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isNext) Primary.copy(alpha = 0.08f) else Color.Transparent,
                                focusedContainerColor = Primary.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (shouldRequestFocus) Modifier.focusRequester(overlayFocusRequester)
                                    else Modifier
                                )
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        onOverlayInteracted()
                                        onFocusedProgramChange(focusToken)
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (isNext) {
                                    Text(
                                        text = stringResource(R.string.epg_up_next),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = program.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isNext) Color.White else Color.White.copy(alpha = 0.8f),
                                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Row {
                                    Text(
                                        text = stringResource(R.string.time_range_format, timeFormat.format(Date(program.startTime)), timeFormat.format(Date(program.endTime))),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.label_duration_min, program.durationMinutes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim
                                    )
                                    if (program.hasArchive) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(
                                            label = stringResource(R.string.player_archive_badge),
                                            containerColor = AppColors.Warning,
                                            contentColor = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsOverlay(
    stats: PlayerStats,
    diagnostics: PlayerDiagnosticsUiState,
    modifier: Modifier = Modifier
) {
    PlayerOverlayPanel(modifier = modifier.width(320.dp)) {
        Column(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.player_diagnostics_title),
                color = Primary,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Bold
            )
            PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_source))
            if (diagnostics.providerName.isNotBlank()) {
                PlayerMetaRow(stringResource(R.string.player_diagnostics_provider), diagnostics.providerName)
            }
            if (diagnostics.providerSourceLabel.isNotBlank()) {
                PlayerMetaRow(stringResource(R.string.player_diagnostics_source), diagnostics.providerSourceLabel)
            }
            PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_playback))
            PlayerMetaRow(stringResource(R.string.player_diagnostics_decoder), diagnostics.decoderMode.name)
            PlayerMetaRow(stringResource(R.string.player_diagnostics_stream_class), diagnostics.streamClassLabel)
            PlayerMetaRow(stringResource(R.string.player_diagnostics_playback_state), diagnostics.playbackStateLabel)
            if (diagnostics.archiveSupportLabel.isNotBlank()) {
                PlayerMetaRow(stringResource(R.string.player_diagnostics_archive), diagnostics.archiveSupportLabel)
            }
            PlayerMetaRow(stringResource(R.string.player_diagnostics_alternates), diagnostics.alternativeStreamCount.toString())
            if (diagnostics.channelErrorCount > 0) {
                PlayerMetaRow(stringResource(R.string.player_diagnostics_channel_errors), diagnostics.channelErrorCount.toString())
            }
            PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_video))
            PlayerMetaRow(stringResource(R.string.player_diagnostics_resolution), "${stats.width}x${stats.height}")
            PlayerMetaRow(stringResource(R.string.player_diagnostics_video_codec), stats.videoCodec)
            PlayerMetaRow(stringResource(R.string.player_diagnostics_video_bitrate), "${stats.videoBitrate / 1000} kbps")
            PlayerMetaRow(stringResource(R.string.player_diagnostics_dropped_frames), stats.droppedFrames.toString())
            PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_audio))
            PlayerMetaRow(stringResource(R.string.player_diagnostics_audio_codec), stats.audioCodec)
            PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_recovery))
            diagnostics.lastFailureReason?.let { reason ->
                PlayerMetaRow(stringResource(R.string.player_diagnostics_last_failure), reason, maxLines = 3)
            }
            if (diagnostics.recentRecoveryActions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.player_diagnostics_recovery_actions),
                    color = Primary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Bold
                )
                diagnostics.recentRecoveryActions.forEach { action ->
                    Text(
                        text = action,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (diagnostics.troubleshootingHints.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.player_diagnostics_troubleshooting),
                    color = Primary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Bold
                )
                diagnostics.troubleshootingHints.forEach { hint ->
                    Text(
                        text = hint,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryListOverlay(
    categories: List<com.streamvault.domain.model.Category>,
    currentCategoryId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    onSelectCategory: (com.streamvault.domain.model.Category) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(categories, currentCategoryId) {
        categories.indexOfFirst { it.id == currentCategoryId }.coerceAtLeast(0)
    }

    LaunchedEffect(categories, currentIndex) {
        if (categories.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .width(500.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(20.dp)
        ) {
            PlayerOverlayPanel(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.label_categories),
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                        )
                    }
                    items(categories.size) { index ->
                        val category = categories[index]
                        val isSelected = category.id == currentCategoryId
                        var isFocused by remember { mutableStateOf(false) }
                        val shouldRequestFocus = isSelected
                        val bgColor = when {
                            isFocused -> Primary
                            isSelected -> Primary.copy(alpha = 0.20f)
                            else -> AppColors.Surface.copy(alpha = 0.68f)
                        }

                        Surface(
                            onClick = {
                                onSelectCategory(category)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                    if (focusState.isFocused) onOverlayInteracted()
                                }
                                .then(
                                    if (shouldRequestFocus) Modifier.focusRequester(overlayFocusRequester)
                                    else Modifier
                                ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = bgColor,
                                focusedContainerColor = bgColor
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Text(
                                        text = "●",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                } else if (category.count > 0) {
                                    Text(
                                        text = category.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.45f),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
