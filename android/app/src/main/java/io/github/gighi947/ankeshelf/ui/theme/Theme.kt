package io.github.gighi947.ankeshelf.ui.theme

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import io.github.gighi947.ankeshelf.data.SettingsData
import kotlin.math.roundToInt

/** 预置色板（与桌面 web/js/theme.js PALETTES 逐项同源）。 */
data class ReaderPalette(
    val id: String,
    val name: String,
    val bg: String,
    val text: String,
    val primary: String,
    val accent: String,
)

val PALETTES: List<ReaderPalette> = listOf(
    ReaderPalette("default-light", "默认浅色", "#ffffff", "#171717", "#0066cc", "#0066cc"),
    ReaderPalette("sepia", "羊皮纸", "#f1e8d0", "#5b4636", "#008b8b", "#008b8b"),
    ReaderPalette("night", "夜间", "#222222", "#e0e0e0", "#77bbee", "#77bbee"),
    ReaderPalette("solarized-light", "Solarized 浅", "#fdf6e3", "#657b83", "#268bd2", "#2aa198"),
    ReaderPalette("solarized-dark", "Solarized 深", "#002b36", "#93a1a1", "#268bd2", "#2aa198"),
    ReaderPalette("nord-light", "Nord 浅", "#eceff4", "#2e3440", "#5e81ac", "#88c0d0"),
    ReaderPalette("nord-dark", "Nord 深", "#2e3440", "#eceff4", "#88c0d0", "#81a1c1"),
    ReaderPalette("green-eye", "护眼绿", "#dcedd8", "#234a2b", "#2f855a", "#38a169"),
    ReaderPalette("ink-blue", "墨蓝", "#1e293b", "#e2e8f0", "#60a5fa", "#38bdf8"),
)

/** '#rgb' / '#rrggbb' → Compose Color；非法输入返回 null。 */
fun hexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val full = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        else -> return null
    }
    if (!Regex("^[0-9a-fA-F]{6}$").matches(full)) return null
    return try {
        Color(AndroidColor.parseColor("#$full"))
    } catch (_: Exception) {
        null
    }
}

private fun luminanceOf(c: Color): Double = c.luminance().toDouble()

/** 与桌面 shade() 相同：浅底向黑压暗、深底向白提亮 pct。 */
fun shade(base: Color, pct: Float): Color {
    val target = if (luminanceOf(base) > 0.5) Color.Black else Color.White
    return lerp(base, target, pct.coerceIn(0f, 1f))
}

/** 与桌面 contentColor() 相同：亮度 > 0.55 用深色前景，否则白色。 */
fun contentOn(color: Color): Color =
    if (luminanceOf(color) > 0.55) Color(0xFF171717) else Color.White

private fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t.coerceIn(0f, 1f))

/** 解析实际生效主题：system → 系统深浅；固定模式 → 模式本身；否则 theme。 */
fun resolveThemeName(settings: SettingsData, systemDark: Boolean): String {
    val mode = settings.theme_mode
    if (mode == "light" || mode == "sepia" || mode == "dark") return mode
    if (mode == "system") return if (systemDark) "dark" else "light"
    return if (settings.theme in setOf("light", "sepia", "dark")) settings.theme else "light"
}

/** 基础色板：light → 默认浅色，sepia → 羊皮纸，dark → 夜间。 */
fun basePalette(themeName: String): ReaderPalette = when (themeName) {
    "sepia" -> PALETTES[1]
    "dark" -> PALETTES[2]
    else -> PALETTES[0]
}

/** 应用自定义四色覆盖（空串 = 跟随主题）。 */
fun effectivePalette(settings: SettingsData, systemDark: Boolean): ReaderPalette {
    val base = basePalette(resolveThemeName(settings, systemDark))
    return base.copy(
        bg = settings.custom_bg.ifBlank { base.bg },
        text = settings.custom_text.ifBlank { base.text },
        primary = settings.custom_primary.ifBlank { base.primary },
        accent = settings.custom_accent.ifBlank {
            if (settings.custom_primary.isNotBlank()) settings.custom_primary else base.accent
        },
    )
}

