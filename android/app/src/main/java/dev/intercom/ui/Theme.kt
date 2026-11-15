package dev.intercom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Monochrome: black on white in light mode, white on black in dark mode. */
private val Light = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED), onPrimaryContainer = Color.Black,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E4), onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD6D6D6),
)

private val Dark = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F1F1F), onPrimaryContainer = Color.White,
    secondary = Color.White, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2C2C2C), onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFF333333),
)

@Composable
fun IntercomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
