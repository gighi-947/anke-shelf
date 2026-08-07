package io.github.gighi947.ankeshelf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 桌面 v1.2.0 默认色板的安卓侧占位实现。
 * 完整 9 套 PALETTES 与四自定义色移植在 M2（阅读 MVP）完成，
 * 届时与阅读器 WebView CSS 变量保持同一数据源。
 */
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

@Composable
fun AnkeShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
