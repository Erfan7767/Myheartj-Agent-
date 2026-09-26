package com.arenaai.duagents.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Engineering decision: a fixed dark, high-contrast theme — the app is a live voice
// console; a dark, calm canvas makes the two agent colors (teal/rose) instantly readable.
val IrfanColor = Color(0xFF26C6B9)
val ElenaColor = Color(0xFFE46FA0)
val DeepBackground = Color(0xFF0E1116)
val SurfaceDark = Color(0xFF151A21)
val SurfaceElevated = Color(0xFF1C232C)
val TextPrimary = Color(0xFFE9EEF3)
val TextSecondary = Color(0xFF93A1AF)

private val DarkColors = darkColorScheme(
    primary = IrfanColor,
    onPrimary = Color(0xFF00201C),
    secondary = ElenaColor,
    onSecondary = Color(0xFF2B0A1A),
    tertiary = Color(0xFF8FB8DE),
    background = DeepBackground,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF3A4652),
    error = Color(0xFFEF7B7B)
)

@Composable
fun DuAgentsTheme(content: @Composable () -> Unit) {
    // Dark palette is the designed identity; light-system devices still get full contrast.
    isSystemInDarkTheme() // observed for platform consistency, palette intentionally stable
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
