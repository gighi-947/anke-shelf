package io.github.gighi947.ankeshelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.BookTag
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing

private val PRESET_TAG_COLORS = listOf(
    "#2e86ab", "#6f8d87", "#b8860b", "#c05a5a", "#7a5ac0",
    "#4a8f5a", "#b0568a", "#8b5a2b",
)

internal fun tagColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex.ifBlank { "#8b5a2b" }))
}.getOrDefault(Color(0xFF8B5A2B))

/** 书架卡片上的标签行：完整显示全部标签，必要时换行，不截断。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookTagRow(tags: List<BookTag>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(top = AnkeSpacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xxs),
    ) {
        tags.forEach { tag ->
            val color = tagColor(tag.color)
            Surface(
                shape = AnkeRadius.pill,
                color = color.copy(alpha = 0.16f),
                contentColor = color,
            ) {
                Text(
                    tag.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = AnkeSpacing.sm, vertical = AnkeSpacing.xxs),
                )
            }
        }
    }
}

/** 标签筛选行：全部 + 所有出现过的标签。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookTagFilterRow(
    allTags: List<BookTag>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (allTags.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(top = AnkeSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xxs),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") },
        )
        allTags.distinctBy { it.name }.forEach { tag ->
            FilterChip(
                selected = selected == tag.name,
                onClick = { onSelect(if (selected == tag.name) null else tag.name) },
                label = { Text(tag.name) },
            )
        }
    }
}

/** 标签编辑弹窗：可增删标签、设置名字（≤10 字）与预设颜色。 */
@Composable
fun BookTagEditorDialog(
    bookTitle: String,
    tags: List<BookTag>,
    onDismiss: () -> Unit,
    onSave: (List<BookTag>) -> Unit,
) {
    var draft by remember(tags) { mutableStateOf(tags.toList()) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(PRESET_TAG_COLORS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.padding(top = AnkeSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xs),
                ) {
                    draft.forEach { tag ->
                        val color = tagColor(tag.color)
                        Surface(
                            shape = AnkeRadius.pill,
                            color = color.copy(alpha = 0.16f),
                            contentColor = color,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = AnkeSpacing.sm, end = AnkeSpacing.xs),
                            ) {
                                Text(tag.name, style = MaterialTheme.typography.labelMedium)
                                IconButton(
                                    onClick = { draft = draft.filterNot { it.name == tag.name } },
                                    modifier = Modifier.size(AnkeSpacing.xl),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除标签",
                                        tint = color,
                                        modifier = Modifier.size(AnkeSpacing.md),
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 10) newName = it },
                    label = { Text("新标签（最多 10 字）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AnkeSpacing.md),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AnkeSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PRESET_TAG_COLORS.forEach { hex ->
                        val color = tagColor(hex)
                        Box(
                            modifier = Modifier
                                .size(AnkeSpacing.xl)
                                .background(color, CircleShape)
                                .border(
                                    width = if (newColor == hex) 2.dp else 1.dp,
                                    color = if (newColor == hex) MaterialTheme.colorScheme.onSurface else color.copy(alpha = 0.6f),
                                    shape = CircleShape,
                                )
                                .clickable { newColor = hex },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty() && draft.none { it.name == name }) {
                            draft = draft + BookTag(name, newColor)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank() && draft.none { it.name == newName.trim() },
                    modifier = Modifier.padding(top = AnkeSpacing.xs),
                ) { Text("添加标签") }
            }
        },
        confirmButton = {
            TextButton(
                shape = MaterialTheme.shapes.small,
                onClick = { onSave(draft) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("取消") }
        },
    )
}
