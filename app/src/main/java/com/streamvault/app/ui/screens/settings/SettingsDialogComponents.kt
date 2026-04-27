package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.localization.localeForLanguageTag
import com.streamvault.app.localization.supportedAppLanguageTags
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.dialogs.rememberDialogOpenGestureBlocker
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingSourceType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Locale

internal enum class ParentalAction {
    ChangeLevel, ChangePin, SetNewPin
}

@Composable
internal fun PremiumSelectionDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    var canInteract by remember { mutableStateOf(false) }
    val blockOpenGesture = rememberDialogOpenGestureBlocker(canInteract)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        canInteract = true
    }
    Dialog(onDismissRequest = { if (canInteract) onDismiss() }) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceElevated,
                modifier = resolvedModifier
                    .border(1.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .onPreviewKeyEvent(blockOpenGesture)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TvClickableSurface(
                            onClick = { if (canInteract) onDismiss() },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Primary.copy(alpha = 0.2f),
                                focusedContainerColor = Primary.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.settings_cancel),
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.fillMaxWidth(0.62f))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogWidthFraction = when {
                    maxWidth < 700.dp -> 0.92f
                    maxWidth < 1000.dp -> 0.78f
                    else -> 0.62f
                }
                dialogContent(Modifier.fillMaxWidth(dialogWidthFraction))
            }
        }
    }
}

@Composable
internal fun TimeoutValueDialog(
    title: String,
    subtitle: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsedValue = value.toIntOrNull()
    val isValid = parsedValue != null && parsedValue in 2..60

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.42f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_timeout_seconds_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                NumericSettingsTextField(
                    value = value,
                    onValueChange = { updated -> value = updated.filter(Char::isDigit).take(2) },
                    placeholder = stringResource(R.string.settings_timeout_seconds_placeholder)
                )
                Text(
                    text = if (isValid) {
                        formatTimeoutSecondsLabel(parsedValue ?: initialValue, androidx.compose.ui.platform.LocalContext.current)
                    } else {
                        stringResource(R.string.settings_timeout_validation)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid) OnSurfaceDim else Color(0xFFFF8A80)
                )
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_timeout_apply),
                    onClick = { parsedValue?.let(onConfirm) },
                    enabled = isValid
                )
            }
        }
    )
}

@Composable
internal fun LevelOption(
    level: Int,
    text: String,
    currentLevel: Int,
    subtitle: String? = null,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = level == currentLevel,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (subtitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.titleSmall, color = OnBackground)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
        }
    }
}

@Composable
private fun NumericSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val isFocused = hasContainerFocus || hasInputFocus

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    LaunchedEffect(acceptsInput, pendingInputActivation) {
        if (!isTelevisionDevice || !acceptsInput || !pendingInputActivation) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboardController?.show()
        pendingInputActivation = false
    }

    TvClickableSurface(
        onClick = {
            if (!isTelevisionDevice) {
                focusRequester.requestFocus()
                keyboardController?.show()
                return@TvClickableSurface
            }
            acceptsInput = true
            pendingInputActivation = true
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { hasContainerFocus = it.isFocused }
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && !isFocused) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updatedValue ->
                    val digitsOnly = updatedValue.text.filter(Char::isDigit).take(2)
                    fieldValue = updatedValue.copy(
                        text = digitsOnly,
                        selection = TextRange(digitsOnly.length.coerceAtMost(digitsOnly.length))
                    )
                    if (digitsOnly != value) {
                        onValueChange(digitsOnly)
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusProperties {
                        canFocus = !isTelevisionDevice || acceptsInput
                        if (isTelevisionDevice && acceptsInput) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    }
                    .onFocusChanged {
                        hasInputFocus = it.isFocused
                        if (!it.isFocused) {
                            if (isTelevisionDevice) {
                                acceptsInput = false
                            }
                            keyboardController?.hide()
                        }
                    },
                readOnly = isTelevisionDevice && !acceptsInput
            )
        }
    }
}

@Composable
internal fun QualityCapSelectionDialog(
    title: String,
    currentValue: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = remember(context) {
        listOf<Int?>(null, 2160, 1080, 720, 480)
    }
    PremiumSelectionDialog(
        title = title,
        onDismiss = onDismiss
    ) {
        options.forEachIndexed { index, option ->
            LevelOption(
                level = index,
                text = formatQualityCapLabel(option, context.getString(R.string.settings_quality_cap_auto)),
                currentLevel = if (option == currentValue) index else -1,
                onSelect = { onSelect(option) }
            )
        }
    }
}

