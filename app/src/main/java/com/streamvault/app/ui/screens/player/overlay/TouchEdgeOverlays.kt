package com.streamvault.app.ui.screens.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.screens.player.gesture.TouchEdgePanel
import com.streamvault.app.ui.screens.player.gesture.TouchPlaybackTheme

data class TouchEdgeNavItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun TouchEdgeOverlayHost(
    panel: TouchEdgePanel,
    leftNavItems: List<TouchEdgeNavItem>,
    rightMediaItems: List<TouchEdgeNavItem>,
    currentTimeLabel: String,
    isPlaying: Boolean,
    programTitle: String?,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = panel != TouchEdgePanel.NONE,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        modifier = modifier,
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        ) {
            when (panel) {
                TouchEdgePanel.LEFT_NAV -> EdgeSidePanel(
                    items = leftNavItems,
                    alignStart = true,
                    title = stringResource(R.string.player_touch_edge_nav_title),
                    onDismiss = onDismiss,
                )
                TouchEdgePanel.RIGHT_MEDIA -> EdgeSidePanel(
                    items = rightMediaItems,
                    alignStart = false,
                    title = stringResource(R.string.player_touch_edge_media_title),
                    onDismiss = onDismiss,
                )
                TouchEdgePanel.TOP_STATUS -> Box(
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    EdgeTopStrip(currentTimeLabel = currentTimeLabel)
                }
                TouchEdgePanel.BOTTOM_PLAYBACK -> Box(
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    EdgeBottomStrip(
                        programTitle = programTitle,
                        isPlaying = isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekBackward = onSeekBackward,
                        onSeekForward = onSeekForward,
                    )
                }
                TouchEdgePanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun EdgeSidePanel(
    items: List<TouchEdgeNavItem>,
    alignStart: Boolean,
    title: String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 48.dp)
                .widthIn(min = 200.dp, max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(TouchPlaybackTheme.GlassDark)
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = TouchPlaybackTheme.VaultGold,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            items.forEach { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchPlaybackTheme.TouchTargetMin)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = item.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EdgeTopStrip(currentTimeLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TouchPlaybackTheme.GlassDark, Color.Transparent),
                ),
            )
            .clickable(enabled = false) {}
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_touch_edge_status_title),
            color = TouchPlaybackTheme.VaultGold,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = currentTimeLabel,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EdgeBottomStrip(
    programTitle: String?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, TouchPlaybackTheme.GlassDark),
                ),
            )
            .clickable(enabled = false) {}
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = programTitle ?: stringResource(R.string.player_playback_label),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdgeTransportButton(label = "−10", onClick = onSeekBackward)
            EdgeTransportButton(
                label = if (isPlaying) "❚❚" else "▶",
                onClick = onTogglePlayPause,
            )
            EdgeTransportButton(label = "+10", onClick = onSeekForward)
        }
    }
}

@Composable
private fun EdgeTransportButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = TouchPlaybackTheme.TouchTargetMin)
            .widthIn(min = TouchPlaybackTheme.TouchTargetMin)
            .clip(RoundedCornerShape(999.dp))
            .background(TouchPlaybackTheme.VaultGoldMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TouchPlaybackTheme.VaultGold,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
