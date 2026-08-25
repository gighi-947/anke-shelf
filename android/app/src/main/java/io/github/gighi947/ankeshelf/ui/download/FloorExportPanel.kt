package io.github.gighi947.ankeshelf.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.FloorExportPrefs
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.FloorExportFloor
import io.github.gighi947.ankeshelf.service.FloorExportHtml
import io.github.gighi947.ankeshelf.service.FloorExportMapper
import io.github.gighi947.ankeshelf.service.FloorExportRenderer
import io.github.gighi947.ankeshelf.service.RepoResult
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FloorExportPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books = remember { container.repository.listBooks() }
        .filter { it.record.nga_tid > 0 || it.record.path.contains("gululu_library") }
    var selectedBook by remember { mutableStateOf<BookRecord?>(null) }
    var floors by remember { mutableStateOf<List<FloorExportFloor>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("请选择安科与楼层") }
    var session by remember { mutableStateOf<BookSession?>(null) }
    var exportDir by remember { mutableStateOf("") }

    val all = container.settings.getAll()
    var theme by remember { mutableStateOf(all.floor_export.theme) }
    var fmt by remember { mutableStateOf(all.floor_export.fmt) }
    var scale by remember { mutableStateOf(all.floor_export.scale.toFloat()) }

    fun settingsData() = all

    LaunchedEffect(selectedBook) {
        session?.close()
        val rec = selectedBook ?: return@LaunchedEffect
        val result = container.repository.openSession(rec)
        val s = (result as? RepoResult.Ok)?.value
        session = s
        if (s == null) {
            floors = emptyList()
            status = "打开书籍失败"
            return@LaunchedEffect
        }
        floors = FloorExportMapper.list(rec, s).floors
        selected = emptySet()
        status = "共 ${floors.size} 层"
    }

    LaunchedEffect(Unit) {
        exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "floor_export")
            .apply { mkdirs() }.absolutePath
    }

    Column(modifier = Modifier.fillMaxSize().padding(AnkeSpacing.lg)) {
        Text("选择安科", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.25f)) {
            items(books) { b ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AnkeSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selectedBook?.id == b.record.id, onCheckedChange = {
                        selectedBook = if (selectedBook?.id == b.record.id) null else b.record
                    })
                    Text(b.record.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            FilterChip(selected = theme == "light", onClick = { theme = "light" }, label = { Text("浅色") })
            FilterChip(selected = theme == "sepia", onClick = { theme = "sepia" }, label = { Text("羊皮纸") })
            FilterChip(selected = theme == "dark", onClick = { theme = "dark" }, label = { Text("深色") })
            FilterChip(selected = theme == "current", onClick = { theme = "current" }, label = { Text("当前阅读设定") })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            FilterChip(selected = fmt == "png", onClick = { fmt = "png" }, label = { Text("PNG") })
            FilterChip(selected = fmt == "webp", onClick = { fmt = "webp" }, label = { Text("WebP") })
            FilterChip(selected = scale == 1f, onClick = { scale = 1f }, label = { Text("1x") })
            FilterChip(selected = scale == 1.5f, onClick = { scale = 1.5f }, label = { Text("1.5x") })
            FilterChip(selected = scale == 2f, onClick = { scale = 2f }, label = { Text("2x") })
            FilterChip(selected = scale == 3f, onClick = { scale = 3f }, label = { Text("3x") })
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("筛选楼层") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(floors.filter { it.label.contains(filter) || it.num.toString().contains(filter) }) { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AnkeSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = f.num in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + f.num else selected - f.num
                    })
                    Text(f.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (running) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            Button(
                enabled = !running && selectedBook != null && selected.isNotEmpty(),
                onClick = {
                    val rec = selectedBook ?: return@Button
                    val s = session ?: return@Button
                    val picks = selected.sorted()
                    val prefs = FloorExportPrefs(theme = theme, fmt = fmt, scale = scale.toDouble())
                    container.settings.update(SettingsPatch(floor_export = prefs))
                    running = true
                    progress = 0f
                    status = "准备导出…"
                    scope.launch {
                        var failedImages = 0
                        var ok = 0
                        try {
                            val data = settingsData()
                            val themeColors = if (theme == "current") readerTheme(data)
                            else readerTheme(
                                data.copy(
                                    theme = theme, theme_mode = theme,
                                    custom_bg = "", custom_primary = "",
                                    custom_accent = "", custom_text = "",
                                ),
                            )
                            picks.forEachIndexed { index, num ->
                                val floor = floors.first { it.num == num }
                                val html = if (rec.nga_tid > 0) {
                                    FloorExportHtml.nga(rec, floor, themeColors, data)
                                } else {
                                    FloorExportHtml.gululu(s, floor, themeColors, data)
                                }
                                val base = if (rec.nga_tid > 0) {
                                    "file:///android_asset/reader/"
                                } else {
                                    "file:///android_epub/${s.id}/${s.chapterBaseDir(floor.chapterIndex)}/"
                                }
                                val rendered = FloorExportRenderer.render(context, html, base, scale, fmt)
                                val outFile = File(exportDir, "${safeExportName(rec.title)}_第${num}楼.$fmt")
                                rendered.file.copyTo(outFile, overwrite = true)
                                failedImages += rendered.imageFailed
                                ok++
                                progress = (index + 1f) / picks.size
                                status = "已导出 $ok/${picks.size} 层"
                            }
                            status = "导出完成：$ok 层" + if (failedImages > 0) "，$failedImages 张图片加载失败" else ""
                        } catch (e: Exception) {
                            status = "导出失败：${e.message ?: e}"
                        } finally {
                            running = false
                            onChanged()
                        }
                    }
                },
            ) { Text("开始导出") }
            Button(enabled = exportDir.isNotEmpty(), onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(File(exportDir)), "resource/folder")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }) }
            }) { Text("打开目录") }
        }
    }
}
