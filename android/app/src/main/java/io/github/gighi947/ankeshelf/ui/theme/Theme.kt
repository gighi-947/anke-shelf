package io.github.gighi947.ankeshelf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 阅读主题三套色板（与阅读器 WebView 的 readerTheme 同源）。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF8B5A2B),
    background = Color(0xFFF7F3EC),
    surface = Color(0xFFFFFCF6),
    onBackground = Color(0xFF201A15),
    onSurface = Color(0xFF201A15),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0B684),
    background = Color(0xFF171412),
    surface = Color(0xFF1F1B18),
    onBackground = Color(0xFFE9E2D8),
    onSurface = Color(0xFFE9E2D8),
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF8B5A2B),
    background = Color(0xFFF4ECD8),
    surface = Color(0xFFFBF5E6),
    onBackground = Color(0xFF3B3226),
    onSurface = Color(0xFF3B3226),
)

@Composable
fun AnkeShelfTheme(
    themeName: String = "dark",
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeName.lowercase()) {
        "light" -> LightColors
        "sepia" -> SepiaColors
        else -> DarkColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