@Composable
internal fun CategorySortModeDialog(
    type: ContentType,
    currentMode: CategorySortMode,
    onDismiss: () -> Unit,
    onModeSelected: (CategorySortMode) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    PremiumDialog(
        title = categoryTypeLabel(type, context),
        subtitle = categoryTypeDescription(type, context),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CategorySortMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == currentMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatCategorySortModeLabel(mode, context),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == currentMode) Primary else OnBackground
                            )
                            Text(
                                text = sortModeLabel(mode, context),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun ParentalControlCard(
    level: Int,
    hasParentalPin: Boolean,
    hasActiveProvider: Boolean,
    onChangeLevel: () -> Unit,
    onChangePin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.settings_protection_level), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                Text(
                    text = when(level) {
                        0 -> stringResource(R.string.settings_level_off)
                        1 -> stringResource(R.string.settings_level_locked)
                        2 -> stringResource(R.string.settings_level_private)
                        3 -> stringResource(R.string.settings_level_hidden)
                        else -> stringResource(R.string.settings_level_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level == 0) ErrorColor else Primary
                )
            }
            
            // Custom Focusable Button for "Change"
            TvClickableSurface(
                onClick = onChangeLevel,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.2f),
                    focusedContainerColor = Secondary.copy(alpha = 0.5f),
                    contentColor = Secondary,
                    focusedContentColor = Secondary
                )
            ) {
                Text(
                    text = stringResource(R.string.settings_change),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_parental_pin), style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            
            // Custom Focusable Button for "Change PIN"
            TvClickableSurface(
                onClick = onChangePin,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.2f),
                    focusedContainerColor = Primary.copy(alpha = 0.5f),
                    contentColor = Primary,
                    focusedContentColor = Primary
                )
            ) {
                Text(
                    text = stringResource(if (hasParentalPin) R.string.settings_change_pin else R.string.settings_set_pin),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
internal fun LiveTvChannelModeDialog(
    selectedMode: LiveTvChannelMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveTvChannelMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_tv_channel_mode),
        subtitle = stringResource(R.string.settings_live_tv_channel_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveTvChannelMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun LiveTvQuickFilterVisibilityDialog(
    selectedMode: LiveTvQuickFilterVisibilityMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveTvQuickFilterVisibilityMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_tv_quick_filter_visibility),
        subtitle = stringResource(R.string.settings_live_tv_quick_filter_visibility_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveTvQuickFilterVisibilityMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun SettingsSectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
internal fun VodViewModeDialog(
    selectedMode: VodViewMode,
    onDismiss: () -> Unit,
    onModeSelected: (VodViewMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_vod_view_mode),
        subtitle = stringResource(R.string.settings_vod_view_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VodViewMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun LiveChannelNumberingModeDialog(
    selectedMode: ChannelNumberingMode,
    onDismiss: () -> Unit,
    onModeSelected: (ChannelNumberingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_channel_numbering_mode),
        subtitle = stringResource(R.string.settings_live_channel_numbering_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelNumberingMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun LiveChannelGroupingModeDialog(
    selectedMode: LiveChannelGroupingMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveChannelGroupingMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_channel_grouping_mode),
        subtitle = stringResource(R.string.settings_live_channel_grouping_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveChannelGroupingMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun GroupedChannelLabelModeDialog(
    selectedMode: GroupedChannelLabelMode,
    onDismiss: () -> Unit,
    onModeSelected: (GroupedChannelLabelMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_grouped_channel_label_mode),
        subtitle = stringResource(R.string.settings_grouped_channel_label_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupedChannelLabelMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun LiveVariantPreferenceModeDialog(
    selectedMode: LiveVariantPreferenceMode,
    onDismiss: () -> Unit,
    onModeSelected: (LiveVariantPreferenceMode) -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_live_variant_preference_mode),
        subtitle = stringResource(R.string.settings_live_variant_preference_mode_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveVariantPreferenceMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == selectedMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(mode.labelResId()),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == selectedMode) Primary else OnBackground
                            )
                            Text(
                                text = stringResource(mode.descriptionResId()),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
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

@Composable
internal fun SettingsRow(label: String, value: String) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.15f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = {}
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
        }
    }
}

@Composable
internal fun ClickableSettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    indent: androidx.compose.ui.unit.Dp = 0.dp
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = if (enabled) Primary.copy(alpha = 0.15f) else Color.Transparent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = { if (enabled) onClick() }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp + indent, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) OnSurface else OnSurfaceDim
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Primary else OnSurfaceDim
            )
        }
    }
}

@Composable
internal fun SwitchSettingsRow(
    label: String,
    value: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    indent: androidx.compose.ui.unit.Dp = 0.dp
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = { if (enabled) onCheckedChange(!checked) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = if (enabled) Primary.copy(alpha = 0.15f) else Color.Transparent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = { if (enabled) onCheckedChange(!checked) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp + indent, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) OnSurface else OnSurfaceDim
                )
                Text(text = value, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            Switch(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled
            )
        }
    }
}

internal fun formatLatestReleaseLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val versionName = update.latestVersionName ?: return context.getString(R.string.settings_update_not_checked)
    val versionCodeSuffix = update.latestVersionCode?.let { " ($it)" }.orEmpty()
    return "$versionName$versionCodeSuffix"
}

internal fun formatUpdateStatusLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when {
        update.errorMessage != null -> context.getString(R.string.settings_update_status_check_failed)
        update.downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloading -> context.getString(R.string.settings_update_status_downloading)
        update.downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded && downloadedReleaseMatchesLatest -> context.getString(R.string.settings_update_status_ready_to_install)
        update.latestVersionName == null -> context.getString(R.string.settings_update_not_checked)
        update.isUpdateAvailable -> context.getString(R.string.settings_update_status_available)
        else -> context.getString(R.string.settings_update_status_current)
    }
}

