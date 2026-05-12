package com.streamvault.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.extractProgressFraction
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary

@Composable
internal fun SyncingOverlay(
    isSyncing: Boolean,
    providerName: String? = null,
    progress: String? = null
) {
    if (!isSyncing) return

    BackHandler(enabled = true) {}

    val overlayFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { overlayFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = true, onClick = {})
            .focusRequester(overlayFocusRequester)
            .focusable()
            .onKeyEvent { true },
        contentAlignment = Alignment.Center
    ) {
        val fraction = progress?.let { extractProgressFraction(it) }
        val animatedFraction by animateFloatAsState(
            targetValue = fraction ?: 0f,
            animationSpec = tween(durationMillis = 400),
            label = "syncFraction"
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(min = 360.dp, max = 520.dp)
        ) {
            CircularProgressIndicator(color = Primary)
            Text(
                text = stringResource(R.string.settings_syncing_title),
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface
            )
            Text(
                text = providerName ?: stringResource(R.string.settings_syncing_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            progress?.let { message ->
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { animatedFraction },
                        color = Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

