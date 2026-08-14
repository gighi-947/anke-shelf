package io.github.gighi947.ankeshelf.ui.settings

import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.BuildConfig
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.AnnotationStore
import io.github.gighi947.ankeshelf.data.EnrichedStats
import io.github.gighi947.ankeshelf.data.Settings
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.ui.theme.PALETTES
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.ReaderPalette
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.effectivePalette
import io.github.gighi947.ankeshelf.ui.theme.formatDuration
import io.github.gighi947.ankeshelf.ui.theme.hexColor
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.service.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import kotlin.math.roundToInt
/* ---------------- 外观 ---------------- */

@Composable
internal fun AppearancePanel(
    data: SettingsData,
    commit: (SettingsPatch) -> Unit,
    onPickColor: (String) -> Unit,
    context: Context,
) {
    SettingsList {
        SettingsSection("主题") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                THEME_MODES.forEachIndexed { i, (mode, label) ->
                    val selected = if (mode == "system") {
                        data.theme_mode == "system"
                    } else {
                        data.theme_mode == mode
                    }
                    SegmentedButton(
                        selected = selected,
                        onClick = {
                            if (mode == "system") {
                                commit(SettingsPatch(theme_mode = "system"))
                            } else {
                                commit(SettingsPatch(theme_mode = "", theme = mode))
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i,
                            count = THEME_MODES.size,
                            baseShape = AnkeRadius.small,
                        ),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }

        SettingsSection("亮度") {
            SettingsRow("亮度", "叠加一层黑色遮罩，适合夜间调低屏幕亮度") {
                BrightnessSlider(data, commit)
            }
        }

        SettingsSection("界面") {
            SettingsRow("界面字号", "整体调整应用界面文字大小（阅读正文不受影响）") {
                var value by remember(data.ui_font_scale) {
                    mutableFloatStateOf(data.ui_font_scale.toFloat())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        onValueChangeFinished = { commit(SettingsPatch(ui_font_scale = value.toDouble())) },
                        valueRange = 0.85f..1.25f,
                        steps = 7,
                        modifier = Modifier.width(150.dp),
                    )
                    Text(
                        "${(value * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = AnkeSpacing.sm),
                    )
                }
            }
        }

        SettingsSection("预设色板") {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                PALETTES.forEach { p ->
                    val active = data.custom_bg == p.bg && data.custom_text == p.text &&
                        data.custom_primary == p.primary && data.custom_accent == p.accent
                    PaletteCard(
                        palette = p,
                        active = active,
                        onClick = {
                            commit(
                                SettingsPatch(
                                    custom_bg = p.bg,
                                    custom_text = p.text,
                                    custom_primary = p.primary,
                                    custom_accent = p.accent,
                                ),
                            )
                        },
                    )
                }
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    commit(
                        SettingsPatch(
                            custom_bg = "",
                            custom_text = "",
                            custom_primary = "",
                            custom_accent = "",
                        ),
                    )
                }) { Text("恢复跟随主题") }
            }
        }

        SettingsSection("自定义颜色") {
            CUSTOM_COLOR_META.forEach { (key, label, desc) ->
                SettingsRow(label, desc) {
                    val value = when (key) {
                        "custom_bg" -> data.custom_bg
                        "custom_primary" -> data.custom_primary
                        "custom_accent" -> data.custom_accent
                        else -> data.custom_text
                    }
                    ColorDot(value = value, onClick = { onPickColor(key) })
                }
            }
        }

        SettingsSection("主题预览") {
            ThemePreview(data)
        }
    }
}

@Composable
internal fun BrightnessSlider(data: SettingsData, commit: (SettingsPatch) -> Unit) {
    var value by remember(data.brightness) { mutableFloatStateOf(data.brightness.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { commit(SettingsPatch(brightness = value.toDouble())) },
            valueRange = 0f..0.7f,
            modifier = Modifier.width(170.dp),
        )
        Text(
            "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = AnkeSpacing.sm),
        )
    }
}

@Composable
internal fun PaletteCard(palette: ReaderPalette, active: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .width(132.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (active) primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(AnkeSpacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs)) {
                Dot(hexColor(palette.bg) ?: Color.Gray)
                Dot(hexColor(palette.text) ?: Color.Gray)
                Dot(hexColor(palette.primary) ?: Color.Gray)
            }
            Text(
                palette.name,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = AnkeSpacing.sm),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color(0x33000000), CircleShape),
    )
}

@Composable
internal fun ColorDot(value: String, onClick: () -> Unit) {
    val color = hexColor(value)
    Surface(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        shape = CircleShape,
        color = color ?: MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (color == null) {
                Text("跟随", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun ColorPickerSheet(
    key: String,
    current: String,
    onApply: (String) -> Unit,
) {
    var hex by remember(current) { mutableStateOf(current.removePrefix("#")) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AnkeSpacing.xl)
                .padding(bottom = AnkeSpacing.xxl),
    ) {
        Text("选择颜色", style = MaterialTheme.typography.titleMedium)
        Text(
            CUSTOM_COLOR_META.firstOrNull { it.first == key }?.third ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AnkeSpacing.xxs),
        )
        FlowRow(
            modifier = Modifier.padding(top = AnkeSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
        ) {
            (COLOR_SWATCHES[key] ?: listOf("")).forEach { value ->
                val selected = value.removePrefix("#") == current.removePrefix("#")
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onApply(value) }
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            CircleShape,
                        ),
                    shape = CircleShape,
                    color = hexColor(value) ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            selected -> Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = if (hexColor(value) == null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    contentOnColor(hexColor(value) ?: Color.White)
                                },
                            )
                            value.isEmpty() -> Text("跟随", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = AnkeSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            OutlinedTextField(
                value = hex,
                onValueChange = { input ->
                    hex = input.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)
                },
                label = { Text("#RRGGBB") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            )
            Button(
            shape = MaterialTheme.shapes.small,
                onClick = {
                    val normalized = if (hex.length == 3) {
                        hex.map { "$it$it" }.joinToString("")
                    } else {
                        hex
                    }
                    if (normalized.length == 6 && hexColor("#$normalized") != null) {
                        onApply("#$normalized")
                    }
                },
                enabled = hex.length == 3 || hex.length == 6,
            ) { Text("应用") }
        }
        TextButton(
            onClick = { onApply("") },
            modifier = Modifier.padding(top = AnkeSpacing.xs),
        ) { Text("恢复跟随主题") }
    }
}

internal fun contentOnColor(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF171717) else Color.White

@Composable
internal fun ThemePreview(data: SettingsData) {
    val p = effectivePalette(data, isSystemInDarkTheme())
    val bg = hexColor(p.bg) ?: Color.White
    val fg = hexColor(p.text) ?: Color.Black
    val primary = hexColor(p.primary) ?: MaterialTheme.colorScheme.primary
    val previewText = buildAnnotatedString {
        append("这是一段用于预览主题效果的示例文字。")
        withStyle(SpanStyle(color = Color(0xFFE11D48))) { append("彩色字体保留原色") }
        append("，仅默认黑/白文字跟随主题。")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = bg,
    ) {
        Column(modifier = Modifier.padding(AnkeSpacing.lg)) {
            Text(
                "安科书架",
                style = MaterialTheme.typography.titleMedium,
                color = primary,
            )
            Text(
                previewText,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                modifier = Modifier.padding(top = AnkeSpacing.xs),
            )
        }
    }
}
