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

private data class SettingsTab(val id: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    SettingsTab("appearance", "外观", Icons.Filled.Palette),
    SettingsTab("reading", "阅读", Icons.Filled.FormatSize),
               SettingsTab("gestures", "操作", Icons.Filled.TouchApp),                                                   
               SettingsTab("stats", "统计", Icons.Filled.Insights),                                                      
               SettingsTab("data", "数据", Icons.Filled.FolderOpen),                                                      
               SettingsTab("help", "帮助", Icons.Filled.Info),                                                            
           )                                                                                                           

private val GROUP_SUMMARIES = mapOf(
    "appearance" to "主题模式、亮度、预设色板、自定义颜色",
    "reading" to "字号、行高、页面宽度、翻页方式",
               "gestures" to "点按区域、滑动翻页、音量键",                                                                          
               "stats" to "阅读时长、会话、连续阅读",                                                                              
               "data" to "数据目录、清除数据、版本",                                                                               
               "help" to "使用说明、关于应用",                                                                                    
           )                                                                                                           

private val THEME_MODES = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "sepia" to "羊皮纸",
    "dark" to "深色",
)

private val COLOR_SWATCHES = mapOf(
    "custom_bg" to listOf("", "#ffffff", "#f1e8d0", "#dcedd8", "#fdf6e3", "#eceff4", "#222222", "#1e293b", "#002b36"),
    "custom_primary" to listOf("", "#0066cc", "#77bbee", "#008b8b", "#268bd2", "#2f855a", "#60a5fa", "#5e81ac"),
    "custom_accent" to listOf("", "#0066cc", "#2aa198", "#88c0d0", "#38bdf8", "#38a169", "#f59e0b", "#e11d48"),
    "custom_text" to listOf("", "#171717", "#5b4636", "#2e3440", "#657b83", "#e0e0e0", "#e2e8f0", "#93a1a1"),
)

private val CUSTOM_COLOR_META = listOf(
    Triple("custom_bg", "背景色", "用于阅读页与界面背景；深色背景会自动派生工具栏层次"),
    Triple("custom_primary", "主题色", "用于按钮、进度条、选中态等强调元素"),
    Triple("custom_accent", "强调色", "用于标注色块、引用边线等次要强调"),
    Triple("custom_text", "文字颜色", "仅作用于默认黑/白文字，NGA 帖子中的彩色字体保留原色"),
)

private const val LAYOUT_SCROLL = "scroll"
private const val LAYOUT_AUTO = "auto"
private const val LAYOUT_SINGLE = "single"
private const val LAYOUT_DUAL = "dual"

