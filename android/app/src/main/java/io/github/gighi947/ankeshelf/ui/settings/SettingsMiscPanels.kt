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
/* ---------------- 操作 ---------------- */

@Composable
internal fun GesturesPanel(data: SettingsData, commit: (SettingsPatch) -> Unit, context: Context) {
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
internal fun StatsPanel(statsGlobal: EnrichedStats, onOpenStats: () -> Unit) {
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
internal fun DataPanel(
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
internal fun AnnotationExportRow(book: BookUi, annotations: AnnotationStore) {
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
           internal fun HelpPanel(onOpenGuide: () -> Unit) {                                                             
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
                                                                                                                      
