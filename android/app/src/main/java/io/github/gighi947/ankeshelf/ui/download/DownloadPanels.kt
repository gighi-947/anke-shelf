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
import io.github.gighi947.ankeshelf.data.parseNgaCookieText
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
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
/* ---------------- 登录配置 ---------------- */

@Composable
internal fun ConfigPanel(container: AppContainer) {
    val initial = remember { container.ngaConfig.load() }
    var uid by remember { mutableStateOf(initial.uid) }
    var cid by remember { mutableStateOf(initial.cid) }
    var ua by remember { mutableStateOf(initial.ua) }
    var rawCookie by remember { mutableStateOf("") }
    var showNgaLogin by remember { mutableStateOf(false) }
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
            OutlinedTextField(
                value = rawCookie,
                onValueChange = { text ->
                    rawCookie = text
                    val parsed = parseNgaCookieText(text)
                    if (parsed.uid.isNotEmpty()) uid = parsed.uid
                    if (parsed.cid.isNotEmpty()) cid = parsed.cid
                },
                label = { Text("完整 Cookie（自动解析）") },
                placeholder = { Text("可整段粘贴浏览器 Cookie，自动填入上方两栏") },
                minLines = 2,
                maxLines = 4,
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
                TextButton(onClick = { showNgaLogin = true }) { Text("浏览器登录") }
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
    if (showNgaLogin) {
        NgaLoginDialog(
            onDismiss = { showNgaLogin = false },
            onExtracted = { cookie ->
                val parsed = parseNgaCookieText(cookie)
                if (parsed.uid.isNotEmpty()) uid = parsed.uid
                if (parsed.cid.isNotEmpty()) cid = parsed.cid
                showNgaLogin = false
            },
        )
    }
}

/* ---------------- 下载 / 更新 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var tidText by remember { mutableStateOf("") }
    var authorIdText by remember { mutableStateOf("") }
    var maxFloorsText by remember { mutableStateOf("") }
    var themeDark by remember { mutableStateOf(false) }
    var perChapterText by remember { mutableStateOf("20") }
    var imageMode by remember { mutableStateOf("online") }
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
                listOf("online" to "在线", "embedded" to "内嵌", "none" to "无图")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = imageMode == value,
                            onClick = { imageMode = value },
                            label = { Text(label) },
                        )
                    }
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
                            putExtra("imageMode", imageMode)
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
                                putExtra("imageMode", imageMode)
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

