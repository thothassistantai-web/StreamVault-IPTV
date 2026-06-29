package com.streamvault.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.PlayerRenderView
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode

@Composable
fun LivePreviewVideoSurface(
    playerEngine: PlayerEngine?,
    isLoading: Boolean,
    errorMessage: String?,
    errorCode: String?,
    onPreviewClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showOpenHint: Boolean = false,
) {
    val renderSurfaceType by (playerEngine?.renderSurfaceType)?.collectAsStateWithLifecycle(
        initialValue = PlayerRenderSurfaceType.SURFACE_VIEW
    ) ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(PlayerRenderSurfaceType.SURFACE_VIEW) }

    val previewClickable = onPreviewClick != null && errorMessage == null && playerEngine != null

    TvClickableSurface(
        onClick = { if (previewClickable) onPreviewClick?.invoke() },
        enabled = previewClickable,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.12f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playerEngine != null && errorMessage == null -> {
                    PlayerRenderView(
                        playerEngine = playerEngine,
                        resizeMode = PlayerSurfaceResizeMode.FIT,
                        surfaceType = renderSurfaceType,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        if (!errorCode.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.live_preview_error_code, errorCode),
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                isLoading -> Unit
                else -> {
                    Text(
                        text = stringResource(R.string.live_preview_placeholder_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            if (isLoading) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.live_preview_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }

            if (showOpenHint && previewClickable) {
                Text(
                    text = stringResource(R.string.live_preview_tap_to_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
