package io.github.gighi947.ankeshelf.ui.reader.native

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.Bookmark
import io.github.gighi947.ankeshelf.data.HL_COLORS
import io.github.gighi947.ankeshelf.data.Highlight
import io.github.gighi947.ankeshelf.ui.reader.ReaderSelection
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing

/**
 * 标注色板：与 `assets/reader/reader.css` 的 `mark.hl-*` 同色相（此处取不透明版本
 * 作为色点），并与桌面 `web/js/annotations.js` 的 COLOR_HEX 一致。
 * 属设计令牌的显式例外（正文标注色是内容语义，不随主题变化）。
 */
internal val HL_SWATCHES: Map<String, Color> = mapOf(
    "yellow" to Color(0xFFFACC15),
    "green" to Color(0xFF4ADE80),
    "blue" to Color(0xFF38BDF8),
    "pink" to Color(0xFFF472B6),
    "purple" to Color(0xFFC084FC),
    "cyan" to Color(0xFF22D3EE),
)

private val SELECTION_BAR_HEIGHT = AnkeSpacing.xl * 2

/**
 * 选中文本后的标注工具条：6 色高亮 + 加笔记 + 关闭。
 * 位置跟随选区矩形（CSS px == dp，WebView viewport 与本 Box 同坐标系）：
 * 优先放在选区上方，空间不足时放到下方，并夹在屏幕内。
 */
@Composable
internal fun BoxScope.ReaderSelectionBar(
    selection: ReaderSelection,
    barBg: Color,
    fg: Color,
    onColor: (String) -> Unit,
    onNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    var barWidth by remember { mutableStateOf(0.dp) }

    val selTop = selection.top.dp
    val selBottom = selection.bottom.dp
    val above = selTop - SELECTION_BAR_HEIGHT - AnkeSpacing.sm
    val y = if (above > AnkeSpacing.lg) above else selBottom + AnkeSpacing.sm
    val center = ((selection.left + selection.right) / 2f).dp
    val maxX = (screenWidth - barWidth - AnkeSpacing.sm).coerceAtLeast(AnkeSpacing.sm)
    val x = (center - barWidth / 2).coerceIn(AnkeSpacing.sm, maxX)

    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = x, y = y)
            .onSizeChanged { size ->
                with(density) { barWidth = size.width.toDp() }
            }
            .background(barBg.copy(alpha = 0.97f), AnkeRadius.large)
            .border(1.dp, fg.copy(alpha = 0.16f), AnkeRadius.large)
            .padding(horizontal = AnkeSpacing.sm, vertical = AnkeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
    ) {
        for (name in HL_COLORS) {
            val swatch = HL_SWATCHES[name] ?: continue
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(swatch, CircleShape)
                    .border(1.dp, fg.copy(alpha = 0.28f), CircleShape)
                    .clickable { onColor(name) },
            )
        }
        IconButton(onClick = onNote, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "加笔记", tint = fg)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "取消选择", tint = fg)
        }
    }
}

/** 笔记编辑对话框（新建高亮附笔记 / 修改已有笔记）。 */
@Composable
internal fun ReaderNoteDialog(
    initialNote: String,
    quote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("笔记") },
        text = {
            Column {
                if (quote.isNotBlank()) {
                    Text(
                        quote.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = AnkeSpacing.sm),
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(5000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写点想法") },
                    minLines = 3,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(note) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 点击已有高亮：改色 / 编辑笔记 / 删除。 */
@Composable
internal fun ReaderHighlightDialog(
    highlight: Highlight,
    onColor: (String) -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标注") },
        text = {
            Column {
                Text(
                    highlight.text.take(200),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (highlight.note.isNotBlank()) {
                    Text(
                        "笔记：${highlight.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AnkeSpacing.sm),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = AnkeSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                ) {
                    for (name in HL_COLORS) {
                        val swatch = HL_SWATCHES[name] ?: continue
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    if (name == highlight.color) 2.dp else 1.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (name == highlight.color) 0.85f else 0.24f,
                                    ),
                                    CircleShape,
                                )
                                .clickable { onColor(name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onEditNote) { Text("笔记") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm)) {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 标注抽屉：书签 + 高亮列表，点击跳转，可删除。 */
@Composable
internal fun BoxScope.ReaderAnnotationsDrawer(
    visible: Boolean,
    highlights: List<Highlight>,
    bookmarks: List<Bookmark>,
    chapterTitleFn: (Int) -> String,
    onJump: (chapter: Int, offset: Int) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.86f)
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(AnkeSpacing.md),
        ) {
            Text("标注与书签", style = MaterialTheme.typography.titleMedium)
            if (highlights.isEmpty() && bookmarks.isEmpty()) {
                Text(
                    "还没有标注：长按正文选中文字即可高亮或加笔记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.md),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = AnkeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
            ) {
                if (bookmarks.isNotEmpty()) {
                    item {
                        Text(
                            "书签 ${bookmarks.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(bookmarks, key = { it.id }) { bm ->
                        AnnotationRow(
                            leading = {
                                Icon(
                                    Icons.Filled.Bookmark,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            title = bm.text.ifBlank { "书签" },
                            subtitle = chapterTitleFn(bm.chapter_index),
                            onClick = { onJump(bm.chapter_index, bm.offset) },
                            onDelete = { onDeleteBookmark(bm.id) },
                        )
                    }
                }
                if (highlights.isNotEmpty()) {
                    item {
                        Text(
                            "高亮 ${highlights.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = AnkeSpacing.sm),
                        )
                    }
                    items(highlights, key = { it.id }) { h ->
                        AnnotationRow(
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            HL_SWATCHES[h.color] ?: MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        ),
                                )
                            },
                            title = h.text.ifBlank { "高亮" },
                            subtitle = buildString {
                                append(chapterTitleFn(h.chapter_index))
                                if (h.note.isNotBlank()) append(" · 有笔记")
                            },
                            onClick = { onJump(h.chapter_index, h.start_offset) },
                            onDelete = { onDeleteHighlight(h.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, AnkeRadius.small)
            .clickable(onClick = onClick)
            .padding(horizontal = AnkeSpacing.sm, vertical = AnkeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