internal fun formatUpdateCheckTimeLabel(timestamp: Long?, context: android.content.Context): String {
    if (timestamp == null || timestamp <= 0L) {
        return context.getString(R.string.settings_update_not_checked)
    }
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date(timestamp))
}

internal fun shouldShowUpdateDownloadAction(update: AppUpdateUiModel): Boolean {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when (update.downloadStatus) {
        com.streamvault.app.update.AppUpdateDownloadStatus.Downloading,
        com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded -> downloadedReleaseMatchesLatest || (update.isUpdateAvailable && !update.downloadUrl.isNullOrBlank())
        else -> update.isUpdateAvailable && !update.downloadUrl.isNullOrBlank()
    }
}

internal fun formatUpdateDownloadLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when (update.downloadStatus) {
        com.streamvault.app.update.AppUpdateDownloadStatus.Downloading -> context.getString(R.string.settings_update_download_in_progress)
        com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded -> {
            if (downloadedReleaseMatchesLatest) {
                context.getString(R.string.settings_update_install_action)
            } else {
                context.getString(R.string.settings_update_download_action)
            }
        }
        else -> context.getString(R.string.settings_update_download_action)
    }
}
@Composable
internal fun LiveTvQuickFiltersDialog(
    filters: List<String>,
    onDismiss: () -> Unit,
    onAddFilter: (String) -> Unit,
    onRemoveFilter: (String) -> Unit
) {
    var pendingFilter by rememberSaveable { mutableStateOf("") }

    PremiumDialog(
        title = stringResource(R.string.settings_live_tv_quick_filters_dialog_title),
        subtitle = stringResource(R.string.settings_live_tv_quick_filters_dialog_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.5f,
        content = {
            EpgSourceTextField(
                value = pendingFilter,
                onValueChange = { pendingFilter = it },
                placeholder = stringResource(R.string.settings_live_tv_quick_filters_placeholder)
            )
            PremiumDialogActionButton(
                label = stringResource(R.string.settings_live_tv_quick_filters_add),
                enabled = pendingFilter.isNotBlank(),
                onClick = {
                    onAddFilter(pendingFilter)
                    pendingFilter = ""
                }
            )
            if (filters.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_live_tv_quick_filters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_live_tv_quick_filters_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters, key = { it }) { filter ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = filter,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TvButton(onClick = { onRemoveFilter(filter) }) {
                                    Text(stringResource(R.string.settings_live_tv_quick_filters_remove))
                                }
                            }
                        }
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

internal fun formatLiveTvQuickFiltersValue(filters: List<String>, context: android.content.Context): String {
    if (filters.isEmpty()) {
        return context.getString(R.string.settings_live_tv_quick_filters_none)
    }
    return context.resources.getQuantityString(
        R.plurals.settings_live_tv_quick_filters_count,
        filters.size,
        filters.size
    )
}

@Composable
internal fun SettingsNavItem(
    label: String,
    badgeChar: String,
    accentColor: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.11f) else Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(focusRequester = focusRequester, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(
                        color = if (isSelected) Primary else Color.Transparent,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .size(28.dp)
                    .background(accentColor.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeChar,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Primary else OnBackground,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

internal fun formatTimestamp(
    timestampMs: Long,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    if (timestampMs <= 0L) return "--:--"
    return dateTimeFormat.format(java.util.Date(timestampMs))
}

internal data class AppLanguageOption(
    val tag: String,
    val label: String
)

internal fun supportedAppLanguages(systemDefaultLabel: String): List<AppLanguageOption> {
    val localeTags = listOf("system") + supportedAppLanguageTags()

    return localeTags.map { tag ->
        AppLanguageOption(
            tag = tag,
            label = if (tag == "system") {
                systemDefaultLabel
            } else {
                val locale = localeForLanguageTag(tag)
                locale.getDisplayLanguage(locale)
                    .replaceFirstChar { character ->
                        if (character.isLowerCase()) {
                            character.titlecase(locale)
                        } else {
                            character.toString()
                        }
                    }
            }
        )
    }
}

internal fun supportedAudioLanguages(autoLabel: String): List<AppLanguageOption> {
    return buildList {
        add(AppLanguageOption(tag = "auto", label = autoLabel))
        addAll(supportedAppLanguages(autoLabel).filterNot { it.tag == "system" })
    }
}

internal data class SubtitleScaleOption(
    val scale: Float,
    val label: (android.content.Context) -> String
)

internal data class SubtitleColorOption(
    val colorArgb: Int,
    val label: String
)

internal fun subtitleSizeOptions(): List<SubtitleScaleOption> {
    return listOf(
        SubtitleScaleOption(0.85f) { it.getString(R.string.settings_subtitle_size_small) },
        SubtitleScaleOption(1f) { it.getString(R.string.settings_subtitle_size_default) },
        SubtitleScaleOption(1.15f) { it.getString(R.string.settings_subtitle_size_large) },
        SubtitleScaleOption(1.3f) { it.getString(R.string.settings_subtitle_size_extra_large) }
    )
}

internal fun subtitleTextColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0xFFFFFFFF.toInt(), context.getString(R.string.settings_subtitle_color_white)),
        SubtitleColorOption(0xFFFFEB3B.toInt(), context.getString(R.string.settings_subtitle_color_yellow)),
        SubtitleColorOption(0xFF80DEEA.toInt(), context.getString(R.string.settings_subtitle_color_cyan)),
        SubtitleColorOption(0xFFA5D6A7.toInt(), context.getString(R.string.settings_subtitle_color_green))
    )
}

internal fun subtitleBackgroundColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0x00000000, context.getString(R.string.settings_subtitle_background_transparent)),
        SubtitleColorOption(0x80000000.toInt(), context.getString(R.string.settings_subtitle_background_dim)),
        SubtitleColorOption(0xCC000000.toInt(), context.getString(R.string.settings_subtitle_background_black)),
        SubtitleColorOption(0xCC102A43.toInt(), context.getString(R.string.settings_subtitle_background_blue))
    )
}

