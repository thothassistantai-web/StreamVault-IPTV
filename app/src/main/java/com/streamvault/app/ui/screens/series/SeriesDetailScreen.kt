package com.streamvault.app.ui.screens.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.EpisodeRowCard
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series

@Composable
fun SeriesDetailScreen(
    onEpisodeClick: (Episode) -> Unit,
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val series = uiState.series

    if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.series_loading_details), color = AppColors.TextSecondary)
        }
        return
    }

    if (series == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.series_not_found), color = AppColors.Live)
        }
        return
    }

    SeriesDetailContent(
        series = series,
        selectedSeason = uiState.selectedSeason,
        onSeasonSelected = viewModel::selectSeason,
        onEpisodeClick = onEpisodeClick,
        onBack = onBack
    )
}

@Composable
private fun SeriesDetailContent(
    series: Series,
    selectedSeason: Season?,
    onSeasonSelected: (Season) -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        AsyncImage(
            model = rememberCrossfadeImageModel(series.backdropUrl ?: series.posterUrl),
            contentDescription = series.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            AppColors.HeroTop,
                            AppColors.HeroBottom
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Surface.copy(alpha = 0.72f),
                        contentColor = AppColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Outline))
                    )
                ) {
                    Text(stringResource(R.string.series_detail_back))
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(AppColors.SurfaceElevated)
                    ) {
                        AsyncImage(
                            model = rememberCrossfadeImageModel(series.posterUrl ?: series.backdropUrl),
                            contentDescription = series.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(label = stringResource(R.string.nav_series), containerColor = AppColors.BrandMuted)
                            series.rating.takeIf { it > 0f }?.let {
                                StatusPill(label = "RTG ${String.format("%.1f", it)}", containerColor = AppColors.Warning, contentColor = Color.Black)
                            }
                        }
                        Text(
                            text = series.name,
                            style = MaterialTheme.typography.displayMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        ContentMetadataStrip(
                            values = listOf(
                                series.releaseDate.orEmpty(),
                                series.genre.orEmpty(),
                                selectedSeason?.name.orEmpty()
                            )
                        )
                        Text(
                            text = series.plot ?: stringResource(R.string.series_plot_fallback),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.TextSecondary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (series.seasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.series_seasons),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(series.seasons, key = { it.seasonNumber }) { season ->
                                SeasonChip(
                                    season = season,
                                    isSelected = season == selectedSeason,
                                    onClick = { onSeasonSelected(season) }
                                )
                            }
                        }
                    }
                }
            }

            selectedSeason?.let { season ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.series_episodes, season.episodes.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary
                        )
                        Text(
                            text = season.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextTertiary
                        )
                    }
                }
                items(season.episodes, key = { it.id }) { episode ->
                    EpisodeItem(episode = episode, onClick = { onEpisodeClick(episode) })
                }
            }
        }
    }
}

@Composable
fun SeasonChip(
    season: Season,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) AppColors.BrandMuted else AppColors.SurfaceElevated,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AppColors.Brand else AppColors.Outline),
                shape = RoundedCornerShape(999.dp)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(
            text = season.name,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun EpisodeItem(
    episode: Episode,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        EpisodeRowCard(
            episode = episode,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
