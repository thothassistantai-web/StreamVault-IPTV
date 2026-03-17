package com.streamvault.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.AppShapes
import com.streamvault.app.ui.design.AppTypography
import com.streamvault.app.ui.design.LocalAppShapes
import com.streamvault.app.ui.design.LocalAppSpacing

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Brand,
    onPrimary = OnPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    background = AppColors.CanvasElevated,
    onBackground = AppColors.TextPrimary,
    error = AppColors.Live,
    onError = OnPrimary
)

@Composable
fun StreamVaultTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppSpacing provides com.streamvault.app.ui.design.AppSpacing(),
        LocalAppShapes provides AppShapes()
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