internal fun displayLanguageLabel(languageTag: String, defaultLabel: String): String {
    if (languageTag.isBlank() || languageTag == "system" || languageTag == "auto") return defaultLabel
    val locale = localeForLanguageTag(languageTag)
    if (locale.language.isBlank()) return defaultLabel
    return locale.getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

internal fun formatPlaybackTimerMinutesLabel(minutes: Int, context: android.content.Context): String {
    return if (minutes <= 0) {
        context.getString(R.string.settings_timer_off)
    } else {
        context.resources.getQuantityString(R.plurals.settings_timer_minutes, minutes, minutes)
    }
}

@Composable
internal fun PlaybackTimerPresetDialog(
    title: String,
    selectedMinutes: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = remember { listOf(0, 15, 30, 45, 60, 90, 120) }

    PremiumSelectionDialog(
        title = title,
        onDismiss = onDismiss
    ) {
        options.forEachIndexed { index, minutes ->
            LevelOption(
                level = index,
                text = formatPlaybackTimerMinutesLabel(minutes, context),
                currentLevel = if (selectedMinutes == minutes) index else -1,
                onSelect = { onSelect(minutes) }
            )
        }
    }
}

internal fun formatAudioVideoOffsetLabel(offsetMs: Int): String = when {
    offsetMs > 0 -> "+$offsetMs ms"
    offsetMs < 0 -> "$offsetMs ms"
    else -> "0 ms"
}

@Composable
internal fun AudioVideoOffsetValueDialog(
    title: String,
    subtitle: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue.coerceIn(-2_000, 2_000)) }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.42f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = formatAudioVideoOffsetLabel(value),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumDialogFooterButton(
                        label = "-50",
                        onClick = { value = (value - 50).coerceAtLeast(-2_000) }
                    )
                    PremiumDialogFooterButton(
                        label = "+50",
                        onClick = { value = (value + 50).coerceAtMost(2_000) }
                    )
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_reset),
                        onClick = { value = 0 }
                    )
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_save),
                    onClick = { onConfirm(value) }
                )
            }
        }
    )
}

