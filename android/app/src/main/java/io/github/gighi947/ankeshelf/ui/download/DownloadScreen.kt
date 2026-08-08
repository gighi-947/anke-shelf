package io.github.gighi947.ankeshelf.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.components.ActionIcon
import io.github.gighi947.ankeshelf.ui.components.BookManagementOverlay
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
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

private data class DownloadTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val summary: String,
)

private val DOWNLOAD_TABS = listOf(
    DownloadTab("config", "登录配置", Icons.Filled.Key, "NGA Cookie 凭据，仅存本机"),
    DownloadTab("download", "下载 / 更新", Icons.Filled.FileDownload, "下载新帖或增量更新已有书目"),
    DownloadTab("library", "已下载", Icons.Filled.FolderOpen, "导出 EPUB/Markdown 与更新"),
)

/**
 * NGA 下载页（参照设置页：手机一二级菜单 + 平板主从布局）。
 * - 手机：一级分组列表（登录配置 / 下载·更新 / 已下载），点入二级详情；
 * - 平板/横屏（宽度 ≥600dp）：左侧 NavigationRail + 右侧详情面板；
 * - 所有层级页头都带返回键（一级返回书架，二级返回一级）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    container: AppContainer,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    // 二级详情页：系统返回/侧滑返回先回下载一级菜单；一级菜单由 Root 处理（回书架）。
    BackHandler(enabled = group != null) { group = null }

    if (isTablet) {
        val active = group ?: DOWNLOAD_TABS.first().id
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { PageHeaderTitle("NGA 下载") },
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
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DOWNLOAD_TABS.forEach { tab ->
                            NavigationRailItem(
                                selected = active == tab.id,
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
                    DownloadGroupContent(active, container, onChanged)
                }
            }
        }
    } else {
        if (group == null) {
            DownloadGroupList(onBack = onBack, onSelect = { group = it })
        } else {
            val tab = DOWNLOAD_TABS.first { it.id == group }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { PageHeaderTitle(tab.label) },
                        navigationIcon = {
                            IconButton(onClick = { group = null }) {
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
                    DownloadGroupContent(tab.id, container, onChanged)
                }
            }
        }
    }
}

/** 手机一级菜单：下载分组列表（仿系统设置）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadGroupList(onBack: () -> Unit, onSelect: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { PageHeaderTitle("NGA 下载") },
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
            items(DOWNLOAD_TABS) { tab ->
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
                            tab.summary,
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
                                    modifier = Modifier.padding(AnkeSpacing.sm),
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
                    modifier = Modifier
                        .padding(horizontal = AnkeSpacing.xs)
                        .clickable { onSelect(tab.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadGroupContent(
    groupId: String,
    container: AppContainer,
    onChanged: () -> Unit,
) {
    when (groupId) {
        "config" -> ConfigPanel(container)
        "download" -> DownloadPanel(container, onChanged)
        "library" -> LibraryPanel(container, onChanged)
    }
}

/* ---------------- 登录配置 ---------------- */

