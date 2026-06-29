package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.PlaybackGesturePreferences

internal fun LazyListScope.settingsGestureControlsSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item {
        GestureControlsHeader()
        GestureToggleRow(
            label = stringResource(R.string.settings_gesture_controls_enabled),
            subtitle = stringResource(R.string.settings_gesture_controls_enabled_subtitle),
            checked = uiState.playbackGesturePreferences.enabled,
            onCheckedChange = viewModel::setPlaybackGesturesEnabled,
        )
        if (uiState.playbackGesturePreferences.enabled) {
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_swipe_channel_change),
                subtitle = stringResource(R.string.settings_gesture_swipe_channel_change_subtitle),
                checked = uiState.playbackGesturePreferences.swipeChannelChangeEnabled,
                onCheckedChange = viewModel::setPlaybackGestureSwipeChannelChangeEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_swipe_overlays),
                subtitle = stringResource(R.string.settings_gesture_swipe_overlays_subtitle),
                checked = uiState.playbackGesturePreferences.swipeOverlayNavigationEnabled,
                onCheckedChange = viewModel::setPlaybackGestureSwipeOverlayNavigationEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_pinch),
                subtitle = stringResource(R.string.settings_gesture_pinch_subtitle),
                checked = uiState.playbackGesturePreferences.pinchZoomEnabled,
                onCheckedChange = viewModel::setPlaybackGesturePinchZoomEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_double_tap_skip),
                subtitle = stringResource(R.string.settings_gesture_double_tap_skip_subtitle),
                checked = uiState.playbackGesturePreferences.doubleTapSkipEnabled,
                onCheckedChange = viewModel::setPlaybackGestureDoubleTapSkipEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_two_finger_swipe),
                subtitle = stringResource(R.string.settings_gesture_two_finger_swipe_subtitle),
                checked = uiState.playbackGesturePreferences.twoFingerSwipeEnabled,
                onCheckedChange = viewModel::setPlaybackGestureTwoFingerSwipeEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_two_finger_program_details),
                subtitle = stringResource(R.string.settings_gesture_two_finger_program_details_subtitle),
                checked = uiState.playbackGesturePreferences.twoFingerProgramDetailsEnabled,
                onCheckedChange = viewModel::setPlaybackGestureTwoFingerProgramDetailsEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_long_press),
                subtitle = stringResource(R.string.settings_gesture_long_press_subtitle),
                checked = uiState.playbackGesturePreferences.longPressQuickMenuEnabled,
                onCheckedChange = viewModel::setPlaybackGestureLongPressQuickMenuEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_edge_hold_zones),
                subtitle = stringResource(R.string.settings_gesture_edge_hold_zones_subtitle),
                checked = uiState.playbackGesturePreferences.edgeHoldZonesEnabled,
                onCheckedChange = viewModel::setPlaybackGestureEdgeHoldZonesEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_corner_hold_zones),
                subtitle = stringResource(R.string.settings_gesture_corner_hold_zones_subtitle),
                checked = uiState.playbackGesturePreferences.cornerHoldZonesEnabled,
                onCheckedChange = viewModel::setPlaybackGestureCornerHoldZonesEnabled,
            )
            GestureToggleRow(
                label = stringResource(R.string.settings_gesture_edge_panels),
                subtitle = stringResource(R.string.settings_gesture_edge_panels_subtitle),
                checked = uiState.playbackGesturePreferences.edgePanelsEnabled,
                onCheckedChange = viewModel::setPlaybackGestureEdgePanelsEnabled,
            )
            GestureSensitivityRow(
                sensitivityPercent = uiState.playbackGesturePreferences.swipeSensitivityPercent,
                onSelect = viewModel::setPlaybackGestureSwipeSensitivityPercent,
            )
        }
    }
}

@Composable
private fun GestureControlsHeader() {
    Text(
        text = stringResource(R.string.settings_gesture_controls_title),
        style = MaterialTheme.typography.titleMedium,
        color = Primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
    )
    Text(
        text = stringResource(R.string.settings_gesture_controls_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = OnBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun GestureToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    TvClickableSurface(
        onClick = { onCheckedChange(!checked) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun GestureSensitivityRow(
    sensitivityPercent: Int,
    onSelect: (Int) -> Unit,
) {
    val options = remember {
        listOf(50, 75, 100, 125, 150)
    }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    if (showDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_gesture_swipe_sensitivity),
            onDismiss = { showDialog = false },
        ) {
            options.forEachIndexed { index, percent ->
                LevelOption(
                    level = index,
                    text = formatGestureSensitivityLabel(percent),
                    currentLevel = if (sensitivityPercent == percent) index else -1,
                    onSelect = {
                        onSelect(percent)
                        showDialog = false
                    },
                )
            }
        }
    }
    ClickableSettingsRow(
        label = stringResource(R.string.settings_gesture_swipe_sensitivity),
        value = formatGestureSensitivityLabel(sensitivityPercent),
        onClick = { showDialog = true },
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
}

internal fun formatGestureSensitivityLabel(percent: Int): String = when (percent) {
    in Int.MIN_VALUE..62 -> "Low"
    in 63..87 -> "Medium-low"
    in 88..112 -> "Normal"
    in 113..137 -> "Medium-high"
    else -> "High"
}
