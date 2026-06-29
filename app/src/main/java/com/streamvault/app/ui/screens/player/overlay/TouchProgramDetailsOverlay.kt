package com.streamvault.app.ui.screens.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.screens.player.gesture.TouchPlaybackTheme
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createTimeFormat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.util.Date

@Composable
fun TouchProgramDetailsOverlay(
    visible: Boolean,
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    panelWidth: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appTimeFormat = LocalAppTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeIn(),
        exit = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeOut(),
        modifier = modifier,
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, top = 48.dp, bottom = 48.dp)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                TouchPlaybackTheme.GlassDark,
                                Color(0xAA0A0F18),
                            ),
                        ),
                    )
                    .clickable(enabled = false) {}
                    .padding(18.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_touch_program_info_title),
                        color = TouchPlaybackTheme.VaultGold,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (currentProgram == null) {
                        Text(
                            text = stringResource(R.string.player_touch_no_program),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text =                         currentProgram.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                            fontWeight = FontWeight.Bold,
                        )
                        val timeLabel = remember(currentProgram.startTime, currentProgram.endTime, timeFormat) {
                            "${timeFormat.format(Date(currentProgram.startTime))} – ${timeFormat.format(Date(currentProgram.endTime))}"
                        }
                        Text(
                            text = timeLabel,
                            color = TouchPlaybackTheme.VaultGold.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        currentChannel?.let { channel ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (displayChannelNumber > 0) {
                                    ProgramTag(text = stringResource(R.string.player_touch_channel_tag, displayChannelNumber))
                                }
                                ProgramTag(text = channel.name)
                            }
                        }
                        currentProgram.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                maxLines = 12,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramTag(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(TouchPlaybackTheme.VaultGoldMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
