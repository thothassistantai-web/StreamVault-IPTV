package com.streamvault.app.ui.screens.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.ChannelRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun FullEpgScreen(
    currentRoute: String,
    initialCategoryId: Long? = null,
    initialAnchorTime: Long? = null,
    initialFavoritesOnly: Boolean = false,
    onPlayChannel: (Channel, String) -> Unit,
    onPlayArchive: (Channel, Program, String) -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: EpgViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedProgram by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var showAdvancedOptions by rememberSaveable { mutableStateOf(false) }
    val now = rememberGuideNow()
    val returnRoute = remember(uiState.selectedCategoryId, uiState.guideAnchorTime, uiState.showFavoritesOnly) {
        Routes.epg(
            categoryId = uiState.selectedCategoryId.takeIf { it != ChannelRepository.ALL_CHANNELS_ID },
            anchorTime = uiState.guideAnchorTime,
            favoritesOnly = uiState.showFavoritesOnly
        )
    }

    LaunchedEffect(initialCategoryId, initialAnchorTime, initialFavoritesOnly) {
        viewModel.applyNavigationContext(
            categoryId = initialCategoryId,
            anchorTime = initialAnchorTime,
            favoritesOnly = initialFavoritesOnly
        )
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.nav_epg),
        subtitle = stringResource(R.string.guide_shell_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true,
        showScreenHeader = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (uiState.error == null) {
                GuideFilterRow(
                    categories = uiState.categories,
                    selectedCategoryId = uiState.selectedCategoryId,
                    onCategorySelected = viewModel::selectCategory
                )

                GuideTimeControlsRow(
                    onJumpToPreviousDay = viewModel::jumpToPreviousDay,
                    onPageBackward = viewModel::pageBackward,
                    onJumpBackwardHalfHour = viewModel::jumpBackwardHalfHour,
                    onJumpBackward = viewModel::jumpBackward,
                    onJumpToNow = viewModel::jumpToNow,
                    onJumpForwardHalfHour = viewModel::jumpForwardHalfHour,
                    onJumpForward = viewModel::jumpForward,
                    onPageForward = viewModel::pageForward,
                    onJumpToPrimeTime = viewModel::jumpToPrimeTime,
                    onJumpToTomorrow = viewModel::jumpToTomorrow,
                    onJumpToNextDay = viewModel::jumpToNextDay
                )

                GuideDayRow(
                    selectedDayStart = startOfDay(uiState.guideWindowStart + EpgViewModel.LOOKBACK_MS),
                    onDaySelected = viewModel::jumpToDay
                )

                GuideOptionsToggleRow(
                    expanded = showAdvancedOptions,
                    onToggle = { showAdvancedOptions = !showAdvancedOptions }
                )

                if (showAdvancedOptions) {
                    GuideModeRow(
                        selectedMode = uiState.selectedChannelMode,
                        onModeSelected = viewModel::selectChannelMode
                    )

                    GuideDensityRow(
                        selectedDensity = uiState.selectedDensity,
                        onDensitySelected = viewModel::selectDensity
                    )

                    GuideViewOptionsRow(
                        showScheduledOnly = uiState.showScheduledOnly,
                        onToggleScheduledOnly = viewModel::toggleScheduledOnly
                    )

                    GuideFavoritesRow(
                        showFavoritesOnly = uiState.showFavoritesOnly,
                        onToggleFavoritesOnly = viewModel::toggleFavoritesOnly
                    )
                }

                GuideSummaryCard(uiState = uiState)
            }

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.epg_loading), color = OnBackground)
                    }
                }

                uiState.error != null -> {
                    GuideMessageState(
                        title = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> stringResource(R.string.epg_no_provider)
                            else -> stringResource(R.string.epg_error)
                        },
                        subtitle = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> null
                            else -> stringResource(R.string.epg_retry_hint)
                        },
                        actionLabel = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else stringResource(R.string.epg_retry),
                        onAction = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else viewModel::refresh
                    )
                }

                uiState.channels.isEmpty() -> {
                    GuideMessageState(
                        title = if (uiState.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID) {
                            if (uiState.showScheduledOnly) {
                                stringResource(R.string.epg_no_scheduled_channels)
                            } else {
                                stringResource(R.string.epg_no_data)
                            }
                        } else {
                            if (uiState.showScheduledOnly) {
                                stringResource(R.string.epg_no_scheduled_channels)
                            } else {
                                stringResource(R.string.epg_no_channels_in_category)
                            }
                        },
                        subtitle = if (uiState.showScheduledOnly) {
                            stringResource(R.string.epg_scheduled_only_hint)
                        } else {
                            stringResource(R.string.epg_filter_hint)
                        },
                        actionLabel = stringResource(R.string.epg_retry),
                        onAction = viewModel::refresh
                    )
                }

                else -> {
                    EpgGrid(
                        channels = uiState.channels,
                        favoriteChannelIds = uiState.favoriteChannelIds,
                        programsByChannel = uiState.programsByChannel,
                        guideWindowStart = uiState.guideWindowStart,
                        guideWindowEnd = uiState.guideWindowEnd,
                        density = uiState.selectedDensity,
                        now = now,
                        onChannelClick = { channel -> onPlayChannel(channel, returnRoute) },
                        onProgramClick = { channel, program -> selectedProgram = channel to program }
                    )
                }
            }
        }
    }

    val dialogState = selectedProgram
    if (dialogState != null) {
        val (channel, program) = dialogState
        val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        val channelPrograms = channel.epgChannelId?.let { uiState.programsByChannel[it].orEmpty() }.orEmpty()
        val surroundingPrograms = remember(channelPrograms, program) {
            val index = channelPrograms.indexOfFirst { it.startTime == program.startTime && it.endTime == program.endTime && it.title == program.title }
            if (index == -1) {
                channelPrograms.take(4)
            } else {
                channelPrograms.subList(max(0, index - 1), minOf(channelPrograms.size, index + 3))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = { selectedProgram = null }, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                modifier = Modifier
                    .width(520.dp)
                    .fillMaxHeight()
                    .padding(vertical = 24.dp, horizontal = 24.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.player_time_range_minutes,
                                format.format(Date(program.startTime)),
                                format.format(Date(program.endTime)),
                                program.durationMinutes
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDim
                        )
                        if (program.description.isNotBlank()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GuideProgramMetadataRow(
                            label = stringResource(R.string.epg_program_channel_label),
                            value = channel.name
                        )
                        uiState.currentProviderName?.let { providerName ->
                            GuideProgramMetadataRow(
                                label = stringResource(R.string.epg_program_provider_label),
                                value = providerName
                            )
                        }
                        if (uiState.providerSourceLabel.isNotBlank()) {
                            GuideProgramMetadataRow(
                                label = stringResource(R.string.epg_program_provider_type_label),
                                value = uiState.providerSourceLabel
                            )
                        }
                        GuideProgramMetadataRow(
                            label = stringResource(R.string.epg_program_language_label),
                            value = program.lang.ifBlank { stringResource(R.string.epg_program_unknown_value) }
                        )
                        GuideProgramMetadataRow(
                            label = stringResource(R.string.epg_program_replay_label),
                            value = when {
                                program.hasArchive -> stringResource(R.string.epg_program_replay_ready)
                                channel.catchUpSupported -> stringResource(R.string.epg_program_replay_partial)
                                else -> stringResource(R.string.epg_program_replay_unavailable)
                            }
                        )
                        if (program.isNowPlaying) {
                            GuideProgramMetadataRow(
                                label = stringResource(R.string.epg_program_progress_label),
                                value = stringResource(
                                    R.string.epg_program_progress_value,
                                    (program.progressPercent() * 100f).roundToInt(),
                                    stringResource(R.string.epg_minutes_remaining, ((program.endTime - now) / 60_000L).coerceAtLeast(0L))
                                )
                            )
                        }
                    }

                    GuideStatusCard(
                        isArchiveReady = program.hasArchive,
                        providerCatchUpSupported = channel.catchUpSupported,
                        isGuideStale = uiState.isGuideStale
                    )

                    if (uiState.providerArchiveSummary.isNotBlank()) {
                        GuideProviderTroubleshootingCard(
                            summary = uiState.providerArchiveSummary,
                            channel = channel,
                            program = program,
                            isGuideStale = uiState.isGuideStale
                        )
                    }

                    if (surroundingPrograms.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.epg_upcoming_schedule),
                                style = MaterialTheme.typography.titleMedium,
                                color = OnSurface
                            )
                            surroundingPrograms.forEach { scheduleProgram ->
                                val isSelectedProgram = scheduleProgram === program ||
                                    (scheduleProgram.startTime == program.startTime &&
                                        scheduleProgram.endTime == program.endTime &&
                                        scheduleProgram.title == program.title)
                                Surface(
                                    onClick = { selectedProgram = channel to scheduleProgram },
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (isSelectedProgram) Primary.copy(alpha = 0.18f) else SurfaceHighlight,
                                        focusedContainerColor = SurfaceHighlight
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = scheduleProgram.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelectedProgram) Primary else OnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${format.format(Date(scheduleProgram.startTime))} - ${format.format(Date(scheduleProgram.endTime))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (program.hasArchive || channel.catchUpSupported) {
                            Button(
                                onClick = {
                                    selectedProgram = null
                                    onPlayArchive(channel, program, returnRoute)
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = Primary,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.epg_watch_archive))
                            }
                        }
                        Button(
                            onClick = {
                                selectedProgram = null
                                onPlayChannel(channel, returnRoute)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.epg_watch_live))
                        }
                        if (uiState.isGuideStale) {
                            Button(
                                onClick = viewModel::refresh,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.colors(
                                    containerColor = SurfaceHighlight,
                                    contentColor = OnSurface
                                )
                            ) {
                                Text(stringResource(R.string.epg_refresh_guide))
                            }
                        }
                        TextButton(onClick = { selectedProgram = null }) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideProgramMetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideStatusCard(
    isArchiveReady: Boolean,
    providerCatchUpSupported: Boolean,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_program_status_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = when {
                    isArchiveReady -> stringResource(R.string.epg_archive_ready_hint)
                    providerCatchUpSupported -> stringResource(R.string.epg_archive_provider_hint)
                    else -> stringResource(R.string.epg_archive_unavailable_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isArchiveReady) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_archive_stale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun GuideProviderTroubleshootingCard(
    summary: String,
    channel: Channel,
    program: Program,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_provider_troubleshooting_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            val channelReason = when {
                !channel.catchUpSupported && !program.hasArchive ->
                    stringResource(R.string.epg_provider_troubleshooting_no_archive)
                channel.catchUpSupported && channel.catchUpSource.isNullOrBlank() ->
                    stringResource(R.string.epg_provider_troubleshooting_missing_template)
                channel.streamId <= 0L ->
                    stringResource(R.string.epg_provider_troubleshooting_missing_stream_id)
                else ->
                    stringResource(R.string.epg_provider_troubleshooting_ready)
            }
            Text(
                text = channelReason,
                style = MaterialTheme.typography.bodySmall,
                color = if (program.hasArchive) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_provider_troubleshooting_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun rememberGuideNow(): Long {
    val currentTime by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return currentTime
}

@Composable
private fun GuideDensityRow(
    selectedDensity: GuideDensity,
    onDensitySelected: (GuideDensity) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_density_label),
        subtitle = stringResource(R.string.epg_density_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideDensity.COMPACT.name,
                label = stringResource(R.string.epg_density_compact),
                supportingText = stringResource(R.string.epg_density_compact_hint)
            ),
            SelectionChip(
                key = GuideDensity.COMFORTABLE.name,
                label = stringResource(R.string.epg_density_comfortable),
                supportingText = stringResource(R.string.epg_density_comfortable_hint)
            ),
            SelectionChip(
                key = GuideDensity.CINEMATIC.name,
                label = stringResource(R.string.epg_density_cinematic),
                supportingText = stringResource(R.string.epg_density_cinematic_hint)
            )
        ),
        selectedKey = selectedDensity.name,
        onChipSelected = { key ->
            GuideDensity.entries.firstOrNull { it.name == key }?.let(onDensitySelected)
        }
    )
}

@Composable
private fun GuideModeRow(
    selectedMode: GuideChannelMode,
    onModeSelected: (GuideChannelMode) -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_mode_label),
        subtitle = stringResource(R.string.epg_mode_subtitle),
        chips = listOf(
            SelectionChip(
                key = GuideChannelMode.ALL.name,
                label = stringResource(R.string.epg_mode_all),
                supportingText = stringResource(R.string.epg_mode_all_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ANCHORED.name,
                label = stringResource(R.string.epg_mode_anchored),
                supportingText = stringResource(R.string.epg_mode_anchored_hint)
            ),
            SelectionChip(
                key = GuideChannelMode.ARCHIVE_READY.name,
                label = stringResource(R.string.epg_mode_archive),
                supportingText = stringResource(R.string.epg_mode_archive_hint)
            )
        ),
        selectedKey = selectedMode.name,
        onChipSelected = { key ->
            GuideChannelMode.entries
                .firstOrNull { it.name == key }
                ?.let(onModeSelected)
        }
    )
}

@Composable
private fun GuideFilterRow(
    categories: List<Category>,
    selectedCategoryId: Long,
    onCategorySelected: (Long) -> Unit
) {
    if (categories.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(categories, key = { it.id }) { category ->
                Surface(
                    onClick = { onCategorySelected(category.id) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (category.id == selectedCategoryId) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (category.id == selectedCategoryId) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Text(
                        text = category.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTimeControlsRow(
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_time_controls),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_previous_day),
                    onClick = onJumpToPreviousDay
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_page_back),
                    onClick = onPageBackward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_back_half_hour),
                    onClick = onJumpBackwardHalfHour
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_back),
                    onClick = onJumpBackward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_now),
                    onClick = onJumpToNow
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_forward_half_hour),
                    onClick = onJumpForwardHalfHour
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_forward),
                    onClick = onJumpForward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_page_forward),
                    onClick = onPageForward
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_prime_time),
                    onClick = onJumpToPrimeTime
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_jump_tomorrow),
                    onClick = onJumpToTomorrow
                )
            }
            item {
                GuideShortcutChip(
                    label = stringResource(R.string.epg_next_day),
                    onClick = onJumpToNextDay
                )
            }
        }
    }
}