/** 由背景/文字/主题色/强调色派生完整 M3 色板（浅色与深色两套层次）。 */
fun buildReaderColorScheme(
    bg: Color,
    text: Color,
    primary: Color,
    accent: Color,
    dark: Boolean,
) = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = contentOn(primary),
        primaryContainer = mix(primary, bg, 0.74f),
        onPrimaryContainer = mix(primary, text, 0.28f),
        secondary = accent,
        onSecondary = contentOn(accent),
        secondaryContainer = mix(accent, bg, 0.74f),
        onSecondaryContainer = mix(accent, text, 0.28f),
        tertiary = mix(primary, accent, 0.5f),
        onTertiary = contentOn(mix(primary, accent, 0.5f)),
        tertiaryContainer = mix(mix(primary, accent, 0.5f), bg, 0.74f),
        onTertiaryContainer = mix(mix(primary, accent, 0.5f), text, 0.28f),
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = mix(bg, Color.White, 0.10f),
        onSurfaceVariant = mix(text, bg, 0.32f),
        surfaceContainerLowest = mix(bg, Color.White, 0.02f),
        surfaceContainerLow = mix(bg, Color.White, 0.05f),
        surfaceContainer = mix(bg, Color.White, 0.08f),
        surfaceContainerHigh = mix(bg, Color.White, 0.12f),
        surfaceContainerHighest = mix(bg, Color.White, 0.16f),
        outline = mix(text, bg, 0.42f),
        outlineVariant = mix(text, bg, 0.72f),
        inverseSurface = Color(0xFFE0E3E1),
        inverseOnSurface = Color(0xFF2D3130),
        inversePrimary = mix(primary, Color.White, 0.30f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFB4AB),
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = contentOn(primary),
        primaryContainer = mix(primary, bg, 0.86f),
        onPrimaryContainer = mix(primary, text, 0.45f),
        secondary = accent,
        onSecondary = contentOn(accent),
        secondaryContainer = mix(accent, bg, 0.86f),
        onSecondaryContainer = mix(accent, text, 0.45f),
        tertiary = mix(primary, accent, 0.5f),
        onTertiary = contentOn(mix(primary, accent, 0.5f)),
        tertiaryContainer = mix(mix(primary, accent, 0.5f), bg, 0.86f),
        onTertiaryContainer = mix(mix(primary, accent, 0.5f), text, 0.45f),
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = mix(bg, text, 0.06f),
        onSurfaceVariant = mix(text, bg, 0.30f),
        surfaceContainerLowest = mix(bg, text, 0.02f),
        surfaceContainerLow = mix(bg, text, 0.04f),
        surfaceContainer = mix(bg, text, 0.07f),
        surfaceContainerHigh = mix(bg, text, 0.10f),
        surfaceContainerHighest = mix(bg, text, 0.14f),
        outline = mix(text, bg, 0.50f),
        outlineVariant = mix(text, bg, 0.82f),
        inverseSurface = Color(0xFF2D3130),
        inverseOnSurface = Color(0xFFEFF1F0),
        inversePrimary = mix(primary, Color.White, 0.30f),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}

@Composable
fun AnkeShelfTheme(
    settings: SettingsData,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = remember(settings, systemDark) { effectivePalette(settings, systemDark) }
    val bg = remember(palette.bg) { hexColor(palette.bg) ?: Color(0xFF222222) }
    val text = remember(palette.text) { hexColor(palette.text) ?: Color(0xFFE0E0E0) }
    val primary = remember(palette.primary) { hexColor(palette.primary) ?: Color(0xFF77BBEE) }
    val accent = remember(palette.accent) { hexColor(palette.accent) ?: Color(0xFF77BBEE) }
    val dark = resolveThemeName(settings, systemDark) == "dark"

    val context = LocalContext.current
    val colorScheme = if (
        settings.theme_mode == "system" &&
        settings.custom_bg.isBlank() &&
        settings.custom_text.isBlank() &&
        settings.custom_primary.isBlank() &&
        settings.custom_accent.isBlank() &&
        Build.VERSION.SDK_INT >= 31
    ) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(palette, dark) { buildReaderColorScheme(bg, text, primary, accent, dark) }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AnkeShapes,
        content = content,
    )
}

/** 阅读器 WebView 配色（与 Compose 色板同源；M4 起支持自定义四色）。 */
data class ReaderThemeColors(
    val background: String,
    val text: String,
    val accent: String,
)

fun readerTheme(settings: SettingsData, systemDark: Boolean = false): ReaderThemeColors {
    val p = effectivePalette(settings, systemDark)
    return ReaderThemeColors(
        background = p.bg,
        text = p.text,
        accent = p.accent,
    )
}

/** 时长/日期显示与桌面 Util.fmtDuration/fmtDate 一致。 */
fun formatDuration(secs: Int): String {
    val s = maxOf(0, secs)
    if (s < 60) return "$s 秒"
    val mins = s / 60
    if (mins < 60) return "$mins 分钟"
    val h = mins / 60
    val m = mins % 60
    return if (m != 0) "$h 小时 $m 分" else "$h 小时"
}

fun formatDate(iso: String): String {
    if (iso.isBlank()) return "—"
    return try {
        val d = java.time.OffsetDateTime.parse(iso)
        "${d.monthValue}月${d.dayOfMonth}日"
    } catch (_: Exception) {
        try {
            val d = java.time.LocalDate.parse(iso.substring(0, 10))
            "${d.monthValue}月${d.dayOfMonth}日"
        } catch (_: Exception) {
            "—"
        }
    }
}
