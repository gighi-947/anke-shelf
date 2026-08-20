package io.github.gighi947.ankeshelf.ui.settings

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
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
import io.github.gighi947.ankeshelf.data.Backup
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.AnnotationStore
import io.github.gighi947.ankeshelf.data.EnrichedStats
import io.github.gighi947.ankeshelf.data.JsonFileHealth
import io.github.gighi947.ankeshelf.data.Settings
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.verifyDataIntegrity
import io.github.gighi947.ankeshelf.ui.theme.PALETTES
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.ReaderPalette
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.effectivePalette
import io.github.gighi947.ankeshelf.ui.theme.formatDuration
import io.github.gighi947.ankeshelf.ui.theme.hexColor
import java.io.File
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.service.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
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
    val backupCache = remember { File(diagContext.cacheDir, "ankeshelf-backup-cache.zip") }
    val backupPaths = mapOf(
        "shelf" to appPaths.shelfFile,
        "progress" to appPaths.progressFile,
        "settings" to appPaths.settingsFile,
        "annotations" to appPaths.annotationsFile,
        "statistics" to appPaths.statisticsFile,
    )
    fun toast(msg: String, error: Boolean = false) {
        Toast.makeText(diagContext, msg, if (error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let {
            diagScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Backup.createBackupZip(backupCache, backupPaths, version)
                        diagContext.contentResolver.openOutputStream(it)?.use { os ->
                            backupCache.inputStream().use { inp -> inp.copyTo(os) }
                        } != null
                    }.onFailure { Log.w("AnkeShelf", "备份创建失败：${it.message}") }
                        .getOrDefault(false)
                }
                toast(if (ok) "备份已创建" else "备份失败", error = !ok)
            }
        }
    }
    var pendingRestore by remember { mutableStateOf(false) }
    var healthReport by remember { mutableStateOf<List<JsonFileHealth>?>(null) }
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            diagScope.launch {
                val report = withContext(Dispatchers.IO) {
                    runCatching {
                        diagContext.contentResolver.openInputStream(it)?.use { input ->
                            backupCache.outputStream().use { out -> input.copyTo(out) }
                        }
                        Backup.verifyBackupZip(backupCache)
                    }.onFailure { Log.w("AnkeShelf", "备份验证失败：${it.message}") }
                        .getOrNull()
                }
                when {
                    report == null -> toast("验证失败", error = true)
                    report.ok -> toast("备份包有效（${report.files.size} 个文件）")
                    else -> toast("备份包无效：${report.errors.joinToString("、")}", error = true)
                }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            diagScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        diagContext.contentResolver.openInputStream(it)?.use { input ->
                            backupCache.outputStream().use { out -> input.copyTo(out) }
                        }
                        Backup.restoreBackupZip(backupCache, backupPaths, overwrite = false)
                    }.onFailure { Log.w("AnkeShelf", "备份恢复失败：${it.message}") }
                        .getOrNull()
                }
                when {
                    result == null -> toast("导入失败", error = true)
                    result.ok -> toast("备份已恢复（${result.restored.size} 个文件），建议重启应用生效")
                    result.needsOverwrite -> pendingRestore = true
                    else -> toast("导入失败：${result.errors.joinToString("、")}", error = true)
                }
            }
        }
    }
    val restoreOverwrite: () -> Unit = {
        diagScope.launch {
            val result = withContext(Dispatchers.IO) {
                Backup.restoreBackupZip(backupCache, backupPaths, overwrite = true)
            }
            if (result.ok) toast("备份已恢复（${result.restored.size} 个文件），建议重启应用生效")
            else toast("恢复失败：${result.errors.joinToString("、")}", error = true)
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
            SettingsRow("备份数据", "把书架/进度/设置/标注/统计打包为 ank-backup/1 zip") {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { backupLauncher.launch("ankeshelf-backup.zip") },
                ) { Text("备份") }
            }
            SettingsRow("验证备份包", "只读校验清单/校验和/版本，不写盘") {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { verifyLauncher.launch(arrayOf("application/zip")) },
                ) { Text("验证") }
            }
            SettingsRow("导入备份", "先验证；已有数据需二次确认后才覆盖") {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                ) { Text("导入") }
            }
            SettingsRow("校验数据完整性", "检查五个 JSON 存储能否解析与版本字段（不读取内容值）") {
                Button(
                    shape = MaterialTheme.shapes.small,
                    onClick = { healthReport = verifyDataIntegrity(appPaths) },
                ) { Text("校验") }
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
    if (pendingRestore) {
        AlertDialog(
            onDismissRequest = { pendingRestore = false },
            title = { Text("覆盖确认") },
            text = { Text("目标数据已存在，导入将覆盖书架、进度、设置、标注与统计。\n确认继续？") },
            confirmButton = {
                TextButton(onClick = { pendingRestore = false; restoreOverwrite() }) { Text("覆盖并导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = false }) { Text("取消") }
            },
        )
    }
    healthReport?.let { report ->
        val healthy = report.all { it.ok }
        AlertDialog(
            onDismissRequest = { healthReport = null },
            title = { Text(if (healthy) "数据完整" else "发现异常") },
            text = {
                Column {
                    report.forEach { f ->
                        val detail = when {
                            !f.ok -> "损坏：${f.error}"
                            f.error == "missing" -> "尚未创建"
                            else -> "版本 ${f.version ?: "?"} · ${f.size} 字节"
                        }
                        Text(
                            "${if (f.ok) "✓" else "✗"} ${f.name} — $detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (f.ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (!healthy) {
                        Text(
                            "损坏文件已在加载时隔离为 .corrupt-*，可用「导入备份」恢复。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AnkeSpacing.sm),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { healthReport = null }) { Text("关闭") } },
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
                                                                                                                      