/**
 * 设置页（一二级菜单 + 平板主从式布局）：
 * - 手机：一级为设置分组列表（类似系统设置），点入二级详情；
 * - 平板（宽度 ≥600dp）：左侧 NavigationRail 分组导航，右侧直接显示详情面板。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    refreshKey: Int,
    books: List<BookUi>,
    annotations: AnnotationStore,
    statsGlobal: EnrichedStats,
    appPaths: AppPaths,
    onOpenStats: () -> Unit,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onClearAllData: () -> Unit,
) {
    val data = remember(refreshKey) { settings.getAll() }
    var group by remember { mutableStateOf<String?>(null) }
    var sheetKey by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showClearFinal by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    fun commit(patch: SettingsPatch) {
        settings.update(patch)
        onChanged()
    }

    // 二级详情页：系统返回/侧滑返回先回设置一级菜单；一级菜单由 Root 处理（回书架）。
    BackHandler(enabled = group != null) { group = null }

    if (isTablet) {
        val activeGroup = group ?: TABS.first().id
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { AppBarTitle("设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    // 手机横屏等矮屏下允许滚动，平板高度足够时无需滚动。
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TABS.forEach { tab ->
                            NavigationRailItem(
                                selected = activeGroup == tab.id,
                                onClick = { group = tab.id },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                VerticalDivider()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    GroupContent(
                        groupId = activeGroup,
                        data = data,
                        commit = ::commit,
                        books = books,
                        annotations = annotations,
                        fontsDir = appPaths.fontsDir,
                        onPickColor = { sheetKey = it },
                        context = context,
                        statsGlobal = statsGlobal,
                        onOpenStats = onOpenStats,
                        onOpenGuide = onOpenGuide,
                        appPaths = appPaths,
                        onShowPath = { showPathDialog = true },
                        onClearAll = { showClearConfirm = true },
                    )
                }
            }
        }
    } else {
        if (group == null) {
            PhoneGroupList(onBack = onBack, onSelect = { group = it })
        } else {
            val tab = TABS.first { it.id == group }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { AppBarTitle(tab.label) },
                        navigationIcon = {
                            IconButton(shape = MaterialTheme.shapes.small, onClick = { group = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    GroupContent(
                        groupId = tab.id,
                        data = data,
                        commit = ::commit,
                        books = books,
                        annotations = annotations,
                        fontsDir = appPaths.fontsDir,
                        onPickColor = { sheetKey = it },
                        context = context,
                        statsGlobal = statsGlobal,
                        onOpenStats = onOpenStats,
                        onOpenGuide = onOpenGuide,
                        appPaths = appPaths,
                        onShowPath = { showPathDialog = true },
                        onClearAll = { showClearConfirm = true },
                    )
                }
            }
        }
    }

    sheetKey?.let { key ->
        val current = when (key) {
            "custom_bg" -> data.custom_bg
            "custom_primary" -> data.custom_primary
            "custom_accent" -> data.custom_accent
            else -> data.custom_text
        }
        ModalBottomSheet(onDismissRequest = { sheetKey = null }) {
            ColorPickerSheet(
                key = key,
                current = current,
                onApply = { value ->
                    val patch = when (key) {
                        "custom_bg" -> SettingsPatch(custom_bg = value)
                        "custom_primary" -> SettingsPatch(custom_primary = value)
                        "custom_accent" -> SettingsPatch(custom_accent = value)
                        else -> SettingsPatch(custom_text = value)
                    }
                    commit(patch)
                    sheetKey = null
                    Toast.makeText(context, if (value.isEmpty()) "已恢复跟随主题" else "颜色已更新", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("数据目录") },
            text = { Text(appPaths.root.absolutePath) },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { showPathDialog = false }) { Text("知道了") }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除全部数据？") },
            text = { Text("将删除全部用户数据（书架、进度、标注、NGA 配置、统计）。此操作不可恢复。") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    showClearConfirm = false
                    showClearFinal = true
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
    if (showClearFinal) {
        AlertDialog(
            onDismissRequest = { showClearFinal = false },
            title = { Text("最后确认") },
            text = { Text("确定清除全部数据并退出？此操作不可恢复。") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    showClearFinal = false
                    onClearAllData()
                }) { Text("清除并退出") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { showClearFinal = false }) { Text("取消") }
            },
        )
    }
}

/** 页头标题：加粗 + 主题色（底部 Tab 初始页统一风格）。 */
@Composable
private fun AppBarTitle(text: String) {
    PageHeaderTitle(text)
}

/** 手机一级菜单：设置分组列表（类似系统设置）。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhoneGroupList(onBack: () -> Unit, onSelect: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { AppBarTitle("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = AnkeSpacing.xs),
        ) {
            items(TABS) { tab ->
                ListItem(
                    headlineContent = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    supportingContent = {
                        Text(
                            GROUP_SUMMARIES[tab.id] ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { onSelect(tab.id) },
                )
            }
        }
    }
}

@Composable
private fun GroupContent(
    groupId: String,
    data: SettingsData,
    commit: (SettingsPatch) -> Unit,
    books: List<BookUi>,
    annotations: AnnotationStore,
    fontsDir: File,
    onPickColor: (String) -> Unit,
    context: Context,
    statsGlobal: EnrichedStats,
    onOpenStats: () -> Unit,
    onOpenGuide: () -> Unit,
    appPaths: AppPaths,
    onShowPath: () -> Unit,
    onClearAll: () -> Unit,
) {
    when (groupId) {
        "appearance" -> AppearancePanel(
            data = data,
            commit = commit,
            onPickColor = onPickColor,
            context = context,
        )
        "reading" -> ReadingPanel(data, commit, context, fontsDir)
        "gestures" -> GesturesPanel(data, commit, context)
        "stats" -> StatsPanel(statsGlobal, onOpenStats)
        "data" -> DataPanel(
            appPaths = appPaths,
            version = "安科书架 v${BuildConfig.VERSION_NAME}",
            books = books,
            annotations = annotations,
            onShowPath = onShowPath,
            onClearAll = onClearAll,
        )
        "help" -> HelpPanel(onOpenGuide = onOpenGuide)
    }
}

/* ---------------- 外观 ---------------- */

