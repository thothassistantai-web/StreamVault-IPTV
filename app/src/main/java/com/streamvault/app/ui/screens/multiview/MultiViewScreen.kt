package com.streamvault.app.ui.screens.multiview

import android.view.View
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.theme.Primary
import com.streamvault.player.PlayerSurfaceResizeMode

@Composable
fun MultiViewScreen(
    onBack: () -> Unit,
    viewModel: MultiViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val firstSlotFocusRequester = remember { FocusRequester() }
    val firstControlFocusRequester = remember { FocusRequester() }
    var showReplacementPicker by remember { mutableStateOf(false) }
    var showControls by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            showReplacementPicker -> showReplacementPicker = false
            showControls -> showControls = false
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initSlots()
        try {
            kotlinx.coroutines.delay(100)
            firstSlotFocusRequester.requestFocus()
        } catch (_: Exception) {
            // No-op: focus request can fail during composition transitions.
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            try {
                kotlinx.coroutines.delay(60)
                firstControlFocusRequester.requestFocus()
            } catch (_: Exception) {
                // No-op: focus handoff can fail during composition transitions.
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(firstSlotFocusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        showControls = !showControls
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        when {
                            showReplacementPicker -> {
                                showReplacementPicker = false
                                true
                            }
                            showControls -> {
                                showControls = false
                                true
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (showReplacementPicker) {
            ReplaceSlotDialog(
                candidates = uiState.replacementCandidates,
                onDismiss = { showReplacementPicker = false },
                onReplace = { channel ->
                    viewModel.replaceFocusedSlot(channel)
                    showReplacementPicker = false
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f)) {
                PlayerCell(
                    slot = uiState.slots.getOrNull(0),
                    isFocused = uiState.focusedSlotIndex == 0,
                    showSelectionBorder = uiState.showSelectionBorder,
                    onClick = { showControls = !showControls },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(firstSlotFocusRequester)
                        .focusProperties {
                            if (showControls) down = firstControlFocusRequester
                        },
                    onFocused = { viewModel.setFocus(0) }
                )
                PlayerCell(
                    slot = uiState.slots.getOrNull(1),
                    isFocused = uiState.focusedSlotIndex == 1,
                    showSelectionBorder = uiState.showSelectionBorder,
                    onClick = { showControls = !showControls },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusProperties {
                            if (showControls) down = firstControlFocusRequester
                        },
                    onFocused = { viewModel.setFocus(1) }
                )
            }
            Row(modifier = Modifier.weight(1f)) {
                PlayerCell(
                    slot = uiState.slots.getOrNull(2),
                    isFocused = uiState.focusedSlotIndex == 2,
                    showSelectionBorder = uiState.showSelectionBorder,
                    onClick = { showControls = !showControls },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusProperties {
                            if (showControls) down = firstControlFocusRequester
                        },
                    onFocused = { viewModel.setFocus(2) }
                )
                PlayerCell(
                    slot = uiState.slots.getOrNull(3),
                    isFocused = uiState.focusedSlotIndex == 3,
                    showSelectionBorder = uiState.showSelectionBorder,
                    onClick = { showControls = !showControls },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusProperties {
                            if (showControls) down = firstControlFocusRequester
                        },
                    onFocused = { viewModel.setFocus(3) }
                )
            }
        }

        val focused = uiState.slots.getOrNull(uiState.focusedSlotIndex)
        if (showControls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MultiViewControlHud(
                    focused = focused,
                    uiState = uiState,
                    firstControlFocusRequester = firstControlFocusRequester,
                    onShowReplacementPicker = { showReplacementPicker = true },
                    onRemoveFocusedSlot = viewModel::removeFocusedSlot,
                    onClearPinnedAudio = viewModel::clearPinnedAudio,
                    onPinAudioToFocusedSlot = viewModel::pinAudioToFocusedSlot,
                    onLoadPreset = viewModel::loadPreset,
                    onSavePreset = viewModel::saveCurrentAsPreset
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerCell(
    slot: MultiViewSlot?,
    isFocused: Boolean,
    showSelectionBorder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocused: () -> Unit
) {
    val showBorder = isFocused && showSelectionBorder

    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(2.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = if (showBorder) 4.dp else 0.dp,
                    color = if (showBorder) Color.White else Color.Transparent
                )
            ),
            focusedBorder = Border.None,
            pressedBorder = Border.None
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF111111),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF111111)
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                slot == null || slot.isEmpty -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+", color = Color(0xFF555555), fontSize = 32.sp)
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.multiview_empty_slot),
                            color = Color(0xFF555555),
                            fontSize = 12.sp
                        )
                    }
                }

                slot.isLoading -> {
                    CircularProgressIndicator(
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.size(32.dp)
                    )
                }

                slot.hasError -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("!", color = Color(0xFFFF5252), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.multiview_stream_error),
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                !slot.performanceBlockedReason.isNullOrBlank() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.multiview_policy_blocked),
                            color = Color(0xFFFFC107),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = slot.performanceBlockedReason.orEmpty(),
                            color = Color(0xFFFFE082),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                else -> {
                    val engine = slot.playerEngine
                    if (engine != null) {
                        AndroidView(
                            factory = { ctx ->
                                engine.createRenderView(
                                    context = ctx,
                                    resizeMode = PlayerSurfaceResizeMode.FILL
                                ).apply {
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                }
                            },
                            update = { renderView ->
                                engine.bindRenderView(
                                    renderView = renderView,
                                    resizeMode = PlayerSurfaceResizeMode.FILL
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Buffering indicator for active streams
                    val playbackState = slot.playerEngine?.playbackState?.collectAsState()
                    if (playbackState?.value == com.streamvault.player.PlaybackState.BUFFERING) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    if (isFocused && !slot.isEmpty) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (slot.isAudioPinned) {
                                    stringResource(R.string.multiview_audio_pinned_badge)
                                } else {
                                    stringResource(R.string.multiview_audio_badge)
                                },
                                color = Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    ) {
                        Text(
                            text = slot.title,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiViewControlHud(
    focused: MultiViewSlot?,
    uiState: MultiViewUiState,
    firstControlFocusRequester: FocusRequester,
    onShowReplacementPicker: () -> Unit,
    onRemoveFocusedSlot: () -> Unit,
    onClearPinnedAudio: () -> Unit,
    onPinAudioToFocusedSlot: () -> Unit,
    onLoadPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.multiview_policy_summary,
                    uiState.performancePolicy.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    uiState.performancePolicy.maxActiveSlots
                ),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelMedium
            )
            Text(text = "•", color = Color.White.copy(alpha = 0.42f))
            Text(
                text = stringResource(
                    R.string.multiview_telemetry_snapshot,
                    uiState.telemetry.activeSlots,
                    uiState.telemetry.standbySlots,
                    uiState.telemetry.bufferingSlots,
                    uiState.telemetry.errorSlots
                ),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (focused != null && focused.title.isNotBlank()) {
            Text(
                text = stringResource(R.string.multiview_focused_prefix, focused.title),
                color = Primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onShowReplacementPicker,
                enabled = uiState.replacementCandidates.isNotEmpty(),
                modifier = Modifier.focusRequester(firstControlFocusRequester)
            ) {
                Text(stringResource(R.string.multiview_replace_slot))
            }
            Button(
                onClick = onRemoveFocusedSlot,
                enabled = focused != null && !focused.isEmpty
            ) {
                Text(stringResource(R.string.multiview_remove_slot))
            }
            if (uiState.pinnedAudioSlotIndex == uiState.focusedSlotIndex) {
                Button(onClick = onClearPinnedAudio) {
                    Text(stringResource(R.string.multiview_audio_follow_focus))
                }
            } else {
                Button(
                    onClick = onPinAudioToFocusedSlot,
                    enabled = focused != null && !focused.isEmpty
                ) {
                    Text(stringResource(R.string.multiview_pin_audio))
                }
            }
        }

        if (uiState.presets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.presets.forEach { preset ->
                    val presetLabel = stringResource(R.string.multiview_preset_label, preset.index + 1)
                    Button(onClick = { onLoadPreset(preset.index) }) {
                        Text(
                            text = if (preset.isPopulated) {
                                "$presetLabel (${preset.channelCount})"
                            } else {
                                presetLabel
                            }
                        )
                    }
                    Button(onClick = { onSavePreset(preset.index) }) {
                        Text(text = stringResource(R.string.multiview_preset_save, preset.index + 1))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplaceSlotDialog(
    candidates: List<com.streamvault.domain.model.Channel>,
    onDismiss: () -> Unit,
    onReplace: (com.streamvault.domain.model.Channel) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.multiview_replace_title),
        subtitle = stringResource(R.string.multiview_replace_empty),
        onDismissRequest = onDismiss,
        widthFraction = 0.5f,
        content = {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.multiview_replace_empty),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { channel ->
                        PremiumDialogActionButton(
                            label = channel.name,
                            onClick = { onReplace(channel) }
                        )
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}
