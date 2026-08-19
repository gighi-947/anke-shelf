package io.github.gighi947.ankeshelf.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NgaConfigPatch
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.service.RepoResult
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.LogEvents
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.components.ActionIcon
import io.github.gighi947.ankeshelf.ui.components.BookManagementOverlay
import io.github.gighi947.ankeshelf.ui.components.NgaUpdateDialog
import io.github.gighi947.ankeshelf.ui.components.launchNgaUpdate
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
/* ---------------- 已下载 ---------------- */

@Composable
internal fun LibraryPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val books = remember(tick) {
        container.shelf.listBooks().filter { it.nga_tid > 0 }
    }
    var manageBook by remember { mutableStateOf<BookRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<BookRecord?>(null) }
    var updateTarget by remember { mutableStateOf<BookRecord?>(null) }
    var exportTarget by remember { mutableStateOf<Pair<BookRecord, String>?>(null) }
    var view by remember { mutableStateOf(container.settings.getAll().shelf_view.ifBlank { "grid" }) }
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        uri?.let {
            exportTarget?.let { (book, _) -> writeLibraryExport(context, scope, book, "epub", it) }
        }
    }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        uri?.let {
            exportTarget?.let { (book, _) -> writeLibraryExport(context, scope, book, "md", it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AnkeSpacing.lg, vertical = AnkeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已下载 NGA 书（${books.size}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                view = if (view == "list") "grid" else "list"
                container.settings.update(SettingsPatch(shelf_view = view))
                onChanged()
            }) {
                Icon(
                    if (view == "list") Icons.Filled.ViewModule else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = if (view == "list") "切换到网格视图" else "切换到列表视图",
                )
            }
        }
        when {
            books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无已下载的 NGA 书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            view == "list" -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = AnkeSpacing.lg, vertical = AnkeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                items(books, key = { it.id }) { book ->
                    LibraryBookRow(
                        book = book,
                        coversDir = container.appPaths.coversDir,
                        onManage = { manageBook = it },
                        onUpdate = { updateTarget = it },
                        onExport = { rec, fmt ->
                            exportTarget = rec to fmt
                            val launcher = if (fmt == "epub") epubLauncher else mdLauncher
                            launcher.launch(safeExportName(rec.title) + if (fmt == "epub") ".epub" else ".md")
                        },
                        onDelete = { deleteTarget = it },
                    )
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
            ) {
                gridItems(books, key = { it.id }) { book ->
                    LibraryBookCard(
                        book = book,
                        coversDir = container.appPaths.coversDir,
                        onManage = { manageBook = it },
                        onUpdate = { updateTarget = it },
                        onExport = { rec, fmt ->
                            exportTarget = rec to fmt
                            val launcher = if (fmt == "epub") epubLauncher else mdLauncher
                            launcher.launch(safeExportName(rec.title) + if (fmt == "epub") ".epub" else ".md")
                        },
                        onDelete = { deleteTarget = it },
                    )
                }
            }
        }
    }

    BookManagementOverlay(
        manageBook = manageBook,
        onDismiss = { manageBook = null },
        onRename = { rec, name ->
            container.repository.renameBook(rec, name)
            onChanged()
            tick++
        },
        onDelete = { rec ->
            if (!container.repository.removeBook(rec)) {
                Toast.makeText(context, "删除书籍文件失败，书架条目已移除", Toast.LENGTH_LONG).show()
            }
            onChanged()
            tick++
        },
        onSetCover = { rec, uri ->
            when (val result = container.repository.setCustomCover(rec, uri, context)) {
                is RepoResult.Ok -> Unit
                is RepoResult.Err ->
                    Toast.makeText(context, result.error.message, Toast.LENGTH_SHORT).show()
            }
            onChanged()
            tick++
        },
        onResetCover = { rec ->
            container.repository.resetCover(rec)
            onChanged()
            tick++
        },
    )

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = AnkeRadius.large,
            title = { Text("删除书籍？") },
            text = { Text("将删除「${rec.title}」及其进度、断点与本地文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    if (!container.repository.removeBook(rec)) {
                        Toast.makeText(context, "删除书籍文件失败，书架条目已移除", Toast.LENGTH_LONG).show()
                    }
                    deleteTarget = null
                    onChanged()
                    tick++
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    updateTarget?.let { book ->
        NgaUpdateDialog(
            book = book,
            container = container,
            onDismiss = { updateTarget = null },
            onConfirm = { params ->
                updateTarget = null
                launchNgaUpdate(context, book, params)
                onChanged()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBookRow(
    book: BookRecord,
    coversDir: File,
    onManage: (BookRecord) -> Unit,
    onUpdate: (BookRecord) -> Unit,
    onExport: (BookRecord, String) -> Unit,
    onDelete: (BookRecord) -> Unit,
) {
    val coverFile = book.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    var exportMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onManage(book) })
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
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AnkeSpacing.md),
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author.ifBlank { "未知作者" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AnkeSpacing.xxs),
            )
        }
        ActionIcon(Icons.Filled.Refresh, "更新") { onUpdate(book) }
        Box {
            ActionIcon(Icons.Filled.IosShare, "导出") { exportMenu = true }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                DropdownMenuItem(
                    text = { Text("导出 EPUB") },
                    onClick = {
                        exportMenu = false
                        onExport(book, "epub")
                    },
                )
                DropdownMenuItem(
                    text = { Text("导出 Markdown") },
                    onClick = {
                        exportMenu = false
                        onExport(book, "md")
                    },
                )
            }
        }
        ActionIcon(Icons.Filled.Delete, "删除") { onDelete(book) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBookCard(
    book: BookRecord,
    coversDir: File,
    onManage: (BookRecord) -> Unit,
    onUpdate: (BookRecord) -> Unit,
    onExport: (BookRecord, String) -> Unit,
    onDelete: (BookRecord) -> Unit,
) {
    val coverFile = book.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    var exportMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onManage(book) }),
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
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AnkeSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
            ) {
                ActionIcon(Icons.Filled.Refresh, "更新") { onUpdate(book) }
                Box {
                    ActionIcon(Icons.Filled.IosShare, "导出") { exportMenu = true }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导出 EPUB") },
                            onClick = {
                                exportMenu = false
                                onExport(book, "epub")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 Markdown") },
                            onClick = {
                                exportMenu = false
                                onExport(book, "md")
                            },
                        )
                    }
                }
                ActionIcon(Icons.Filled.Delete, "删除") { onDelete(book) }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AnkeSpacing.xs),
        )
        Text(
            book.author.ifBlank { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


internal fun writeLibraryExport(
    context: android.content.Context,
    scope: CoroutineScope,
    book: BookRecord,
    fmt: String,
    uri: Uri,
) {
    scope.launch(Dispatchers.IO) {
        val taskId = "export-${System.currentTimeMillis()}"
        LogEvents.event(
            "export",
            "start",
            "task_id" to taskId,
            "book_id_hash" to LogEvents.bookIdHash(book.id),
            "format" to fmt,
        )
        try {
            val dir = File(book.path)
            val meta = NgaExport.metaOf(dir) ?: return@launch
            val bytes = if (fmt == "md") {
                NgaExport.markdownText(dir, meta).toByteArray(Charsets.UTF_8)
            } else {
                NgaExport.epubBytes(dir, meta, NgaExport.imagesDirFor(context, book.id))
            }
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            LogEvents.event(
                "export",
                "done",
                "task_id" to taskId,
                "book_id_hash" to LogEvents.bookIdHash(book.id),
                "format" to fmt,
            )
        } catch (e: Exception) {
            LogEvents.event(
                "export",
                "failed",
                "task_id" to taskId,
                "book_id_hash" to LogEvents.bookIdHash(book.id),
                "error" to (e.message ?: "导出失败"),
            )
        }
    }
}
