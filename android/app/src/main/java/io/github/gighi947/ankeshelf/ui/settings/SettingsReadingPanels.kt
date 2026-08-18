package io.github.gighi947.ankeshelf.ui.settings

import android.content.Context
import android.widget.Toast
import io.github.gighi947.ankeshelf.data.queryDisplayName
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
/* ---------------- 阅读 ---------------- */

@Composable
internal fun ReadingPanel(
    data: SettingsData,
    commit: (SettingsPatch) -> Unit,
    context: Context,
    fontsDir: File,
) {
    var fontsTick by remember { mutableIntStateOf(0) }
    val importedFonts = remember(fontsTick) {
        fontsDir.listFiles()
            ?.filter { it.extension.equals("ttf", true) || it.extension.equals("otf", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    val fontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val displayName = queryDisplayName(context.contentResolver, it)
                ?: "font-${System.currentTimeMillis()}.ttf"
            val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "font.ttf" }
            val target = File(fontsDir, safeName)
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                commit(SettingsPatch(custom_font = target.name))
                fontsTick++
            } catch (_: Exception) {
            }
        }
    }

    SettingsList {
        SettingsSection("排版") {
            SettingsRow("字号", "正文显示大小") {
                RangeSliderRow(
                    value = data.font_size.toFloat(),
                    range = 12f..36f,
                    steps = 23,
                    format = { "${it.roundToInt()}px" },
                    onChangeFinished = { commit(SettingsPatch(font_size = it.roundToInt())) },
                )
            }
            SettingsRow("行高", "正文行间距") {
                RangeSliderRow(
                    value = data.line_height.toFloat(),
                    range = 1.2f..3.0f,
                    steps = 17,
                    format = { "%.1f".format(it) },
                    onChangeFinished = { commit(SettingsPatch(line_height = it.toDouble())) },
                )
            }
            SettingsRow("页面宽度", "分页内容宽度比例") {
                RangeSliderRow(
                    value = (data.page_width * 100).toFloat(),
                    range = 50f..150f,
                    steps = 19,
                    format = { "${it.roundToInt()}%" },
                    onChangeFinished = { commit(SettingsPatch(page_width = (it / 100).toDouble())) },
                )
            }
        }

        SettingsSection("正文字体") {
            SettingsRow(
                "当前字体",
                when {
                    data.custom_font.isBlank() || data.custom_font.startsWith("sys:") -> "内置霞鹜文楷"
                    data.custom_font == "system" -> "系统默认"
                    else -> data.custom_font
                },
            ) {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    fontLauncher.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/x-font-ttf",
                            "application/octet-stream",
                        ),
                    )
                }) { Text("导入字体…") }
            }
            FlowRow(
                modifier = Modifier.padding(top = AnkeSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                FilterChip(
                    selected = data.custom_font.isBlank() || data.custom_font.startsWith("sys:"),
                    onClick = { commit(SettingsPatch(custom_font = "")) },
                    label = { Text("内置霞鹜文楷") },
                )
                FilterChip(
                    selected = data.custom_font == "system",
                    onClick = { commit(SettingsPatch(custom_font = "system")) },
                    label = { Text("系统默认") },
                )
                importedFonts.forEach { f ->
                    FilterChip(
                        selected = data.custom_font == f.name,
                        onClick = { commit(SettingsPatch(custom_font = f.name)) },
                        label = { Text(f.nameWithoutExtension, maxLines = 1) },
                    )
                }
            }
        }

        SettingsSection("翻页方式") {
            val layout = when {
                !data.pagination -> LAYOUT_SCROLL
                data.dual_page -> LAYOUT_DUAL
                data.auto_dual == false -> LAYOUT_SINGLE
                else -> LAYOUT_AUTO
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm)) {
                listOf(
                    LAYOUT_SCROLL to "滚动阅读",
                    LAYOUT_AUTO to "自动双页",
                    LAYOUT_SINGLE to "单页分页",
                    LAYOUT_DUAL to "横屏双页",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = layout == value,
                        onClick = {
                            when (value) {
                                LAYOUT_SCROLL -> commit(
                                    SettingsPatch(
                                        pagination = false,
                                        dual_page = false,
                                        auto_dual = true,
                                    ),
                                )
                                LAYOUT_DUAL -> commit(
                                    SettingsPatch(
                                        pagination = true,
                                        dual_page = true,
                                        auto_dual = true,
                                    ),
                                )
                                LAYOUT_SINGLE -> commit(
                                    SettingsPatch(
                                        pagination = true,
                                        dual_page = false,
                                        auto_dual = false,
                                    ),
                                )
                                else -> commit(
                                    SettingsPatch(
                                        pagination = true,
                                        dual_page = false,
                                        auto_dual = true,
                                    ),
                                )
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "滚动阅读不分页、整章滚动到底即一章；分页模式支持单页与横屏双页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AnkeSpacing.sm),
            )
        }

        SettingsSection("界面") {
            SettingsRow("固定显示阅读页顶栏与底栏", "固定后翻页/换章不会自动收起顶底栏") {
                Switch(
                    checked = data.bars_pinned,
                    onCheckedChange = { commit(SettingsPatch(bars_pinned = it)) },
                )
            }
            SettingsRow("隐藏书名前缀", "书架隐藏首个【安科】等括号前缀（仅显示层，不改原名）") {
                Switch(
                    checked = data.hide_title_brackets,
                    onCheckedChange = { commit(SettingsPatch(hide_title_brackets = it)) },
                )
            }
            TopInsetRow(context)
        }
    }
}

@Composable
internal fun RangeSliderRow(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChangeFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChangeFinished(current) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.width(150.dp),
        )
        Text(
            format(current),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = AnkeSpacing.sm),
        )
    }
}

@Composable
internal fun TopInsetRow(context: Context) {
    val prefs = remember {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    }
    var manual by remember { mutableStateOf(prefs.getInt("top_inset_dp", -1) >= 0) }
    var value by remember { mutableIntStateOf(prefs.getInt("top_inset_dp", 24).coerceIn(0, 64)) }
    SettingsRow(
        if (manual) "顶部安全区（手动 $value dp）" else "顶部安全区（自动）",
        "自动按挖孔/状态栏避让；手动可微调阅读页顶部留白",
    ) {
        Switch(
            checked = manual,
            onCheckedChange = {
                manual = it
                val v = if (it) value else -1
                prefs.edit().putInt("top_inset_dp", v).apply()
            },
        )
    }
    if (manual) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.toFloat(),
                onValueChange = { value = it.roundToInt() },
                onValueChangeFinished = { prefs.edit().putInt("top_inset_dp", value).apply() },
                valueRange = 0f..64f,
                steps = 15,
                modifier = Modifier.width(150.dp),
            )
            Text("$value dp", style = MaterialTheme.typography.labelMedium)
        }
    }
}