@Composable
private fun AppearancePanel(
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
private fun BrightnessSlider(data: SettingsData, commit: (SettingsPatch) -> Unit) {
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
private fun PaletteCard(palette: ReaderPalette, active: Boolean, onClick: () -> Unit) {
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
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color(0x33000000), CircleShape),
    )
}

@Composable
private fun ColorDot(value: String, onClick: () -> Unit) {
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
private fun ColorPickerSheet(
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

private fun contentOnColor(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF171717) else Color.White

@Composable
private fun ThemePreview(data: SettingsData) {
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

/* ---------------- 阅读 ---------------- */

@Composable
private fun ReadingPanel(
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
            TopInsetRow(context)
        }
    }
}

@Composable
private fun RangeSliderRow(
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
private fun TopInsetRow(context: Context) {
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

/* ---------------- 操作 ---------------- */

@Composable
private fun GesturesPanel(data: SettingsData, commit: (SettingsPatch) -> Unit, context: Context) {
    val prefs = remember {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    }
    var volumePaging by remember { mutableStateOf(prefs.getBoolean("volume_key_paging", false)) }
    SettingsList {
        SettingsSection("手势说明") {
            listOf(
                "点按左侧" to "上一页（分页模式）；滚动模式下不换章",
                "点按右侧" to "下一页（分页模式）；滚动模式下不换章",
                "点按中间" to "切换顶底控制条",
                "横向滑动" to "分页模式下翻页",
                "返回键" to "保存进度并返回书架",
            ).forEach { (k, v) ->
                SettingsRow(k, v) {
                    Icon(
                        Icons.Filled.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingsSection("按键") {
            SettingsRow("音量键翻页", "按音量上/下键切换上一页/下一页") {
                Switch(
                    checked = volumePaging,
                    onCheckedChange = {
                        volumePaging = it
                        prefs.edit().putBoolean("volume_key_paging", it).apply()
                    },
                )
            }
        }
    }
}

/* ---------------- 统计 / 数据 ---------------- */

@Composable
private fun StatsPanel(statsGlobal: EnrichedStats, onOpenStats: () -> Unit) {
    SettingsList {
        SettingsSection("阅读统计") {
            SettingsRow(
                "全部书籍 · 已读 ${formatDuration(statsGlobal.total_seconds)}",
                "默认汇总全部书目；进入详情后可按具体书目查看",
            ) {
                Button(shape = MaterialTheme.shapes.small, onClick = onOpenStats) { Text("详情") }
            }
        }
    }
}

@Composable
private fun DataPanel(
    appPaths: AppPaths,
    version: String,
    books: List<BookUi>,
    annotations: AnnotationStore,
    onShowPath: () -> Unit,
    onClearAll: () -> Unit,
) {
    val diagContext = LocalContext.current
    val diagScope = rememberCoroutineScope()
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let {
            val text = Diagnostics.collect(diagContext, appPaths, version)
            diagScope.launch(Dispatchers.IO) {
                diagContext.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    SettingsList {
        SettingsSection("数据") {
            SettingsRow("打开数据目录", "查看书架/进度/标注等 JSON 数据文件位置") {
                Button(shape = MaterialTheme.shapes.small, onClick = onShowPath) { Text("查看路径") }
            }
            SettingsRow("导出诊断信息", "版本/系统/WebView/数据文件与最近事件（不含凭据与正文）") {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { diagLauncher.launch("ankeshelf-diagnostics.txt") },
                ) { Text("导出") }
            }
            SettingsRow("清除全部数据", "删除书架、进度、标注、NGA 配置与统计") {
                Button(shape = MaterialTheme.shapes.small, onClick = onClearAll) { Text("清除") }
            }
            Text(
                "NGA 帖子的下载与导出请在书架「下载」页操作；卸载将删除全部用户数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection("导出标注") {
            val withAnnotations = books.filter { ui ->
                val b = annotations.getAll(ui.record.id)
                b.highlights.isNotEmpty() || b.bookmarks.isNotEmpty()
            }
            if (withAnnotations.isEmpty()) {
                Text(
                    "暂无标注可导出（在阅读器中选中文字即可添加高亮/书签）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                withAnnotations.forEach { ui ->
                    AnnotationExportRow(book = ui, annotations = annotations)
                }
            }
        }
        Text(
            version,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AnkeSpacing.sm),
        )
    }
}

@Composable
private fun AnnotationExportRow(book: BookUi, annotations: AnnotationStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeName = book.record.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .ifBlank { book.record.id }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        uri?.let {
            val text = annotations.export(book.record.id, "md", book.record.title) { "第 ${it + 1} 章" }
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            val text = annotations.export(book.record.id, "json", book.record.title) { "第 ${it + 1} 章" }
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AnkeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = book.record.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(shape = MaterialTheme.shapes.small, onClick = {
            mdLauncher.launch("$safeName-annotations.md")
        }) { Text("MD") }
        TextButton(shape = MaterialTheme.shapes.small, onClick = {
            jsonLauncher.launch("$safeName-annotations.json")
        }) { Text("JSON") }
    }
}

           /* ---------------- 帮助 ---------------- */                                                                  
                                                                                                                      
           @Composable                                                                                                  
           private fun HelpPanel(onOpenGuide: () -> Unit) {                                                             
               SettingsList {                                                                                            
                   SettingsSection("帮助") {                                                                              
                       Row(                                                                                              
                           modifier = Modifier                                                                           
                               .fillMaxWidth()                                                                           
                               .clickable { onOpenGuide() }                                                             
                               .padding(vertical = AnkeSpacing.xs),                                                      
                           verticalAlignment = Alignment.CenterVertically,                                               
                       ) {                                                                                               
                           Column(modifier = Modifier.weight(1f)) {                                                      
                               Text("使用说明", style = MaterialTheme.typography.bodyLarge)                                
                               Text(                                                                                     
                                   "导入、下载、阅读操作与数据说明",                                                        
                                   style = MaterialTheme.typography.bodySmall,                                           
                                   color = MaterialTheme.colorScheme.onSurfaceVariant,                                    
                                   modifier = Modifier.padding(top = AnkeSpacing.xxs),                                   
                               )                                                                                         
                           }                                                                                             
                           Icon(                                                                                         
                               Icons.AutoMirrored.Filled.KeyboardArrowRight,                                             
                               contentDescription = null,                                                               
                               tint = MaterialTheme.colorScheme.onSurfaceVariant,                                        
                           )                                                                                             
                       }                                                                                                 
                   }                                                                                                     
               }                                                                                                         
           }                                                                                                             
                                                                                                                      
           /* ---------------- 通用 ---------------- */                                                                  

@Composable
private fun SettingsList(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AnkeSpacing.lg,
            end = AnkeSpacing.lg,
            top = AnkeSpacing.md,
            bottom = AnkeSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(AnkeSpacing.lg)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.padding(top = AnkeSpacing.md)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    desc: String = "",
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AnkeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (desc.isNotEmpty()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.xxs),
                )
            }
        }
        // 控件限定最大宽度，避免滑块/按钮抢占整行导致左侧说明被压成一字一行的竖排。
        Box(
            modifier = Modifier.widthIn(max = 220.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            control()
        }
    }
}

private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}
