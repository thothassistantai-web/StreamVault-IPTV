package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.design.LocalAppSpacing

@Composable
fun LibraryBrowseScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    railContent: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = LocalAppSpacing.current
    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        header = header
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(312.dp)
                    .fillMaxHeight()
            ) {
                railContent()
            }
            Spacer(modifier = Modifier.width(spacing.lg))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                content()
            }
        }
    }
}

@Composable
fun CategoryRailPanel(
    title: String,
    searchValue: String,
    onSearchValueChange: (String) -> Unit,
    searchPlaceholder: String,
    modifier: Modifier = Modifier,
    searchFocusRequester: FocusRequester = FocusRequester(),
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    content: LazyListScope.() -> Unit
) {
    val spacing = LocalAppSpacing.current
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppColors.SurfaceElevated,
                            AppColors.Surface
                        )
                    )
                )
                .padding(horizontal = spacing.md, vertical = spacing.md)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            SearchInput(
                value = searchValue,
                onValueChange = onSearchValueChange,
                placeholder = searchPlaceholder,
                focusRequester = searchFocusRequester
            )
            if (headerContent != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                headerContent()
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = contentPadding,
                content = content
            )
        }
    }
}

@Composable
fun BrowseSearchLaunchCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(22.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
        }
    }
}

@Composable
fun BrowseHeroPanel(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    imageUrl: String? = null,
    metadata: List<String> = emptyList(),
    actionLabel: String
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(28.dp)
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Gradient fallback always visible; covered by AsyncImage on successful load
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                AppColors.Canvas,
                                AppColors.CanvasElevated,
                                AppColors.SurfaceEmphasis
                            )
                        )
                    )
            )
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = rememberCrossfadeImageModel(imageUrl),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AppColors.HeroTop,
                                Color.Transparent,
                                AppColors.HeroBottom
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                eyebrow?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.BrandStrong
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (metadata.isNotEmpty()) {
                    ContentMetadataStrip(values = metadata)
                }
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Brand,
                        contentColor = Color.Black
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