@Composable
private fun GuideDayRow(
    selectedDayStart: Long,
    onDaySelected: (Long) -> Unit
) {
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val dayAnchors = remember(selectedDayStart) {
        (-1L..3L).map { offset ->
            selectedDayStart + (offset * EpgViewModel.DAY_SHIFT_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_day_selector_label),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(dayAnchors, key = { it }) { dayStart ->
                val isSelected = dayStart == selectedDayStart
                Surface(
                    onClick = { onDaySelected(dayStart) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (isSelected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = dayRelativeLabel(dayStart),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = dayFormat.format(Date(dayStart)),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideViewOptionsRow(
    showScheduledOnly: Boolean,
    onToggleScheduledOnly: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.epg_view_options_label),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(
                    onClick = onToggleScheduledOnly,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (showScheduledOnly) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (showScheduledOnly) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (showScheduledOnly) {
                                    R.string.epg_view_scheduled_only_on
                                } else {
                                    R.string.epg_view_scheduled_only_off
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.epg_view_scheduled_only_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideFavoritesRow(
    showFavoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit
) {
    SelectionChipRow(
        title = stringResource(R.string.epg_favorites_filter_title),
        subtitle = stringResource(R.string.epg_favorites_filter_subtitle),
        chips = listOf(
            SelectionChip(
                key = "all",
                label = stringResource(R.string.epg_favorites_filter_all),
                supportingText = stringResource(R.string.epg_favorites_filter_all_hint)
            ),
            SelectionChip(
                key = "favorites",
                label = stringResource(R.string.epg_favorites_filter_favorites),
                supportingText = stringResource(R.string.epg_favorites_filter_favorites_hint)
            )
        ),
        selectedKey = if (showFavoritesOnly) "favorites" else "all",
        onChipSelected = { key ->
            val shouldShowFavorites = key == "favorites"
            if (shouldShowFavorites != showFavoritesOnly) {
                onToggleFavoritesOnly()
            }
        },
        contentPadding = PaddingValues(horizontal = 24.dp)
    )
}

@Composable
private fun GuideShortcutChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun GuideOptionsToggleRow(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Surface(
            onClick = onToggle,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SurfaceElevated,
                focusedContainerColor = SurfaceHighlight,
                contentColor = OnSurface,
                focusedContentColor = OnSurface
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(999.dp)
                )
            )
        ) {
            Text(
                text = stringResource(if (expanded) R.string.epg_hide_options else R.string.epg_show_options),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun GuideSummaryCard(uiState: EpgUiState) {
    val lastUpdatedLabel = uiState.lastUpdatedAt?.let { timestamp ->
        val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
        if (minutes <= 1) {
            stringResource(R.string.epg_updated_now)
        } else {
            stringResource(R.string.epg_updated_minutes_ago, minutes)
        }
    }
    val windowFormat = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val windowLabel = stringResource(
        R.string.epg_window_label,
        windowFormat.format(Date(uiState.guideWindowStart)),
        windowFormat.format(Date(uiState.guideWindowEnd))
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.epg_showing_channels,
                    uiState.channels.size,
                    uiState.totalChannelCount
                ),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = stringResource(
                    R.string.epg_schedule_summary,
                    uiState.channelsWithSchedule,
                    uiState.channels.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Text(
                text = windowLabel,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            if (lastUpdatedLabel != null) {
                Text(
                    text = lastUpdatedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            if (uiState.isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_stale_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun GuideMessageState(
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.colors(
                        containerColor = Primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun EpgGrid(
    channels: List<Channel>,
    favoriteChannelIds: Set<Long>,
    programsByChannel: Map<String, List<Program>>,
    guideWindowStart: Long,
    guideWindowEnd: Long,
    density: GuideDensity,
    now: Long,
    onChannelClick: (Channel) -> Unit,
    onProgramClick: (Channel, Program) -> Unit
) {
    val (channelRailWidth, timelineGap, rowHeight) = when (density) {
        GuideDensity.COMPACT -> Triple(180.dp, 12.dp, 76.dp)
        GuideDensity.COMFORTABLE -> Triple(220.dp, 16.dp, 92.dp)
        GuideDensity.CINEMATIC -> Triple(260.dp, 20.dp, 108.dp)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        val timelineWidth = (maxWidth - channelRailWidth - timelineGap).coerceAtLeast(720.dp)
        val markerStepMs = EpgViewModel.HALF_HOUR_SHIFT_MS

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "timeline_header") {
                GuideTimelineHeader(
                    windowStart = guideWindowStart,
                    windowEnd = guideWindowEnd,
                    now = now,
                    channelRailWidth = channelRailWidth,
                    timelineGap = timelineGap,
                    timelineWidth = timelineWidth,
                    markerStepMs = markerStepMs
                )
            }
            items(channels, key = { it.id }) { channel ->
                val programs = channel.epgChannelId?.let { programsByChannel[it] } ?: emptyList()
                EpgRow(
                    channel = channel,
                    isFavorite = channel.id in favoriteChannelIds,
                    programs = programs,
                    now = now,
                    windowStart = guideWindowStart,
                    windowEnd = guideWindowEnd,
                    channelRailWidth = channelRailWidth,
                    timelineGap = timelineGap,
                    timelineWidth = timelineWidth,
                    rowHeight = rowHeight,
                    markerStepMs = markerStepMs,
                    onChannelClick = { onChannelClick(channel) },
                    onProgramClick = { program -> onProgramClick(channel, program) }
                )
            }
        }
    }
}

@Composable
private fun GuideTimelineHeader(
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineWidth: Dp,
    markerStepMs: Long
) {
    val hourFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val clampedNow = now.coerceIn(windowStart, windowEnd)
    val elapsedRatio = ((clampedNow - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val markerLabel = if (now in windowStart..windowEnd) {
        stringResource(R.string.epg_now_marker, hourFormat.format(Date(now)))
    } else {
        stringResource(R.string.epg_outside_window)
    }
    val hourMarkers = buildList {
        var marker = windowStart
        while (marker <= windowEnd) {
            add(marker)
            marker += markerStepMs
        }
        if (lastOrNull() != windowEnd) {
            add(windowEnd)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Spacer(modifier = Modifier.width(channelRailWidth + timelineGap))
        Column(
            modifier = Modifier.width(timelineWidth),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.epg_timeline_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim
                )
                Text(
                    text = stringResource(
                        R.string.epg_timeline_range_label,
                        formatWindowDuration(totalDuration)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                hourMarkers.forEach { marker ->
                    val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    val markerOffset = timelineWidth * markerRatio
                    Column(
                        modifier = Modifier.padding(start = markerOffset),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = hourFormat.format(Date(marker)),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(Color.White.copy(alpha = 0.16f))
                        )
                    }
                }
                if (now in windowStart..windowEnd) {
                    Box(
                        modifier = Modifier
                            .padding(start = timelineWidth * elapsedRatio)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Primary)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = markerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (now in windowStart..windowEnd) Primary else OnSurfaceDim
                )
            }
            LinearProgressIndicator(
                progress = { elapsedRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Primary,
                trackColor = SurfaceElevated
            )
        }
    }
}

@Composable
fun EpgRow(
    channel: Channel,
    isFavorite: Boolean,
    programs: List<Program>,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    channelRailWidth: Dp,
    timelineGap: Dp,
    timelineWidth: Dp,
    rowHeight: Dp,
    markerStepMs: Long,
    onChannelClick: () -> Unit,
    onProgramClick: (Program) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val currentProgram = programs.firstOrNull { now in it.startTime..it.endTime }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onChannelClick,
            modifier = Modifier
                .width(channelRailWidth)
                .fillMaxHeight()
                .onFocusChanged { isFocused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SurfaceElevated,
                focusedContainerColor = SurfaceHighlight
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(8.dp)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                .fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isFavorite) {
                        Text(
                            text = stringResource(R.string.epg_favorite_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "${channel.number}. ${channel.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isFocused) TextPrimary else OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentProgram?.title ?: stringResource(R.string.epg_no_schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(timelineGap))

        if (programs.isEmpty()) {
            Box(
                modifier = Modifier
                    .width(timelineWidth)
                    .fillMaxHeight()
                    .background(SurfaceElevated, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.epg_no_schedule), color = OnSurfaceDim)
            }
        } else {
            Box(
                modifier = Modifier
                    .width(timelineWidth)
                    .fillMaxHeight()
                    .background(SurfaceElevated, RoundedCornerShape(8.dp))
            ) {
                val markers = remember(windowStart, windowEnd, markerStepMs) {
                    buildList {
                        var marker = windowStart
                        while (marker <= windowEnd) {
                            add(marker)
                            marker += markerStepMs
                        }
                    }
                }
                markers.forEach { marker ->
                    val markerRatio = ((marker - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .padding(start = timelineWidth * markerRatio)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
                if (now in windowStart..windowEnd) {
                    val nowRatio = ((now - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .padding(start = timelineWidth * nowRatio)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Primary)
                    )
                }
                programs.forEach { program ->
                    ProgramItem(
                        program = program,
                        isCurrent = now in program.startTime..program.endTime,
                        now = now,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        timelineWidth = timelineWidth,
                        onClick = { onProgramClick(program) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProgramItem(
    program: Program,
    isCurrent: Boolean,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    timelineWidth: Dp,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startStr = format.format(Date(program.startTime))
    val endStr = format.format(Date(program.endTime))
    val totalDuration = (windowEnd - windowStart).coerceAtLeast(1L)
    val visibleStart = max(program.startTime, windowStart)
    val visibleEnd = max(visibleStart + 1, minOf(program.endTime, windowEnd))
    val startRatio = ((visibleStart - windowStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val widthRatio = ((visibleEnd - visibleStart).toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val itemStart = timelineWidth * startRatio
    val itemWidth = (timelineWidth * widthRatio).coerceAtLeast(96.dp)
    val elapsedRatio = if (program.endTime > program.startTime) {
        ((now - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainingMinutes = ((program.endTime - now) / 60_000L).coerceAtLeast(0L)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(start = itemStart, top = 6.dp, bottom = 6.dp)
            .width(itemWidth)
            .fillMaxHeight()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) Primary.copy(alpha = 0.2f) else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.epg_now_playing),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isFocused) TextPrimary else if (isCurrent) Primary else OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$startStr - $endStr",
                style = MaterialTheme.typography.bodySmall,
                color = if (isFocused) TextSecondary else OnSurfaceDim
            )
            if (program.hasArchive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.player_archive_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    maxLines = 1
                )
            }
            if (isCurrent) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { elapsedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Primary,
                    trackColor = SurfaceHighlight
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.epg_minutes_remaining, remainingMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatWindowDuration(durationMs: Long): String {
    val hours = durationMs / (60 * 60 * 1000L)
    val minutes = (durationMs / (60 * 1000L)) % 60
    return if (minutes == 0L) {
        "${hours}h"
    } else {
        "${hours}h ${minutes}m"
    }
}

private fun startOfDay(timestamp: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

@Composable
private fun dayRelativeLabel(dayStart: Long): String {
    val todayStart = remember { startOfDay(System.currentTimeMillis()) }
    return when (dayStart) {
        todayStart - EpgViewModel.DAY_SHIFT_MS -> stringResource(R.string.epg_day_yesterday)
        todayStart -> stringResource(R.string.epg_day_today)
        todayStart + EpgViewModel.DAY_SHIFT_MS -> stringResource(R.string.epg_day_tomorrow)
        else -> {
            val format = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            format.format(Date(dayStart))
        }
    }
}
