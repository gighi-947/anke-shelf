package io.github.gighi947.ankeshelf.ui.download

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.GululuIdResult
import io.github.gighi947.ankeshelf.data.GululuSource
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.GululuImportService
import io.github.gighi947.ankeshelf.service.GululuServiceStatus
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import kotlinx.coroutines.delay

/**
 * 骨碌碌导入面板：粘贴公开链接或书籍 ID → 选择图片模式 → 前台服务导入为标准 EPUB。
 * 与桌面「骨碌碌导入」等价（Windows 在下载页同一处入口）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GululuPanel(container: AppContainer, onChanged: () -> Unit) {
    val context = LocalContext.current
    var sourceText by remember { mutableStateOf("") }
    var imageMode by remember { mutableStateOf("online") }
    var running by remember { mutableStateOf(GululuServiceStatus.running) }
    var stage by remember { mutableStateOf(GululuServiceStatus.stage) }
    var detail by remember { mutableStateOf(GululuServiceStatus.detail) }
    var error by remember { mutableStateOf(GululuServiceStatus.error) }
    var current by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var lastStage = ""
        while (true) {
            running = GululuServiceStatus.running
            stage = GululuServiceStatus.stage
            detail = GululuServiceStatus.detail
            error = GululuServiceStatus.error
            current = GululuServiceStatus.current
            total = GululuServiceStatus.total
            if (lastStage != stage && stage == "done") onChanged()
            lastStage = stage
            delay(500)
        }
    }

    // 输入即校验：非法链接/ID 立刻给出与桌面一致的提示，不等到任务失败。
    val parsed = remember(sourceText) {
        if (sourceText.isBlank()) null else GululuSource.extractBookId(sourceText)
    }
    val sourceId = (parsed as? GululuIdResult.Ok)?.bookId ?: 0
    val inputError = (parsed as? GululuIdResult.Err)?.message.orEmpty()
    // 本机已有该书才显示「检查更新」（对齐桌面：更新前必须先导入过）
    val existing = remember(sourceId, stage) {
        sourceId > 0 &&
            java.io.File(
                java.io.File(container.appPaths.gululuLibraryDir, sourceId.toString()),
                "post.epub",
            ).isFile
    }

    DownloadList {
        DownloadSection("导入来源") {
            OutlinedTextField(
                value = sourceText,
                onValueChange = { sourceText = it },
                label = { Text("骨碌碌书籍 ID 或公开链接") },
                singleLine = true,
                isError = inputError.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                inputError.ifEmpty {
                    if (sourceId > 0) "已识别书籍 ID：$sourceId" else "支持 https://www.gululu.world/book/<id> 或纯数字 ID"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (inputError.isNotEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = AnkeSpacing.xs),
            )
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
            Text(
                "内嵌会把正文图片打包进 EPUB（仅 HTTPS 位图、单图 25 MB 上限，失败图片显示占位）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = AnkeSpacing.xs),
            )
        }

        DownloadSection("操作") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                Button(
                    shape = MaterialTheme.shapes.small,
                    enabled = sourceId > 0 && !running,
                    onClick = {
                        val intent = Intent(context, GululuImportService::class.java).apply {
                            action = GululuImportService.ACTION_START
                            putExtra("sourceId", sourceId)
                            putExtra("imageMode", imageMode)
                        }
                        ContextCompat.startForegroundService(context, intent)
                    },
                ) { Text("开始导入") }
                if (existing) {
                    Button(
                        shape = MaterialTheme.shapes.small,
                        enabled = sourceId > 0 && !running,
                        onClick = {
                            val intent = Intent(context, GululuImportService::class.java).apply {
                                action = GululuImportService.ACTION_START
                                putExtra("action", "update")
                                putExtra("sourceId", sourceId)
                                putExtra("imageMode", imageMode)
                            }
                            ContextCompat.startForegroundService(context, intent)
                        },
                    ) { Text("检查更新") }
                }
                if (running) {
                    Button(
                        shape = MaterialTheme.shapes.small,
                        onClick = {
                            context.startService(
                                Intent(context, GululuImportService::class.java)
                                    .setAction(GululuImportService.ACTION_CANCEL),
                            )
                        },
                    ) { Text("取消") }
                }
            }
        }

        DownloadSection("任务状态") {
            Text(
                when {
                    error.isNotEmpty() -> "失败：$error"
                    running -> detail.ifEmpty { "正在导入…" }
                    stage == "done" -> detail.ifEmpty { "导入完成" }
                    stage == "cancelled" -> "已取消"
                    else -> "空闲"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error.isNotEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (running && total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AnkeSpacing.sm),
                )
            }
        }
    }
}
