package io.github.gighi947.ankeshelf.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NgaConfigPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.NgaProgress
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** NGA 下载页（M3）：配置 + 下载表单 + 进度/取消 + 增量更新入口。 */
@Composable
fun DownloadScreen(
    container: AppContainer,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val initial = remember { container.ngaConfig.load() }
    var uid by remember { mutableStateOf(initial.uid) }
    var cid by remember { mutableStateOf(initial.cid) }
    var ua by remember { mutableStateOf(initial.ua) }
    var configured by remember { mutableStateOf(initial.configured) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(AnkeSpacing.lg),
    ) {
        PageHeaderTitle("NGA 下载")

        Text(
            "登录配置（仅存本机）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AnkeSpacing.md),
        )
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
            TextButton(shape = MaterialTheme.shapes.small, onClick = {
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
            "下载 / 更新",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AnkeSpacing.lg),
        )
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AnkeSpacing.md),
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

        if (NgaServiceStatus.running || status.stage == "done" || status.stage == "error" || status.stage == "cancelled") {
            Column(modifier = Modifier.padding(top = AnkeSpacing.lg)) {
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

        NgaBooksExportSection(container = container, onChanged = onChanged)
    }
}

/** 已下载 NGA 书列表与导出（EPUB / Markdown，SAF 自选位置）。 */
@Composable
private fun NgaBooksExportSection(
    container: AppContainer,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val books = remember(tick) {
        container.shelf.listBooks().filter { it.nga_tid > 0 }
    }
    if (books.isEmpty()) return

    Text(
        "已下载 NGA 书",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = AnkeSpacing.lg),
    )
    books.forEach { book ->
        val nativeDir = java.io.File(book.path)
        val epubLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/epub+zip"),
        ) { uri ->
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
        ) { uri ->
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
