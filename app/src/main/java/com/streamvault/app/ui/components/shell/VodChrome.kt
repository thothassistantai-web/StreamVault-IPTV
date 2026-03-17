package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.components.ChipRowItem
import com.streamvault.app.ui.components.ChipRowSection
import com.streamvault.app.ui.design.AppColors.Brand as Primary
import com.streamvault.app.ui.design.AppColors.Focus as FocusBorder
import com.streamvault.app.ui.design.AppColors.SurfaceElevated as SurfaceElevated
import com.streamvault.app.ui.design.AppColors.SurfaceEmphasis as SurfaceHighlight
import com.streamvault.app.ui.design.AppColors.TextPrimary as TextPrimary
import com.streamvault.app.ui.design.AppColors.TextTertiary as OnSurfaceDim
import androidx.compose.foundation.BorderStroke
import com.streamvault.app.R
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton

@Composable
fun VodSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    seeAllLabel: String = stringResource(R.string.action_see_all)
) {
    AppSectionHeader(
        title = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        actionLabel = onSeeAll?.let { seeAllLabel },
        onActionClick = onSeeAll,
        actionContentColor = AppColors.TextSecondary
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VodActionChipRow(
    actions: List<VodActionChip>,
    selectedKey: String? = null,
    modifier: Modifier = Modifier
) {
    ChipRowSection(
        chips = actions.map { action ->
            ChipRowItem(
                key = action.key,
                label = action.label,
                supportingText = action.detail,
                onClick = action.onClick
            )
        },
        selectedKey = selectedKey,
        modifier = modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        chipHorizontalPadding = 14,
        supportingTextStyle = MaterialTheme.typography.labelSmall,
        supportingTextMaxLines = 1,
        focusedContainerBoostWhenSelected = true
    )
}

@Composable
fun VodHeroStrip(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(132.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceHighlight,
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(22.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                colors = SurfaceDefaults.colors(containerColor = Primary)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Black,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

data class VodActionChip(
    val key: String,
    val label: String,
    val detail: String? = null,
    val onClick: () -> Unit
)

data class VodCategoryOption(
    val name: String,
    val count: Int,
    val onClick: () -> Unit
)

@Composable
fun VodCategoryPickerDialog(
    title: String,
    subtitle: String,
    categories: List<VodCategoryOption>,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val filteredCategories = remember(categories, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) categories
        else categories.filter { it.name.contains(normalized, ignoreCase = true) }
    }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            SearchInput(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.vod_category_picker_search_placeholder),
                focusRequester = searchFocusRequester,
                modifier = Modifier.fillMaxWidth()
            )

            if (filteredCategories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.vod_category_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredCategories, key = { it.name }) { category ->
                        Surface(
                            onClick = {
                                category.onClick()
                                onDismiss()
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = SurfaceElevated,
                                focusedContainerColor = SurfaceHighlight
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, FocusBorder),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = category.count.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.category_options_cancel),
                onClick = onDismiss
            )
        }
    )
}
