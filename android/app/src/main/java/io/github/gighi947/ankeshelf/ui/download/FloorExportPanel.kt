package io.github.gighi947.ankeshelf.ui.download

import android.util.Log

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
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
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.FloorExportPrefs
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.ngaHeaders
import okhttp3.Request
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FloorExportPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books = remember { container.repository.listBooks() }
        .filter { it.record.nga_tid > 0 || it.record.path.contains("gululu_library") }
    var selectedBook by remember { mutableStateOf<BookRecord?>(null) }
    var bookFilter by remember { mutableStateOf("") }
    var bookMenuExpanded by remember { mutableStateOf(false) }
    var floors by remember { mutableStateOf<List<FloorExportFloor>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var hasFiles by remember { mutableStateOf(false) }
    var lastBatch by remember { mutableStateOf<List<File>>(emptyList()) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("请选择安科与楼层") }
    var session by remember { mutableStateOf<BookSession?>(null) }
    var exportDir by remember { mutableStateOf("") }

    val all = container.settings.getAll()
    var theme by remember { mutableStateOf(all.floor_export.theme) }
    var fmt by remember { mutableStateOf(all.floor_export.fmt) }
    var scale by remember { mutableStateOf(all.floor_export.scale.toFloat()) }

    fun settingsData() = container.settings.getAll()

    fun persistPrefs() {
        container.settings.update(
            SettingsPatch(
                floor_export = FloorExportPrefs(
                    theme = theme,
                    fmt = fmt,
                    scale = scale.toDouble(),
                    last_book_id = selectedBook?.id ?: "",
                ),
            ),
        )
    }

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
        val lastBookId = container.settings.getAll().floor_export.last_book_id
        if (lastBookId.isNotBlank()) {
            selectedBook = books.firstOrNull { it.record.id == lastBookId }?.record
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(AnkeSpacing.lg)) {
        Text("选择安科", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        val selectedTitle = books.firstOrNull { it.record.id == selectedBook?.id }?.record?.title ?: ""
        val filteredBooks = if (bookFilter.isBlank()) books
        else books.filter { it.record.title.contains(bookFilter.trim(), ignoreCase = true) }
        ExposedDropdownMenuBox(
            expanded = bookMenuExpanded,
            onExpandedChange = { bookMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = if (bookMenuExpanded) bookFilter else selectedTitle,
                onValueChange = { if (bookMenuExpanded) bookFilter = it },
                singleLine = true,
                label = { Text("搜索安科") },
                placeholder = { if (bookMenuExpanded) Text("输入书名筛选…") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(
                expanded = bookMenuExpanded,
                onDismissRequest = { bookMenuExpanded = false },
            ) {
                if (filteredBooks.isEmpty()) {
                    DropdownMenuItem(text = { Text("没有匹配的书籍") }, onClick = {}, enabled = false)
                } else {
                    filteredBooks.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.record.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            onClick = {
                                selectedBook = b.record
                                bookFilter = ""
                                bookMenuExpanded = false
                                persistPrefs()
                            },
                        )
                    }
                }
            }
        }

        Text("导出选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text("主题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
        ) {
            FilterChip(selected = theme == "light", onClick = { theme = "light"; persistPrefs() }, label = { Text("浅色") })
            FilterChip(selected = theme == "sepia", onClick = { theme = "sepia"; persistPrefs() }, label = { Text("羊皮纸") })
            FilterChip(selected = theme == "dark", onClick = { theme = "dark"; persistPrefs() }, label = { Text("深色") })
            FilterChip(selected = theme == "current", onClick = { theme = "current"; persistPrefs() }, label = { Text("当前阅读设定") })
        }
        Text("格式与倍率", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
        ) {
            FilterChip(selected = fmt == "png", onClick = { fmt = "png"; persistPrefs() }, label = { Text("PNG") })
            FilterChip(selected = fmt == "webp", onClick = { fmt = "webp"; persistPrefs() }, label = { Text("WebP") })
            FilterChip(selected = scale == 1f, onClick = { scale = 1f }, label = { Text("1x") })
            FilterChip(selected = scale == 1.5f, onClick = { scale = 1.5f }, label = { Text("1.5x") })
            FilterChip(selected = scale == 2f, onClick = { scale = 2f }, label = { Text("2x") })
            FilterChip(selected = scale == 3f, onClick = { scale = 3f }, label = { Text("3x") })
        }

        Text("楼层", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("筛选楼层") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val floorFilter = filter.trim()
            val numQuery = Regex("^(\\d+)\\s*楼?$").find(floorFilter)?.groupValues?.get(1)?.toIntOrNull()
            val filteredFloors = if (floorFilter.isBlank()) floors
            else floors.filter { f ->
                if (numQuery != null) f.num == numQuery
                else f.label.contains(floorFilter, ignoreCase = true) || f.num.toString() == floorFilter
            }
            items(filteredFloors) { f ->
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
                    hasFiles = false
                    lastBatch = emptyList()
                    progress = 0f
                    status = "准备导出…"
                    scope.launch {
                        var failedImages = 0
                        var ok = 0
                        try {
                            val data = settingsData()
                            val systemDark = (context.resources.configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES
                            val themeColors = if (theme == "current") readerTheme(data, systemDark)
                            else readerTheme(
                                data.copy(
                                    theme = theme, theme_mode = theme,
                                    custom_bg = "", custom_primary = "",
                                    custom_accent = "", custom_text = "",
                                ),
                            )

                            Log.w("AnkeShelf", "[floor_export] theme=$theme colors=${themeColors.background}/${themeColors.text}/${themeColors.accent} customFont=${data.custom_font} bookFonts=${data.book_fonts}")
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
                                val density = context.resources.displayMetrics.density
                                val screenCss = (context.resources.displayMetrics.widthPixels / density).toInt()
                                val fontPx = data.font_size
                                val pageWidth = data.page_width.coerceIn(0.5, 1.5)
                                val viewportWidth = minOf((46 * fontPx * pageWidth).toInt(), screenCss).coerceAtLeast(320)
                                val ngaFetcher: (String) -> ByteArray? = { url ->
                                    try {
                                        val req = Request.Builder().url(url)
                                            .ngaHeaders(container.ngaConfig.load())
                                            .build()
                                        container.okHttp.newCall(req).execute().use { resp ->
                                            if (resp.isSuccessful) resp.body?.bytes() else null
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                val rendered = FloorExportRenderer.render(
                                    context = context,
                                    html = html,
                                    baseUrl = base,
                                    scale = scale,
                                    format = fmt,
                                    fontsDir = container.appPaths.fontsDir,
                                    assetResolver = if (rec.nga_tid > 0) null else { rel ->
                                        s.readAsset(floor.chapterIndex, rel)
                                    },
                                    ngaImageFetcher = ngaFetcher,
                                    userAgent = NgaConfig.DEFAULT_UA,
                                    viewportWidth = viewportWidth,
                                )
                                val outFile = File(exportDir, "${safeExportName(rec.title)}_第${num}楼.$fmt")
                                rendered.file.copyTo(outFile, overwrite = true)
                                lastBatch = lastBatch + outFile
                                failedImages += rendered.imageFailed
                                ok++
                                progress = (index + 1f) / picks.size
                                status = "已导出 $ok/${picks.size} 层"
                            }
                            status = "导出完成：$ok 层" + if (failedImages > 0) "，$failedImages 张图片加载失败" else ""
                            hasFiles = ok > 0
                        } catch (e: Exception) {
                            status = "导出失败：${e.message ?: e}"
                        } finally {
                            running = false
                            onChanged()
                        }
                    }
                },
            ) { Text("开始导出") }
            Button(enabled = hasFiles && !running && lastBatch.isNotEmpty(), onClick = {
                if (lastBatch.isEmpty()) {
                    Toast.makeText(context, "本批导出没有文件", Toast.LENGTH_SHORT).show()
                } else {
                    val uris = lastBatch.map { f ->
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                    }
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        clipData = ClipData.newRawUri(null, uris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "分享本批楼层")) }
                        .onFailure { Toast.makeText(context, "无法调起分享", Toast.LENGTH_SHORT).show() }
                }
            }) { Text("分享本批") }
            Button(enabled = exportDir.isNotEmpty(), onClick = {
                runCatching {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("楼层导出目录", exportDir))
                }
                Toast.makeText(context, "导出目录：$exportDir", Toast.LENGTH_LONG).show()
            }) { Text("目录路径") }
        }
    }
}
