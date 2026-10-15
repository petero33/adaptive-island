package dev.personal.adaptiveisland.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IslandDarkScheme = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color(0xFF0B0B12),
    secondary = Color(0xFF8A93A8),
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF14141C),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = Color(0xFFA8AFC2),
    outline = Color(0xFF2E2E3A),
)

@Composable
fun AdaptiveIslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IslandDarkScheme,
        content = content,
    )
}