@Composable
private fun ConfigPanel(container: AppContainer) {
    val initial = remember { container.ngaConfig.load() }
    var uid by remember { mutableStateOf(initial.uid) }
    var cid by remember { mutableStateOf(initial.cid) }
    var ua by remember { mutableStateOf(initial.ua) }
    var configured by remember { mutableStateOf(initial.configured) }

    DownloadList {
        DownloadSection("登录配置（仅存本机）") {
            OutlinedTextField(
                value = uid,
                onValueChange = { uid = it },
                label = { Text("ngaPassportUid") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cid,
                onValueChange = { cid = it },
                label = { Text("ngaPassportCid") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
            )
            OutlinedTextField(
                value = ua,
                onValueChange = { ua = it },
                label = { Text("User-Agent（留空用默认）") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                Button(shape = MaterialTheme.shapes.small, onClick = {
                    container.ngaConfig.save(
                        NgaConfigPatch(
                            uid = uid,
                            cid = cid,
                            ua = ua.ifBlank { NgaConfig.DEFAULT_UA },
                        ),
                    )
                    configured = container.ngaConfig.load().configured
                }) { Text("保存配置") }
                TextButton(onClick = {
                    container.ngaConfig.clear()
                    uid = ""
                    cid = ""
                    ua = NgaConfig.DEFAULT_UA
                    configured = false
                }) { Text("清除") }
                Text(
                    if (configured) "已配置" else "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (configured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Text(
                "凭据只存本机私有文件，可随时清除；获取方法见 README 教程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AnkeSpacing.sm),
            )
        }
    }
}

/* ---------------- 下载 / 更新 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var tidText by remember { mutableStateOf("") }
    var authorIdText by remember { mutableStateOf("") }
    var maxFloorsText by remember { mutableStateOf("") }
    var themeDark by remember { mutableStateOf(false) }
    var perChapterText by remember { mutableStateOf("20") }
    var imageOnline by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf(NgaServiceStatus.snapshot()) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && activity != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101,
            )
        }
        while (true) {
            status = NgaServiceStatus.snapshot()
            delay(500)
        }
    }

    val tid = tidText.trim().toLongOrNull() ?: 0L
    val existing = remember(tidText) {
        if (tid > 0) container.shelf.listBooks().firstOrNull { it.nga_tid.toLong() == tid } else null
    }

    DownloadList {
        DownloadSection("下载参数") {
            OutlinedTextField(
                value = tidText,
                onValueChange = { tidText = it.filter { c -> c.isDigit() } },
                label = { Text("帖子 tid") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = authorIdText,
                onValueChange = { authorIdText = it.filter { c -> c.isDigit() } },
                label = { Text("只看楼主 uid（0=全部）") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
            )
            OutlinedTextField(
                value = maxFloorsText,
                onValueChange = { maxFloorsText = it.filter { c -> c.isDigit() } },
                label = { Text("楼层上限（0=不限）") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
            )
            OutlinedTextField(
                value = perChapterText,
                onValueChange = { perChapterText = it.filter { c -> c.isDigit() } },
                label = { Text("每章楼层数") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
            )
            // 主题/图片选项拆成独立分组（参照设置页“翻页方式”的 FilterChip 做法），
            // 避免窄屏下单行 8 个元素溢出错乱。
            Text(
                "主题",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AnkeSpacing.sm),
            )
            FlowRow(
                modifier = Modifier.padding(top = AnkeSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                FilterChip(
                    selected = !themeDark,
                    onClick = { themeDark = false },
                    label = { Text("浅色") },
                )
                FilterChip(
                    selected = themeDark,
                    onClick = { themeDark = true },
                    label = { Text("深色") },
                )
            }
            Text(
                "图片",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AnkeSpacing.sm),
            )
            FlowRow(
                modifier = Modifier.padding(top = AnkeSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                FilterChip(
                    selected = imageOnline,
                    onClick = { imageOnline = true },
                    label = { Text("在线") },
                )
                FilterChip(
                    selected = !imageOnline,
                    onClick = { imageOnline = false },
                    label = { Text("无图") },
                )
            }
        }

        DownloadSection("操作") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                Button(
                    shape = MaterialTheme.shapes.small,
                    enabled = tid > 0 && !NgaServiceStatus.running,
                    onClick = {
                        val intent = Intent(context, NgaDownloadService::class.java).apply {
                            action = NgaDownloadService.ACTION_START
                            putExtra("tid", tid)
                            putExtra("authorId", authorIdText.trim().toLongOrNull() ?: 0L)
                            putExtra("maxFloors", maxFloorsText.trim().toIntOrNull() ?: 0)
                            putExtra("theme", if (themeDark) "dark" else "light")
                            putExtra("perChapter", perChapterText.trim().toIntOrNull() ?: 20)
                            putExtra("imageMode", if (imageOnline) "online" else "none")
                            // 已存在同 tid 书时点击“重新下载”= 强制全量重下；
                            // 否则（首次）走全量，已存在场景由下载器自动转为增量。
                            putExtra("fullRedownload", existing != null)
                        }
                        ContextCompat.startForegroundService(context, intent)
                        onChanged()
                    },
                ) { Text(if (existing != null) "重新下载" else "开始下载") }
                if (existing != null) {
                    Button(
                        shape = MaterialTheme.shapes.small,
                        enabled = !NgaServiceStatus.running,
                        onClick = {
                            val intent = Intent(context, NgaDownloadService::class.java).apply {
                                action = NgaDownloadService.ACTION_START
                                putExtra("action", "update")
                                putExtra("bookId", existing.id)
                                putExtra("tid", tid)
                                putExtra("authorId", authorIdText.trim().toLongOrNull() ?: 0L)
                                putExtra("theme", if (themeDark) "dark" else "light")
                                putExtra("perChapter", perChapterText.trim().toIntOrNull() ?: 20)
                                putExtra("imageMode", if (imageOnline) "online" else "none")
                            }
                            ContextCompat.startForegroundService(context, intent)
                            onChanged()
                        },
                    ) { Text("检查更新") }
                }
                if (NgaServiceStatus.running) {
                    Button(shape = MaterialTheme.shapes.small, onClick = {
                        context.startService(
                            Intent(context, NgaDownloadService::class.java)
                                .setAction(NgaDownloadService.ACTION_CANCEL),
                        )
                    }) { Text("取消") }
                }
            }
        }

        if (NgaServiceStatus.running || status.stage == "done" || status.stage == "error" || status.stage == "cancelled") {
            DownloadSection("任务状态") {
                Text(
                    when (status.stage) {
                        "pages" -> status.detail
                        "format" -> "正在写入原生书…"
                        "done" -> status.detail
                        "error" -> "失败：${NgaServiceStatus.error}"
                        "cancelled" -> "已取消"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (NgaServiceStatus.running) {
                    LinearProgressIndicator(
                        progress = {
                            if (status.total > 0) {
                                status.current.toFloat() / status.total
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AnkeSpacing.sm),
                    )
                }
            }
        }
    }
}

/* ---------------- 已下载 ---------------- */

@Composable
private fun LibraryPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val books = remember(tick) {
        container.shelf.listBooks().filter { it.nga_tid > 0 }
    }
    var manageBook by remember { mutableStateOf<BookRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<BookRecord?>(null) }
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
                        onUpdate = { startLibraryUpdate(context, it); onChanged() },
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
                        onUpdate = { startLibraryUpdate(context, it); onChanged() },
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
            container.repository.removeBook(rec)
            onChanged()
            tick++
        },
    )

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书籍？") },
            text = { Text("将删除「${rec.title}」及其进度、断点与本地文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    container.repository.removeBook(rec)
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryBookRow(
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
                Text(book.title.take(1).ifBlank { "书" }, style = MaterialTheme.typography.titleMedium)
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
private fun LibraryBookCard(
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
                Text(book.title.take(1).ifBlank { "书" }, style = MaterialTheme.typography.headlineMedium)
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

private fun startLibraryUpdate(context: android.content.Context, book: BookRecord) {
    val intent = Intent(context, NgaDownloadService::class.java).apply {
        action = NgaDownloadService.ACTION_START
        putExtra("action", "update")
        putExtra("bookId", book.id)
        putExtra("tid", book.nga_tid.toLong())
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun writeLibraryExport(
    context: android.content.Context,
    scope: CoroutineScope,
    book: BookRecord,
    fmt: String,
    uri: Uri,
) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val dir = File(book.path)
            val meta = NgaExport.metaOf(dir) ?: return@runCatching
            val bytes = if (fmt == "md") {
                NgaExport.markdownText(dir, meta).toByteArray(Charsets.UTF_8)
            } else {
                NgaExport.epubBytes(dir, meta)
            }
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
        }
    }
}

/* ---------------- 通用 ---------------- */

@Composable
private fun DownloadList(content: @Composable ColumnScope.() -> Unit) {
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
private fun DownloadSection(title: String, content: @Composable () -> Unit) {
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