@Composable
internal fun RecordingMetaPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .widthIn(min = 92.dp, max = 160.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun summarizeRecordingOutputPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return trimmed
    val decoded = runCatching { android.net.Uri.decode(trimmed) }.getOrDefault(trimmed)
    return decoded
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { decoded }
}

@Composable
internal fun RecordingActionButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 168.dp, max = 220.dp)
            .heightIn(min = 52.dp)
            .then(modifier),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = accent.copy(alpha = 0.14f),
            focusedContainerColor = accent.copy(alpha = 0.28f),
            contentColor = accent,
            focusedContentColor = accent
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun SimpleTextValueDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            EpgSourceTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = initialValue
            )
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogActionButton(
                    label = stringResource(R.string.settings_save),
                    onClick = { onConfirm(value.trim()) }
                )
            }
        }
    )
}

internal fun formatRecordingSourceType(sourceType: RecordingSourceType): String = when (sourceType) {
    RecordingSourceType.TS -> "TS"
    RecordingSourceType.HLS -> "HLS"
    RecordingSourceType.DASH -> "DASH"
    RecordingSourceType.UNKNOWN -> "Auto"
}

internal fun formatRecordingFailureCategory(category: RecordingFailureCategory): String = when (category) {
    RecordingFailureCategory.NONE -> "None"
    RecordingFailureCategory.NETWORK -> "Network"
    RecordingFailureCategory.STORAGE -> "Storage"
    RecordingFailureCategory.AUTH -> "Auth"
    RecordingFailureCategory.TOKEN_EXPIRED -> "Token"
    RecordingFailureCategory.DRM_UNSUPPORTED -> "DRM"
    RecordingFailureCategory.FORMAT_UNSUPPORTED -> "Format"
    RecordingFailureCategory.SCHEDULE_CONFLICT -> "Conflict"
    RecordingFailureCategory.PROVIDER_LIMIT -> "Connection limit"
    RecordingFailureCategory.UNKNOWN -> "Unknown"
}

internal fun playerTimeoutOptions(): List<Int> = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30)

internal fun formatDecoderModeLabel(mode: DecoderMode, context: android.content.Context): String {
    return when (mode) {
        DecoderMode.AUTO -> context.getString(R.string.settings_decoder_auto)
        DecoderMode.HARDWARE -> context.getString(R.string.settings_decoder_hardware)
        DecoderMode.SOFTWARE -> context.getString(R.string.settings_decoder_software)
        DecoderMode.COMPATIBILITY -> context.getString(R.string.settings_decoder_compatibility)
    }
}

internal fun formatSurfaceModeLabel(
    mode: com.streamvault.domain.model.PlayerSurfaceMode,
    context: android.content.Context
): String = when (mode) {
    com.streamvault.domain.model.PlayerSurfaceMode.AUTO -> context.getString(R.string.settings_surface_auto)
    com.streamvault.domain.model.PlayerSurfaceMode.SURFACE_VIEW -> context.getString(R.string.settings_surface_surface_view)
    com.streamvault.domain.model.PlayerSurfaceMode.TEXTURE_VIEW -> context.getString(R.string.settings_surface_texture_view)
}

internal fun formatTimeoutSecondsLabel(seconds: Int, context: android.content.Context): String {
    return context.resources.getQuantityString(
        R.plurals.settings_timeout_seconds,
        seconds,
        seconds
    )
}

