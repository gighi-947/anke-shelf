package io.github.gighi947.ankeshelf.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("主题：")
                RadioButton(selected = !themeDark, onClick = { themeDark = false })
                Text("浅色")
                RadioButton(selected = themeDark, onClick = { themeDark = true })
                Text("深色")
                Spacer(modifier = Modifier.height(0.dp))
                Text("图片：", modifier = Modifier.padding(start = AnkeSpacing.lg))
                RadioButton(selected = imageOnline, onClick = { imageOnline = true })
                Text("在线")
                RadioButton(selected = !imageOnline, onClick = { imageOnline = false })
                Text("无图")
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

    DownloadList {
        DownloadSection("已下载 NGA 书") {
            if (books.isEmpty()) {
                Text(
                    "暂无已下载的 NGA 书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@DownloadSection
            }
            books.forEach { book ->
                val nativeDir = java.io.File(book.path)
                val epubLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/epub+zip"),
                ) { uri: Uri? ->
                    uri?.let {
                        val meta = NgaExport.metaOf(nativeDir) ?: return@let
                        val bytes = NgaExport.epubBytes(nativeDir, meta)
                        scope.launch(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) }
                        }
                    }
                }
                val mdLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri: Uri? ->
                    uri?.let {
                        val meta = NgaExport.metaOf(nativeDir) ?: return@let
                        val text = NgaExport.markdownText(nativeDir, meta)
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
                        .padding(top = AnkeSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(shape = MaterialTheme.shapes.small, onClick = {
                        val meta = NgaExport.metaOf(nativeDir) ?: return@TextButton
                        epubLauncher.launch(safeExportName(meta.title) + ".epub")
                    }) { Text("EPUB") }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = {
                        val meta = NgaExport.metaOf(nativeDir) ?: return@TextButton
                        mdLauncher.launch(safeExportName(meta.title) + ".md")
                    }) { Text("MD") }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = {
                        val meta = NgaExport.metaOf(nativeDir) ?: return@TextButton
                        val intent = Intent(context, NgaDownloadService::class.java).apply {
                            action = NgaDownloadService.ACTION_START
                            putExtra("action", "update")
                            putExtra("bookId", book.id)
                            putExtra("tid", meta.tid)
                        }
                        ContextCompat.startForegroundService(context, intent)
                        onChanged()
                    }) { Text("更新") }
                }
            }
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
