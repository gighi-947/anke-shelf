package io.github.gighi947.ankeshelf.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadParams
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaDownloader
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing

/**
 * 更新参数对话框（对齐桌面“更新帖子”面板）：预填最近一次下载/更新设置，
 * 可临时修改只看楼主、主题、图片模式、每章楼层数；仅对本次新增楼层生效。
 * 书架封面/列表与“已下载”页共用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NgaUpdateDialog(
    book: BookRecord,
    container: AppContainer,
    onDismiss: () -> Unit,
    onConfirm: (NgaDownloadParams) -> Unit,
) {
    val defaults = remember(book.id) {
        runCatching {
            NgaDownloader(container.appPaths, container.repository, container.ngaConfig)
                .defaultsFor(book.id)
        }.getOrNull()
    }
    var authorIdText by remember(book.id) {
        mutableStateOf((defaults?.authorId ?: 0L).takeIf { it > 0 }?.toString() ?: "")
    }
    var themeDark by remember(book.id) { mutableStateOf((defaults?.theme ?: "light") == "dark") }
    var perChapterText by remember(book.id) { mutableStateOf((defaults?.perChapter ?: 20).toString()) }
    var imageMode by remember(book.id) { mutableStateOf(defaults?.imageMode ?: "online") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AnkeRadius.large,
        title = { Text("更新「${book.title}」") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = authorIdText,
                    onValueChange = { authorIdText = it.filter { c -> c.isDigit() } },
                    label = { Text("只看楼主 uid（0=全部）") },
                    singleLine = true,
                    shape = AnkeRadius.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                OutlinedTextField(
                    value = perChapterText,
                    onValueChange = { perChapterText = it.filter { c -> c.isDigit() } },
                    label = { Text("每章楼层数") },
                    singleLine = true,
                    shape = AnkeRadius.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "更新设置仅对本次新增楼层生效，不影响已有楼层。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(
                shape = AnkeRadius.small,
                onClick = {
                    onConfirm(
                        NgaDownloadParams(
                            tid = book.nga_tid.toLong(),
                            authorId = authorIdText.trim().toLongOrNull() ?: 0L,
                            imageMode = imageMode,
                            theme = if (themeDark) "dark" else "light",
                            perChapter = perChapterText.trim().toIntOrNull()?.coerceIn(1, 200) ?: 20,
                        ),
                    )
                },
            ) { Text("开始更新") }
        },
        dismissButton = {
            TextButton(shape = AnkeRadius.small, onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 携带参数启动更新前台服务（书架与已下载页共用）。 */
fun launchNgaUpdate(context: Context, book: BookRecord, params: NgaDownloadParams) {
    val intent = Intent(context, NgaDownloadService::class.java).apply {
        action = NgaDownloadService.ACTION_START
        putExtra("action", "update")
        putExtra("bookId", book.id)
        putExtra("tid", params.tid)
        putExtra("authorId", params.authorId)
        putExtra("theme", params.theme)
        putExtra("perChapter", params.perChapter)
        putExtra("imageMode", params.imageMode)
    }
    ContextCompat.startForegroundService(context, intent)
}