internal fun formatSubtitleSizeLabel(scale: Float, context: android.content.Context): String {
    return subtitleSizeOptions().firstOrNull { it.scale == scale }?.label?.invoke(context)
        ?: context.getString(R.string.settings_subtitle_size_default)
}

internal fun formatSubtitleColorLabel(colorArgb: Int, options: List<SubtitleColorOption>): String {
    return options.firstOrNull { it.colorArgb == colorArgb }?.label ?: options.first().label
}

internal fun formatQualityCapLabel(maxHeight: Int?, autoLabel: String): String {
    return maxHeight?.let { "${it}p" } ?: autoLabel
}

internal fun formatSpeedTestValueLabel(speedTest: InternetSpeedTestUiModel): String {
    return String.format(Locale.getDefault(), "%.1f Mbps", speedTest.megabitsPerSecond)
}

internal fun formatSpeedTestSummary(
    speedTest: InternetSpeedTestUiModel,
    context: android.content.Context,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    val transportLabel = when (speedTest.transportLabel) {
        InternetSpeedTestTransport.WIFI.name -> context.getString(R.string.settings_speed_test_transport_wifi)
        InternetSpeedTestTransport.ETHERNET.name -> context.getString(R.string.settings_speed_test_transport_ethernet)
        InternetSpeedTestTransport.CELLULAR.name -> context.getString(R.string.settings_speed_test_transport_cellular)
        InternetSpeedTestTransport.OTHER.name -> context.getString(R.string.settings_speed_test_transport_other)
        else -> context.getString(R.string.settings_speed_test_transport_unknown)
    }
    val measuredAtLabel = formatTimestamp(speedTest.measuredAtMs, dateTimeFormat)
    return if (speedTest.isEstimated) {
        context.getString(R.string.settings_speed_test_summary_estimated, transportLabel, measuredAtLabel)
    } else {
        context.getString(R.string.settings_speed_test_summary_measured, transportLabel, measuredAtLabel)
    }
}

internal fun sortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return when (mode) {
        CategorySortMode.DEFAULT -> context.getString(R.string.settings_category_sort_default)
        CategorySortMode.TITLE_ASC -> context.getString(R.string.settings_category_sort_az)
        CategorySortMode.TITLE_DESC -> context.getString(R.string.settings_category_sort_za)
        CategorySortMode.COUNT_DESC -> context.getString(R.string.settings_category_sort_most_items)
        CategorySortMode.COUNT_ASC -> context.getString(R.string.settings_category_sort_least_items)
    }
}

internal fun formatCategorySortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return sortModeLabel(mode, context)
}

internal fun categoryTypeLabel(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_sort_live)
        ContentType.MOVIE -> context.getString(R.string.settings_category_sort_movies)
        ContentType.SERIES -> context.getString(R.string.settings_category_sort_series)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_sort_series)
    }
}

internal fun categoryTypeDescription(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_type_live_description)
        ContentType.MOVIE -> context.getString(R.string.settings_category_type_movies_description)
        ContentType.SERIES -> context.getString(R.string.settings_category_type_series_description)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_type_series_description)
    }
}

internal fun LiveTvChannelMode.labelResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro
}

internal fun LiveTvChannelMode.descriptionResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable_desc
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact_desc
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro_desc
}

internal fun LiveTvQuickFilterVisibilityMode.labelResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always
}

internal fun LiveTvQuickFilterVisibilityMode.descriptionResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide_desc
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available_desc
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always_desc
}

internal fun VodViewMode.labelResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic
}

internal fun VodViewMode.descriptionResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern_desc
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic_desc
}

internal fun ChannelNumberingMode.labelResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden
}

internal fun ChannelNumberingMode.descriptionResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group_desc
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider_desc
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden_desc
}

internal fun LiveChannelGroupingMode.labelResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants
}

internal fun LiveChannelGroupingMode.descriptionResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped_desc
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants_desc
}

internal fun GroupedChannelLabelMode.labelResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid
}

internal fun GroupedChannelLabelMode.descriptionResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical_desc
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original_desc
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid_desc
}

internal fun LiveVariantPreferenceMode.labelResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first
}

internal fun LiveVariantPreferenceMode.descriptionResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality_desc
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only_desc
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced_desc
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first_desc
}

