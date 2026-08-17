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

internal data class SettingsTab(val id: String, val label: String, val icon: ImageVector)

internal val TABS = listOf(
    SettingsTab("appearance", "外观", Icons.Filled.Palette),
    SettingsTab("reading", "阅读", Icons.Filled.FormatSize),
               SettingsTab("gestures", "操作", Icons.Filled.TouchApp),                                                   
               SettingsTab("stats", "统计", Icons.Filled.Insights),                                                      
               SettingsTab("data", "数据", Icons.Filled.FolderOpen),                                                      
               SettingsTab("help", "帮助", Icons.Filled.Info),                                                            
           )                                                                                                           

internal val GROUP_SUMMARIES = mapOf(
    "appearance" to "主题模式、亮度、预设色板、自定义颜色",
    "reading" to "字号、行高、页面宽度、翻页方式",
               "gestures" to "点按区域、滑动翻页、音量键",                                                                          
               "stats" to "阅读时长、会话、连续阅读",                                                                              
               "data" to "数据目录、清除数据、版本",                                                                               
               "help" to "使用说明、关于应用",                                                                                    
           )                                                                                                           

internal val THEME_MODES = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "sepia" to "羊皮纸",
    "dark" to "深色",
)

internal val COLOR_SWATCHES = mapOf(
    "custom_bg" to listOf("", "#ffffff", "#f1e8d0", "#dcedd8", "#fdf6e3", "#eceff4", "#222222", "#1e293b", "#002b36"),
    "custom_primary" to listOf("", "#0066cc", "#77bbee", "#008b8b", "#268bd2", "#2f855a", "#60a5fa", "#5e81ac"),
    "custom_accent" to listOf("", "#0066cc", "#2aa198", "#88c0d0", "#38bdf8", "#38a169", "#f59e0b", "#e11d48"),
    "custom_text" to listOf("", "#171717", "#5b4636", "#2e3440", "#657b83", "#e0e0e0", "#e2e8f0", "#93a1a1"),
)

internal val CUSTOM_COLOR_META = listOf(
    Triple("custom_bg", "背景色", "用于阅读页与界面背景；深色背景会自动派生工具栏层次"),
    Triple("custom_primary", "主题色", "用于按钮、进度条、选中态等强调元素"),
    Triple("custom_accent", "强调色", "用于标注色块、引用边线等次要强调"),
    Triple("custom_text", "文字颜色", "仅作用于默认黑/白文字，NGA 帖子中的彩色字体保留原色"),
)

internal const val LAYOUT_SCROLL = "scroll"
internal const val LAYOUT_AUTO = "auto"
internal const val LAYOUT_SINGLE = "single"
internal const val LAYOUT_DUAL = "dual"

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



           /* ---------------- 通用 ---------------- */                                                                  

@Composable
internal fun SettingsList(content: @Composable ColumnScope.() -> Unit) {
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
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
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
internal fun SettingsRow(
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
