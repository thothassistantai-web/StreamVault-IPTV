package com.streamvault.app.ui.screens.player.gesture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.screens.epg.EpgViewModel
import com.streamvault.app.ui.screens.player.PlayerViewModel
import com.streamvault.app.ui.screens.player.closeMiniGuideOverlay
import com.streamvault.app.ui.screens.player.closeProgramDetailsOverlay
import com.streamvault.app.ui.screens.player.closeQuickMenuOverlay
import com.streamvault.app.ui.screens.player.dismissTouchEdgePanel

import com.streamvault.app.ui.screens.player.closeFullGuideOverlay
import com.streamvault.app.ui.screens.player.closeOverlays
import com.streamvault.app.ui.screens.player.onLiveOverlayInteraction
import com.streamvault.app.ui.screens.player.openCategoryListOverlay
import com.streamvault.app.ui.screens.player.openLastVisitedCategory
import com.streamvault.app.ui.screens.player.pause
import com.streamvault.app.ui.screens.player.play
import com.streamvault.app.ui.screens.player.playCatchUp
import com.streamvault.app.ui.screens.player.playChannelFromGuideOverlay
import com.streamvault.app.ui.screens.player.seekBackward
import com.streamvault.app.ui.screens.player.seekForward
import com.streamvault.app.ui.screens.player.startManualRecording
import com.streamvault.app.ui.screens.player.zapToChannel
import com.streamvault.app.ui.screens.player.overlay.ChannelListOverlay
import com.streamvault.app.ui.screens.player.overlay.TouchEdgeNavItem
import com.streamvault.app.ui.screens.player.overlay.TouchEdgeOverlayHost
import com.streamvault.app.ui.screens.player.overlay.TouchMiniGuideOverlay
import com.streamvault.app.ui.screens.player.overlay.TouchProgramDetailsOverlay
import com.streamvault.app.ui.screens.player.overlay.TouchQuickMenuAction
import com.streamvault.app.ui.screens.player.overlay.TouchQuickMenuOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerTransparentGuideOverlay
import com.streamvault.app.ui.screens.player.resolvePlayerGuideNavigationContext
import com.streamvault.domain.model.ContentType
import com.streamvault.player.TrackType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlayerTouchOverlays(
    viewModel: PlayerViewModel,
    contentType: String,
    touchPanelWidth: Dp,
    channelBrowserWidth: Dp,
    channelListFocusRequester: androidx.compose.ui.focus.FocusRequester,
    currentChannelId: Long,
    onNavigate: ((String) -> Unit)?,
    onOpenTrackSelection: (TrackType) -> Unit,
    onOpenSpeedSelection: () -> Unit,
    onOpenStopPlaybackTimer: () -> Unit,
    onOpenProgramHistory: () -> Unit,
    onOpenSplitDialog: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showMiniGuide by viewModel.showMiniGuideOverlay.collectAsStateWithLifecycle()
    val showQuickMenu by viewModel.showQuickMenuOverlay.collectAsStateWithLifecycle()
    val showProgramDetails by viewModel.showProgramDetailsOverlay.collectAsStateWithLifecycle()
    val showChannelBrowser by viewModel.showChannelListOverlay.collectAsStateWithLifecycle()
    val showFullGuide by viewModel.showFullGuideOverlay.collectAsStateWithLifecycle()
    val touchEdgePanel by viewModel.touchEdgePanel.collectAsStateWithLifecycle()
    val currentProgram by viewModel.currentProgram.collectAsStateWithLifecycle()
    val nextProgram by viewModel.nextProgram.collectAsStateWithLifecycle()
    val upcomingPrograms by viewModel.upcomingPrograms.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val displayChannelNumber by viewModel.displayChannelNumber.collectAsStateWithLifecycle()
    val currentChannelList by viewModel.currentChannelList.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val lastVisitedCategory by viewModel.lastVisitedCategory.collectAsStateWithLifecycle()
    val playerEngine by viewModel.activePlayerEngine.collectAsStateWithLifecycle()
    val isPlaying by playerEngine.isPlaying.collectAsStateWithLifecycle()
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val timeLabel = remember {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    val quickMenuActions = remember(contentType) {
        buildList {
            add(TouchQuickMenuAction("★", "Favorites") { onNavigate?.invoke(Routes.HOME) })
            add(TouchQuickMenuAction("A", "Audio") { onOpenTrackSelection(TrackType.AUDIO) })
            add(TouchQuickMenuAction("CC", "Subtitles") { onOpenTrackSelection(TrackType.TEXT) })
            if (contentType == ContentType.LIVE.name) {
                add(TouchQuickMenuAction("REC", "Record") { viewModel.startManualRecording() })
            }
            add(TouchQuickMenuAction("Zzz", "Sleep") { onOpenStopPlaybackTimer() })
            add(TouchQuickMenuAction("⚙", "Settings") { onNavigate?.invoke(Routes.SETTINGS) })
        }
    }

    val leftNavItems = remember(onNavigate) {
        listOf(
            TouchEdgeNavItem("Favorites") { onNavigate?.invoke(Routes.HOME) },
            TouchEdgeNavItem("All Channels") { onNavigate?.invoke(Routes.LIVE_TV) },
            TouchEdgeNavItem("Guide") { onNavigate?.invoke(Routes.EPG) },
            TouchEdgeNavItem("Search") { onNavigate?.invoke(Routes.SEARCH) },
        )
    }
    val rightMediaItems = remember(onNavigate) {
        listOf(
            TouchEdgeNavItem("Recordings") { onNavigate?.invoke(Routes.SETTINGS) },
            TouchEdgeNavItem("Downloads") { onNavigate?.invoke(Routes.DOWNLOADS) },
            TouchEdgeNavItem("Movies") { onNavigate?.invoke(Routes.MOVIES) },
            TouchEdgeNavItem("Series") { onNavigate?.invoke(Routes.SERIES) },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        TouchMiniGuideOverlay(
            visible = showMiniGuide,
            currentProgram = currentProgram,
            nextProgram = nextProgram,
            upcomingPrograms = upcomingPrograms,
            panelWidth = touchPanelWidth,
            onDismiss = viewModel::closeMiniGuideOverlay,
            onOverlayInteracted = viewModel::onLiveOverlayInteraction,
        )

        TouchProgramDetailsOverlay(
            visible = showProgramDetails,
            currentChannel = currentChannel,
            displayChannelNumber = displayChannelNumber,
            currentProgram = currentProgram,
            panelWidth = touchPanelWidth,
            onDismiss = viewModel::closeProgramDetailsOverlay,
        )

        TouchQuickMenuOverlay(
            visible = showQuickMenu,
            actions = quickMenuActions,
            panelWidth = touchPanelWidth,
            onDismiss = viewModel::closeQuickMenuOverlay,
            onOverlayInteracted = viewModel::onLiveOverlayInteraction,
        )

        AnimatedVisibility(
            visible = showChannelBrowser && contentType == ContentType.LIVE.name,
            enter = slideInHorizontally(initialOffsetX = { if (isRtl) -it else it }),
            exit = slideOutHorizontally(targetOffsetX = { if (isRtl) -it else it }),
            modifier = Modifier
                .align(if (isRtl) Alignment.TopStart else Alignment.TopEnd)
                .fillMaxHeight()
                .width(channelBrowserWidth),
        ) {
            ChannelListOverlay(
                channels = currentChannelList,
                recentChannels = recentChannels,
                currentChannelId = currentChannel?.id ?: currentChannelId,
                overlayFocusRequester = channelListFocusRequester,
                lastVisitedCategoryName = lastVisitedCategory?.name,
                onOpenLastGroup = viewModel::openLastVisitedCategory,
                onSelectChannel = viewModel::zapToChannel,
                onOpenCategories = viewModel::openCategoryListOverlay,
                onDismiss = viewModel::closeOverlays,
                onOverlayInteracted = viewModel::onLiveOverlayInteraction,
            )
        }

        TouchEdgeOverlayHost(
            panel = touchEdgePanel,
            leftNavItems = leftNavItems,
            rightMediaItems = rightMediaItems,
            currentTimeLabel = timeLabel,
            isPlaying = isPlaying,
            programTitle = currentProgram?.title,
            onDismiss = viewModel::dismissTouchEdgePanel,
            onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
            onSeekBackward = viewModel::seekBackward,
            onSeekForward = viewModel::seekForward,
        )

        if (showFullGuide && contentType == ContentType.LIVE.name) {
            val epgViewModel: EpgViewModel = hiltViewModel()
            val epgUiState by epgViewModel.uiState.collectAsStateWithLifecycle()
            val activeCategoryId by viewModel.activeCategoryId.collectAsStateWithLifecycle()
            val guideContext = remember(activeCategoryId, currentChannel?.categoryId) {
                resolvePlayerGuideNavigationContext(
                    activeCategoryId = activeCategoryId,
                    currentChannelCategoryId = currentChannel?.categoryId,
                )
            }

            LaunchedEffect(guideContext) {
                epgViewModel.applyNavigationContext(
                    categoryId = guideContext.categoryId,
                    anchorTime = null,
                    favoritesOnly = guideContext.favoritesOnly,
                )
            }

            PlayerTransparentGuideOverlay(
                uiState = epgUiState,
                currentPlayerChannelId = currentChannel?.id ?: currentChannelId,
                onDismiss = viewModel::closeFullGuideOverlay,
                onJumpToNow = epgViewModel::jumpToNow,
                onSelectCategory = { category -> epgViewModel.selectCategory(category.id) },
                onSearchQueryChange = epgViewModel::updateProgramSearchQuery,
                onClearSearch = epgViewModel::clearProgramSearch,
                onWatchChannel = { channel ->
                    viewModel.playChannelFromGuideOverlay(
                        channel = channel,
                        selectedGuideCategoryId = guideContext.categoryId ?: -1L,
                        favoritesOnly = guideContext.favoritesOnly,
                        combinedProfileId = null,
                    )
                },
                onWatchArchive = { _, program ->
                    viewModel.playCatchUp(program)
                    viewModel.closeFullGuideOverlay()
                },
                onRequestMoreChannels = epgViewModel::requestMoreChannels,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
