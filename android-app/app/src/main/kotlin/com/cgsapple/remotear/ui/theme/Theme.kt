package com.cgsapple.remotear.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = Background,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnBackground,
    secondary = SecondaryGreen,
    onSecondary = Background,
    tertiary = TertiaryWarning,
    onTertiary = Background,
    background = Background,
    onBackground = OnBackground,
    surface = SurfaceDark,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error = ErrorRed,
    onError = OnBackground,
    outline = Outline,
)

@Composable
fun RemoteArTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = RemoteArTypography,
        content = content,
    )
}
