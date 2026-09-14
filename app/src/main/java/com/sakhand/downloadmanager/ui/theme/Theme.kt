package com.sakhand.downloadmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SakhandColorScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2A6B),
    onPrimaryContainer = Color(0xFFE9DFFC),
    secondary = Blue,
    onSecondary = Color.White,
    tertiary = Pink,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = Color.Black,
    outline = CardBorder,
    outlineVariant = CardBorder
)

@Composable
fun SakhandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SakhandColorScheme,
        typography = AppTypography,
        content = content
    )
}
