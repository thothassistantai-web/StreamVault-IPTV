package com.streamvault.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.ui.components.shell.MoviePosterCard
import com.streamvault.app.ui.components.shell.SeriesPosterCard
import com.streamvault.app.ui.theme.AccentAmber
import com.streamvault.app.ui.theme.AccentCyan
import com.streamvault.app.ui.theme.AccentRed
import com.streamvault.app.ui.theme.CardBackground
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.GradientOverlayBottom
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.app.ui.theme.TextTertiary
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.app.ui.design.FocusSpec

@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 160.dp,
    height: Dp = 240.dp,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) {
            if (isReorderMode && !isDragging) 1f else FocusSpec.FocusedScale
        } else {
            if (isDragging) FocusSpec.FocusedScale else 1f
        },
        animationSpec = tween(durationMillis = 160),
        label = "cardScale"
    )

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = FocusSpec.PressedScale
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBackground,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = if (isDragging) 4.dp else FocusSpec.CardBorderWidth,
                    color = if (isDragging) AccentAmber else FocusBorder
                ),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(isFocused)
        }
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false
) {
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = 220.dp,
        height = 124.dp,
        isReorderMode = isReorderMode,
        isDragging = isDragging
    ) { isFocused ->
        if (!channel.logoUrl.isNullOrBlank() && !isLocked) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, GradientOverlayBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (isLocked) stringResource(R.string.card_locked_channel) else channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isFocused) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!isLocked) {
                channel.currentProgram?.let { program ->
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val now = System.currentTimeMillis()
                    val totalDuration = program.endTime - program.startTime
                    val elapsed = now - program.startTime
                    val progress = if (totalDuration > 0) elapsed.toFloat() / totalDuration else 0f

                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color = AccentCyan,
                        trackColor = SurfaceHighlight
                    )
                }
            }
        }

        if (!isLocked) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (channel.isFavorite) {
                    StatusBadge(label = stringResource(R.string.badge_fav), containerColor = AccentAmber, contentColor = Color.Black)
                }
                if (channel.errorCount > 0) {
                    StatusBadge(label = stringResource(R.string.badge_error), containerColor = AccentRed)
                }
                if (channel.catchUpSupported) {
                    StatusBadge(label = stringResource(R.string.badge_catch_up), containerColor = Primary)
                }
                StatusBadge(
                    label = stringResource(R.string.card_live_badge),
                    containerColor = AccentRed
                )
            }
        }

        if (isLocked) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatusBadge(label = stringResource(R.string.home_locked_short), containerColor = SurfaceHighlight)
            }
        }
    }
}

@Composable
fun MovieCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    watchProgress: Float = 0f,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false
) {
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = 136.dp,
        height = 204.dp,
        isReorderMode = isReorderMode,
        isDragging = isDragging
    ) {
        if (!isLocked) {
            MoviePosterCard(
                movie = movie,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                StatusBadge(
                    label = if (isLocked) stringResource(R.string.home_locked_short) else stringResource(R.string.badge_movie),
                    containerColor = SurfaceHighlight
                )
            }
        }

        if (watchProgress > 0f && !isLocked) {
            LinearProgressIndicator(
                progress = { watchProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Primary,
                trackColor = Color.Transparent
            )
        }

        if (!isLocked) {
            if (movie.rating > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_rating, String.format("%.1f", movie.rating)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentAmber
                    )
                }
            }

            if (movie.isFavorite) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StatusBadge(label = stringResource(R.string.badge_fav), containerColor = AccentRed)
                }
            }
        }
    }
}

@Composable
fun SeriesCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    watchProgress: Float = 0f,
    subtitle: String? = null,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false
) {
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = 136.dp,
        height = 204.dp,
        isReorderMode = isReorderMode,
        isDragging = isDragging
    ) {
        if (!isLocked) {
            SeriesPosterCard(
                series = series,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                StatusBadge(
                    label = if (isLocked) stringResource(R.string.home_locked_short) else stringResource(R.string.badge_series),
                    containerColor = SurfaceHighlight
                )
            }
        }

        if (watchProgress > 0f && !isLocked) {
            LinearProgressIndicator(
                progress = { watchProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Primary,
                trackColor = Color.Transparent
            )
        }

        if (!isLocked) {
            if (series.rating > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_rating, String.format("%.1f", series.rating)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentAmber
                    )
                }
            }

            if (series.isFavorite) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    StatusBadge(label = stringResource(R.string.badge_fav), containerColor = AccentRed)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    containerColor: Color,
    contentColor: Color = Color.White
) {
    Box(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}
