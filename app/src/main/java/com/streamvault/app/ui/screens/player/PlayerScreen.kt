package com.streamvault.app.ui.screens.player

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.streamvault.app.ui.components.dialogs.ProgramHistoryDialog
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.streamvault.app.R
import com.streamvault.app.MainActivity
import com.streamvault.app.ui.screens.player.overlay.ChannelInfoOverlay
import com.streamvault.app.ui.screens.player.overlay.CategoryListOverlay
import com.streamvault.app.ui.screens.player.overlay.ChannelListOverlay
import com.streamvault.app.ui.screens.player.overlay.DiagnosticsOverlay
import com.streamvault.app.ui.screens.player.overlay.EpgOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerErrorOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNoticeBanner
import com.streamvault.app.ui.screens.player.overlay.PlayerResumePrompt
import com.streamvault.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerAspectRatioToast
import com.streamvault.app.ui.screens.player.overlay.PlayerControlsOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNumericInputOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerResolutionBadge
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.navigation.Routes



@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    epgChannelId: String? = null,
    internalChannelId: Long = -1L,
    categoryId: Long? = null,
    providerId: Long? = null,
    isVirtual: Boolean = false,
    contentType: String = "LIVE",
    archiveStartMs: Long? = null,
    archiveEndMs: Long? = null,
    archiveTitle: String? = null,
    returnRoute: String? = null,
    onBack: () -> Unit,
    onNavigate: ((String) -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
    multiViewViewModel: MultiViewViewModel = hiltViewModel()
) {
    val mainActivity = LocalContext.current.findMainActivity()
    val isInPictureInPictureMode = mainActivity
        ?.pictureInPictureModeFlow
        ?.collectAsState(initial = mainActivity.isInPictureInPictureMode)
        ?.value
        ?: false
    val playbackState by viewModel.playerEngine.playbackState.collectAsState()
    val isPlaying by viewModel.playerEngine.isPlaying.collectAsState()
    val showControls by viewModel.showControls.collectAsState()
    val videoFormat by viewModel.videoFormat.collectAsState()
    val playerError by viewModel.playerError.collectAsState()
    val currentProgram by viewModel.currentProgram.collectAsState()
    val nextProgram by viewModel.nextProgram.collectAsState()
    val programHistory by viewModel.programHistory.collectAsState()
    val currentChannel by viewModel.currentChannel.collectAsState()
    val resumePrompt by viewModel.resumePrompt.collectAsState()
    
    val showChannelListOverlay by viewModel.showChannelListOverlay.collectAsState()
    val showCategoryListOverlay by viewModel.showCategoryListOverlay.collectAsState()
    val availableCategories by viewModel.availableCategories.collectAsState()
    val activeCategoryId by viewModel.activeCategoryId.collectAsState()
    val showEpgOverlay by viewModel.showEpgOverlay.collectAsState()
    val currentChannelList by viewModel.currentChannelList.collectAsState()
    val recentChannels by viewModel.recentChannels.collectAsState()
    val lastVisitedCategory by viewModel.lastVisitedCategory.collectAsState()
    val displayChannelNumber by viewModel.displayChannelNumber.collectAsState()
    val upcomingPrograms by viewModel.upcomingPrograms.collectAsState()
    val showChannelInfoOverlay by viewModel.showChannelInfoOverlay.collectAsState()
    val numericChannelInput by viewModel.numericChannelInput.collectAsState()
    
    val availableAudioTracks by viewModel.availableAudioTracks.collectAsState()
    val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsState()
    val availableVideoQualities by viewModel.availableVideoQualities.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val showDiagnostics by viewModel.showDiagnostics.collectAsState()
    val playerDiagnostics by viewModel.playerDiagnostics.collectAsState()
    val currentPosition by viewModel.playerEngine.currentPosition.collectAsState()
    val duration by viewModel.playerEngine.duration.collectAsState()
    val playerNotice by viewModel.playerNotice.collectAsState()
    val currentChannelRecording by viewModel.currentChannelRecording.collectAsState()

    var showTrackSelection by remember { mutableStateOf<TrackType?>(null) }
    var showProgramHistory by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val categoryListFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val channelInfoFocusRequester = remember { FocusRequester() } // NEW
    var lastFocusedChannelListItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastFocusedEpgProgramToken by rememberSaveable { mutableStateOf<Long?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPictureInPictureMode by rememberUpdatedState(isInPictureInPictureMode)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(mainActivity, streamUrl, playbackState, isPlaying, videoFormat.width, videoFormat.height) {
        mainActivity?.updatePlayerPictureInPictureState(
            enabled = streamUrl.isNotBlank() && playbackState != PlaybackState.ERROR,
            isPlaying = isPlaying,
            videoWidth = videoFormat.width,
            videoHeight = videoFormat.height
        )
    }

    LaunchedEffect(isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            viewModel.closeOverlays()
            if (showControls) {
                viewModel.toggleControls()
            }
        }
    }

    DisposableEffect(lifecycleOwner, mainActivity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> {
                    if (currentPictureInPictureMode) {
                        viewModel.closeOverlays()
                    } else {
                        viewModel.onAppBackgrounded()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mainActivity?.clearPlayerPictureInPictureState()
            viewModel.onPlayerScreenDisposed()
        }
    }

    // Consolidated focus management for all overlays
    val anyOverlayVisible = showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showTrackSelection != null || showProgramHistory || showSplitDialog || showDiagnostics
    
    LaunchedEffect(anyOverlayVisible) {
        if (anyOverlayVisible) {
            // Give overlays a moment to animate in before requesting focus
            delay(150)
            try {
                when {
                    showCategoryListOverlay -> categoryListFocusRequester.requestFocus()
                    showChannelListOverlay -> channelListFocusRequester.requestFocus()
                    showEpgOverlay -> epgFocusRequester.requestFocus()
                    showChannelInfoOverlay -> channelInfoFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
        } else {
            // Restore focus to main player when all overlays are gone
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    
    // Show resolution overlay temporarily when it changes
    var showResolution by remember { mutableStateOf(false) }
    
    LaunchedEffect(videoFormat) {
        if (!videoFormat.isEmpty) {
            showResolution = true
            delay(3000)
            showResolution = false
        }
    }

    if (!isInPictureInPictureMode && showProgramHistory) {
        ProgramHistoryDialog(
            programs = programHistory,
            onDismiss = { showProgramHistory = false },
            onProgramSelect = { program ->
                viewModel.playCatchUp(program)
                showProgramHistory = false
            }
        )
    }

    // Split Screen Manager dialog
    if (showSplitDialog && currentChannel != null) {
        MultiViewPlannerDialog(
            pendingChannel = currentChannel,
            onDismiss = { showSplitDialog = false },
            onLaunch = {
                showSplitDialog = false
                onNavigate?.invoke(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

    LaunchedEffect(streamUrl, epgChannelId, title, internalChannelId, categoryId, providerId, isVirtual, contentType, archiveStartMs, archiveEndMs, archiveTitle) {
        viewModel.prepare(
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            internalChannelId = internalChannelId,
            categoryId = categoryId ?: -1,
            providerId = providerId ?: -1,
            isVirtual = isVirtual,
            contentType = contentType,
            title = title,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle
        )
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(100)
            try { playButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            viewModel.hideControlsAfterDelay()
        } else {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val handlePlayerNoticeAction: (PlayerNoticeAction) -> Unit = remember(returnRoute, onNavigate) {
        { action ->
            if (action == PlayerNoticeAction.OPEN_GUIDE && !returnRoute.isNullOrBlank() && onNavigate != null) {
                viewModel.dismissPlayerNotice()
                onNavigate(returnRoute)
            } else {
                viewModel.runPlayerNoticeAction(action)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusProperties {
                // Only allow focus on the main background when no overlays are active
                canFocus = !anyOverlayVisible
            }
            .focusable()
            .onKeyEvent { event ->
                // Only handle KeyDown to avoid double actions
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE" && viewModel.hasPendingNumericChannelInput()) {
                                viewModel.commitNumericChannelInput()
                            } else if (contentType == "LIVE") {
                                if (showChannelInfoOverlay) viewModel.closeChannelInfoOverlay()
                                else viewModel.openChannelInfoOverlay()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelListOverlay && contentType == "LIVE") {
                                // Second left press while channel list is open → open category list
                                viewModel.openCategoryListOverlay()
                                true
                            } else if (contentType == "LIVE" && !showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openEpgOverlay() else viewModel.openChannelListOverlay()
                                true
                            } else if (!showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.seekForward() else viewModel.seekBackward()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE" && !showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openChannelListOverlay() else viewModel.openEpgOverlay()
                                true
                            } else if (!showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.seekBackward() else viewModel.seekForward()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) return@onKeyEvent false

                            if (contentType == "LIVE") {
                                viewModel.playNext()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) return@onKeyEvent false

                            if (contentType == "LIVE") {
                                viewModel.playPrevious()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (viewModel.hasPendingNumericChannelInput()) {
                                viewModel.clearNumericChannelInput()
                                true
                            } else if (playerNotice != null) {
                                viewModel.dismissPlayerNotice()
                                true
                            } else if (showProgramHistory) {
                                showProgramHistory = false
                                true
                            } else if (showSplitDialog) {
                                showSplitDialog = false
                                true
                            } else if (showTrackSelection != null) {
                                showTrackSelection = null
                                true
                            } else if (showDiagnostics) {
                                viewModel.toggleDiagnostics()
                                true
                            } else if (showChannelInfoOverlay) {
                                viewModel.closeChannelInfoOverlay()
                                true
                            } else if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay) {
                                viewModel.closeOverlays()
                                true
                            } else if (showControls) {
                                viewModel.toggleControls()
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isPlaying) viewModel.pause() else viewModel.play()
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                            if (contentType == "LIVE") {
                                viewModel.playNext()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                            if (contentType == "LIVE") {
                                viewModel.playPrevious()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (contentType == "LIVE") {
                                viewModel.zapToLastChannel()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_GUIDE -> {
                            if (contentType == "LIVE") {
                                viewModel.openEpgOverlay()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_INFO -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE") {
                                if (showChannelInfoOverlay) viewModel.closeChannelInfoOverlay()
                                else viewModel.openChannelInfoOverlay()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            viewModel.toggleControls()
                            true
                        }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                            if (contentType == "LIVE") {
                                val keyCode = event.nativeKeyEvent.keyCode
                                val digit = when (keyCode) {
                                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                                    else -> return@onKeyEvent false
                                }
                                viewModel.inputNumericChannelDigit(digit)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // ExoPlayer Video Surface
        AndroidView(
            factory = { context ->
                viewModel.playerEngine.createRenderView(
                    context = context,
                    resizeMode = aspectRatio.toPlayerSurfaceResizeMode()
                )
            },
            update = { renderView ->
                viewModel.playerEngine.bindRenderView(
                    renderView = renderView,
                    resizeMode = aspectRatio.toPlayerSurfaceResizeMode()
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering indicator
        if (playbackState == PlaybackState.BUFFERING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_buffering),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = playerNotice != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 116.dp)
        ) {
            PlayerNoticeBanner(
                notice = playerNotice,
                onDismiss = viewModel::dismissPlayerNotice,
                onAction = handlePlayerNoticeAction
            )
        }

        // Error overlay
        if (playbackState == PlaybackState.ERROR) {
            PlayerErrorOverlay(
                playerError = playerError,
                contentType = contentType,
                hasAlternateStream = viewModel.hasAlternateStream(),
                hasLastChannel = viewModel.hasLastChannel(),
                onAction = handlePlayerNoticeAction
            )
        }

        PlayerControlsOverlay(
            visible = showControls,
            title = title,
            contentType = contentType,
            isPlaying = isPlaying,
            currentProgram = currentProgram,
            currentChannelName = currentChannel?.name,
            displayChannelNumber = displayChannelNumber,
            currentPosition = currentPosition,
            duration = duration,
            aspectRatioLabel = aspectRatio.modeName,
            subtitleTrackCount = availableSubtitleTracks.size,
            audioTrackCount = availableAudioTracks.size,
            videoQualityCount = availableVideoQualities.size,
            currentRecordingStatus = currentChannelRecording?.status,
            playButtonFocusRequester = playButtonFocusRequester,
            modifier = Modifier.fillMaxSize(),
            onClose = onBack,
            onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
            onSeekBackward = viewModel::seekBackward,
            onSeekForward = viewModel::seekForward,
            onRestartProgram = viewModel::restartCurrentProgram,
            onOpenArchive = { showProgramHistory = true },
            onStartRecording = viewModel::startManualRecording,
            onStopRecording = viewModel::stopCurrentRecording,
            onScheduleRecording = viewModel::scheduleRecording,
            onToggleAspectRatio = viewModel::toggleAspectRatio,
            onOpenSubtitleTracks = { showTrackSelection = TrackType.TEXT },
            onOpenAudioTracks = { showTrackSelection = TrackType.AUDIO },
            onOpenVideoTracks = { showTrackSelection = TrackType.VIDEO },
            onOpenSplitScreen = { showSplitDialog = true }
        )

        PlayerNumericInputOverlay(
            state = numericChannelInput,
            visible = contentType == "LIVE" && !showControls,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )

        PlayerAspectRatioToast(
            aspectRatioLabel = aspectRatio.modeName,
            controlsVisible = showControls,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        )

        PlayerResolutionBadge(
            visible = showResolution && !showControls && !videoFormat.isEmpty,
            resolutionLabel = videoFormat.resolutionLabel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(32.dp)
        )
        
        // Resume Prompt Dialog
        if (!isInPictureInPictureMode && resumePrompt.show) {
            PlayerResumePrompt(
                title = resumePrompt.title,
                onStartOver = { viewModel.dismissResumePrompt(resume = false) },
                onResume = { viewModel.dismissResumePrompt(resume = true) }
            )
        }
        
        // Track Selection Dialog
        if (!isInPictureInPictureMode) {
            PlayerTrackSelectionDialog(
                trackType = showTrackSelection,
                audioTracks = availableAudioTracks,
                subtitleTracks = availableSubtitleTracks,
                videoTracks = availableVideoQualities,
                onDismiss = { showTrackSelection = null },
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectVideo = viewModel::selectVideoQuality,
                onSelectSubtitle = viewModel::selectSubtitleTrack
            )
        }

        // --- Overlays ---
        if (!isInPictureInPictureMode && showDiagnostics) {
            val playerStats by viewModel.playerStats.collectAsState()
            DiagnosticsOverlay(
                stats = playerStats,
                diagnostics = playerDiagnostics,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
            )
        }

        AnimatedVisibility(
            visible = showChannelListOverlay,
            enter = slideInHorizontally(initialOffsetX = { if (isRtl) it else -it }),
            exit = slideOutHorizontally(targetOffsetX = { if (isRtl) it else -it }),
            modifier = Modifier
                .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
                .fillMaxHeight()
                .width(350.dp)
                .focusGroup()
        ) {
            ChannelListOverlay(
                channels = currentChannelList,
                recentChannels = recentChannels,
                currentChannelId = currentChannel?.id ?: internalChannelId,
                overlayFocusRequester = channelListFocusRequester,
                preferredFocusedChannelId = lastFocusedChannelListItemId,
                onFocusedChannelChange = { channelId -> lastFocusedChannelListItemId = channelId },
                lastVisitedCategoryName = lastVisitedCategory?.name,
                onOpenLastGroup = { viewModel.openLastVisitedCategory() },
                onSelectChannel = { channelId -> viewModel.zapToChannel(channelId) },
                onOpenCategories = { viewModel.openCategoryListOverlay() },
                onDismiss = { viewModel.closeOverlays() },
                onOverlayInteracted = viewModel::onLiveOverlayInteraction
            )
        }

        AnimatedVisibility(
            visible = showCategoryListOverlay,
            enter = slideInHorizontally(initialOffsetX = { if (isRtl) it else -it }),
            exit = slideOutHorizontally(targetOffsetX = { if (isRtl) it else -it }),
            modifier = Modifier
                .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
                .fillMaxHeight()
                .width(350.dp)
                .focusGroup()
        ) {
            CategoryListOverlay(
                categories = availableCategories,
                currentCategoryId = activeCategoryId,
                overlayFocusRequester = categoryListFocusRequester,
                onSelectCategory = { category ->
                    viewModel.selectCategoryFromOverlay(category)
                },
                onDismiss = { viewModel.closeOverlays() },
                onOverlayInteracted = viewModel::onLiveOverlayInteraction
            )
        }

        AnimatedVisibility(
            visible = showEpgOverlay,
            enter = slideInHorizontally(initialOffsetX = { if (isRtl) -it else it }),
            exit = slideOutHorizontally(targetOffsetX = { if (isRtl) -it else it }),
            modifier = Modifier
                .align(if (isRtl) Alignment.TopStart else Alignment.TopEnd)
                .fillMaxHeight()
                .width(400.dp)
                .focusGroup()
        ) {
            EpgOverlay(
                currentChannel = currentChannel,
                displayChannelNumber = displayChannelNumber,
                currentProgram = currentProgram,
                nextProgram = nextProgram,
                upcomingPrograms = upcomingPrograms,
                overlayFocusRequester = epgFocusRequester,
                preferredFocusedProgramToken = lastFocusedEpgProgramToken,
                onFocusedProgramChange = { token -> lastFocusedEpgProgramToken = token },
                onDismiss = { viewModel.closeOverlays() },
                onPlayCatchUp = { program -> 
                    viewModel.playCatchUp(program)
                    viewModel.closeOverlays()
                },
                onOverlayInteracted = viewModel::onLiveOverlayInteraction
            )
        }

        AnimatedVisibility(
            visible = showChannelInfoOverlay,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .focusGroup()
        ) {
            ChannelInfoOverlay(
                currentChannel = currentChannel,
                displayChannelNumber = displayChannelNumber,
                currentProgram = currentProgram,
                nextProgram = nextProgram,
                focusRequester = channelInfoFocusRequester,
                lastVisitedCategoryName = lastVisitedCategory?.name,
                onDismiss = { viewModel.closeChannelInfoOverlay() },
                onOverlayInteracted = viewModel::onLiveOverlayInteraction,
                onOpenFullEpg = {
                    viewModel.closeChannelInfoOverlay()
                    viewModel.openEpgOverlay()
                },
                onOpenLastGroup = {
                    viewModel.closeChannelInfoOverlay()
                    viewModel.openLastVisitedCategory()
                },
                currentRecordingStatus = currentChannelRecording?.status,
                onStartRecording = viewModel::startManualRecording,
                onStopRecording = viewModel::stopCurrentRecording,
                onRestartProgram = { viewModel.restartCurrentProgram() },
                onToggleAspectRatio = { viewModel.toggleAspectRatio() },
                onToggleDiagnostics = { viewModel.toggleDiagnostics() },
                onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
                isPlaying = isPlaying,
                currentAspectRatio = aspectRatio.modeName,
                isDiagnosticsEnabled = showDiagnostics,
                onOpenSplitScreen = { showSplitDialog = true }
            )
        }
    }
}

private fun AspectRatio.toPlayerSurfaceResizeMode(): PlayerSurfaceResizeMode = when (this) {
    AspectRatio.FIT -> PlayerSurfaceResizeMode.FIT
    AspectRatio.FILL -> PlayerSurfaceResizeMode.FILL
    AspectRatio.ZOOM -> PlayerSurfaceResizeMode.ZOOM
}

private tailrec fun android.content.Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is android.content.ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
