package io.github.gighi947.ankeshelf.ui.shelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.service.LogEvents
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.components.ActionIcon
import io.github.gighi947.ankeshelf.ui.components.BookManagementOverlay
import io.github.gighi947.ankeshelf.ui.components.NgaUpdateDialog
import io.github.gighi947.ankeshelf.ui.components.launchNgaUpdate
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** 书架页：空态 + 网格（M2：SAF 导入 EPUB、显示进度、进入阅读器）。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BookshelfScreen(
    books: List<BookUi>,
    coversDir: File,
    container: AppContainer,
    onImport: (Uri) -> Unit,
    onOpenDownload: () -> Unit,
    onOpenGuide: () -> Unit,
    onRename: (BookRecord, String) -> Unit,
    onDelete: (BookRecord) -> Unit,
    onSetCover: (BookRecord, Uri) -> Unit,
    onResetCover: (BookRecord) -> Unit,
    onOpen: (BookRecord) -> Unit,
    shelfView: String,
    onShelfViewChange: (String) -> Unit,
    sort: String,
    onSortChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<Pair<BookRecord, String>?>(null) }
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        uri?.let { pendingExport?.let { (rec, fmt) -> exportBook(context, scope, rec, fmt, it) } }
    }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        uri?.let { pendingExport?.let { (rec, fmt) -> exportBook(context, scope, rec, fmt, it) } }
    }
    val launchPicker = {
        launcher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }
    val hideBrackets = container.settings.getAll().hide_title_brackets
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var manageBook by remember { mutableStateOf<BookRecord?>(null) }
    var updateTarget by remember { mutableStateOf<BookRecord?>(null) }
    val sortedBooks = remember(books, sort) {
        when (sort) {
            "added" -> books.sortedByDescending { it.record.added_at }
            "name" -> books.sortedBy { it.record.title }
            else -> books.sortedByDescending { it.record.last_read_at }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { PageHeaderTitle("安科书架") },
                actions = {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "\u6392\u5e8f")
                    }
                    Box {
                        DropdownMenu(
                            expanded = sortMenu,
                            onDismissRequest = { sortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("\u6309\u6700\u8fd1\u9605\u8bfb") },
                                onClick = { onSortChange("recent"); sortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("\u6309\u5bfc\u5165\u65f6\u95f4") },
                                onClick = { onSortChange("added"); sortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("\u6309\u540d\u79f0") },
                                onClick = { onSortChange("name"); sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = {
                        onShelfViewChange(if (shelfView == "list") "grid" else "list")
                    }) {
                        Icon(
                            if (shelfView == "list") Icons.Filled.ViewModule else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = if (shelfView == "list") {
                                "\u5207\u6362\u5230\u7f51\u683c\u89c6\u56fe"
                            } else {
                                "\u5207\u6362\u5230\u5217\u8868\u89c6\u56fe"
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { importMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "\u5bfc\u5165")
                        }
                        DropdownMenu(
                            expanded = importMenu,
                            onDismissRequest = { importMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("\u5bfc\u5165 EPUB") },
                                onClick = {
                                    importMenu = false
                                    launchPicker()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("\u4ece NGA \u4e0b\u8f7d") },
                                onClick = {
                                    importMenu = false
                                    onOpenDownload()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            val guidePrefs = context.getSharedPreferences("guide", android.content.Context.MODE_PRIVATE)
            var guideSeen by remember { mutableStateOf(guidePrefs.getBoolean("seen", false)) }
            fun markGuideSeen() {
                guidePrefs.edit().putBoolean("seen", true).apply()
                guideSeen = true
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(AnkeSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("书架为空", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "导入 EPUB 开始阅读",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AnkeSpacing.sm, bottom = AnkeSpacing.lg),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm)) {
                        Button(shape = MaterialTheme.shapes.small, onClick = launchPicker) {
                            Text("导入 EPUB")
                        }
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onOpenDownload) {
                            Text("从 NGA 下载")
                        }
                    }
                }
                // 首次打开且书架为空：顶部提醒查看内置使用说明（查看/关闭后不再出现）。
                if (!guideSeen) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(AnkeSpacing.md),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = AnkeSpacing.lg,
                                top = AnkeSpacing.sm,
                                bottom = AnkeSpacing.sm,
                                end = AnkeSpacing.xs,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("新用户？", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "应用内置使用说明：导入 EPUB、NGA 下载、阅读操作等。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = AnkeSpacing.xxs),
                                )
                            }
                            TextButton(onClick = {
                                markGuideSeen()
                                onOpenGuide()
                            }) { Text("查看") }
                            IconButton(onClick = { markGuideSeen() }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭提醒")
                            }
                        }
                    }
                }
            }
        } else {
            if (shelfView == "list") {
                LazyColumn(
                    contentPadding = PaddingValues(AnkeSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    lazyItems(sortedBooks, key = { it.record.id }) { ui ->
                        BookListRow(
                            ui = ui,
                            coversDir = coversDir,
                            hideBrackets = hideBrackets,
                            onClick = { onOpen(ui.record) },
                            onLongPress = { manageBook = it },
                            onUpdate = { updateTarget = it },
                            onExportEpub = { rec ->
                                pendingExport = rec to "epub"
                                epubLauncher.launch(safeExportName(rec.title) + ".epub")
                            },
                            onExportMd = { rec ->
                                pendingExport = rec to "md"
                                mdLauncher.launch(safeExportName(rec.title) + ".md")
                            },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(AnkeSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    items(sortedBooks, key = { it.record.id }) { ui ->
                        BookCard(
                            ui = ui,
                            coversDir = coversDir,
                            context = context,
                            container = container,
                            hideBrackets = hideBrackets,
                            onClick = { onOpen(ui.record) },
                            onLongPress = { manageBook = it },
                            onUpdate = { updateTarget = it },
                            onExportEpub = { rec ->
                                pendingExport = rec to "epub"
                                epubLauncher.launch(safeExportName(rec.title) + ".epub")
                            },
                            onExportMd = { rec ->
                                pendingExport = rec to "md"
                                mdLauncher.launch(safeExportName(rec.title) + ".md")
                            },
                        )
                    }
                }
            }
        }
        BookManagementOverlay(
            manageBook = manageBook,
            onDismiss = { manageBook = null },
            onRename = onRename,
            onDelete = onDelete,
            onSetCover = onSetCover,
            onResetCover = onResetCover,
        )

        updateTarget?.let { book ->
            NgaUpdateDialog(
                book = book,
                container = container,
                onDismiss = { updateTarget = null },
                onConfirm = { params ->
                    updateTarget = null
                    launchNgaUpdate(context, book, params)
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BookListRow(
    ui: BookUi,
    coversDir: File,
    hideBrackets: Boolean,
    onClick: () -> Unit,
    onLongPress: (BookRecord) -> Unit,
    onUpdate: (BookRecord) -> Unit,
    onExportEpub: (BookRecord) -> Unit,
    onExportMd: (BookRecord) -> Unit,
) {
    val coverFile = ui.record.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    val displayTitle = if (hideBrackets) ui.record.title.replace(Regex("""^(?:【[^】]*】|\[[^\]]*\])[\s　]*"""), "").ifBlank { ui.record.title } else ui.record.title
    var exportMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { onLongPress(ui.record) })
            .padding(vertical = AnkeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 74.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFile?.exists() == true) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (ui.record.nga_tid > 0) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(displayTitle.take(1).ifBlank { "书" }, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AnkeSpacing.md),
        ) {
            Text(
                displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.record.author.ifBlank { "未知作者" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AnkeSpacing.xxs),
            )
            Text(
                "${ui.progressPct.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AnkeSpacing.xxs),
            )
        }
        if (ui.record.nga_tid > 0) {
            ActionIcon(Icons.Filled.Refresh, "更新") { onUpdate(ui.record) }
        }
        Box {
            ActionIcon(Icons.Filled.IosShare, "导出") { exportMenu = true }
            DropdownMenu(
                expanded = exportMenu,
                onDismissRequest = { exportMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("导出 EPUB") },
                    onClick = {
                        exportMenu = false
                        onExportEpub(ui.record)
                    },
                )
                if (ui.record.nga_tid > 0) {
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        onClick = {
                            exportMenu = false
                            onExportMd(ui.record)
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BookCard(
    ui: BookUi,
    coversDir: File,
    context: Context,
    container: AppContainer,
    hideBrackets: Boolean,
    onClick: () -> Unit,
    onLongPress: (BookRecord) -> Unit,
    onUpdate: (BookRecord) -> Unit,
    onExportEpub: (BookRecord) -> Unit,
    onExportMd: (BookRecord) -> Unit,
) {
    val coverFile = ui.record.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    val displayTitle = if (hideBrackets) ui.record.title.replace(Regex("""^(?:【[^】]*】|\[[^\]]*\])[\s　]*"""), "").ifBlank { ui.record.title } else ui.record.title
    var exportMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { onLongPress(ui.record) }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFile?.exists() == true) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (ui.record.nga_tid > 0) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    displayTitle.take(1).ifBlank { "书" },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AnkeSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
            ) {
                if (ui.record.nga_tid > 0) {
                    ActionIcon(Icons.Filled.Refresh, "更新") { onUpdate(ui.record) }
                }
                Box {
                    ActionIcon(Icons.Filled.IosShare, "导出") { exportMenu = true }
                    DropdownMenu(
                        expanded = exportMenu,
                        onDismissRequest = { exportMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出 EPUB") },
                            onClick = {
                                exportMenu = false
                                onExportEpub(ui.record)
                            },
                        )
                        if (ui.record.nga_tid > 0) {
                            DropdownMenuItem(
                                text = { Text("导出 Markdown") },
                                onClick = {
                                    exportMenu = false
                                    onExportMd(ui.record)
                                },
                            )
                        }
                    }
                }
            }
        }
        Text(
            displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AnkeSpacing.xs),
        )
        Text(
            ui.record.author.ifBlank { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${ui.progressPct.roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun exportBook(
    context: Context,
    scope: CoroutineScope,
    rec: BookRecord,
    fmt: String,
    uri: Uri,
) {
    scope.launch(Dispatchers.IO) {
        val taskId = "export-${System.currentTimeMillis()}"
        LogEvents.event(
            "export",
            "start",
            "task_id" to taskId,
            "book_id_hash" to LogEvents.bookIdHash(rec.id),
            "format" to fmt,
        )
        try {
            val os = context.contentResolver.openOutputStream(uri) ?: return@launch
            os.use { out ->
                if (rec.nga_tid > 0) {
                    val dir = File(rec.path)
                    val meta = NgaExport.metaOf(dir) ?: return@launch
                    if (fmt == "md") {
                        out.write(NgaExport.markdownText(dir, meta).toByteArray(Charsets.UTF_8))
                    } else {
                        out.write(
                            NgaExport.epubBytes(
                                dir,
                                meta,
                                NgaExport.imagesDirFor(context, rec.id),
                            ),
                        )
                    }
                } else if (fmt == "epub") {
                    File(rec.path).inputStream().use { input -> input.copyTo(out) }
                }
            }
            LogEvents.event(
                "export",
                "done",
                "task_id" to taskId,
                "book_id_hash" to LogEvents.bookIdHash(rec.id),
                "format" to fmt,
            )
        } catch (e: Exception) {
            LogEvents.event(
                "export",
                "failed",
                "task_id" to taskId,
                "book_id_hash" to LogEvents.bookIdHash(rec.id),
                "error" to (e.message ?: "导出失败"),
            )
        }
    }
}
