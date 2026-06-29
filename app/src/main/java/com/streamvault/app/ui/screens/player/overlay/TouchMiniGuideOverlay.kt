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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.streamvault.domain.model.Program
import java.util.Date

@Composable
fun TouchMiniGuideOverlay(
    visible: Boolean,
    currentProgram: Program?,
    nextProgram: Program?,
    upcomingPrograms: List<Program>,
    panelWidth: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appTimeFormat = LocalAppTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    val laterPrograms = remember(upcomingPrograms, nextProgram, currentProgram) {
        upcomingPrograms.filter { program ->
            program.id != currentProgram?.id && program.id != nextProgram?.id
        }.take(3)
    }

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
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_touch_mini_guide_title),
                        color = TouchPlaybackTheme.VaultGold,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    MiniGuideSection(
                        label = stringResource(R.string.player_touch_on_now),
                        program = currentProgram,
                        timeFormat = timeFormat,
                        highlighted = true,
                    )
                    MiniGuideSection(
                        label = stringResource(R.string.player_touch_up_next),
                        program = nextProgram,
                        timeFormat = timeFormat,
                    )
                    if (laterPrograms.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.player_touch_later),
                            color = TouchPlaybackTheme.VaultGold.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        laterPrograms.forEach { program ->
                            MiniGuideRow(program = program, timeFormat = timeFormat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniGuideSection(
    label: String,
    program: Program?,
    timeFormat: java.text.DateFormat,
    highlighted: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = TouchPlaybackTheme.VaultGold.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        if (program == null) {
            Text(
                text = stringResource(R.string.player_touch_no_program),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            MiniGuideRow(
                program = program,
                timeFormat = timeFormat,
                highlighted = highlighted,
            )
        }
    }
}

@Composable
private fun MiniGuideRow(
    program: Program,
    timeFormat: java.text.DateFormat,
    highlighted: Boolean = false,
) {
    val timeLabel = remember(program.startTime, program.endTime, timeFormat) {
        "${timeFormat.format(Date(program.startTime))} – ${timeFormat.format(Date(program.endTime))}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlighted) TouchPlaybackTheme.VaultGoldMuted
                else Color.White.copy(alpha = 0.04f)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = program.title,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = timeLabel,
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
