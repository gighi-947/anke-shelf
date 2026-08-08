package io.github.gighi947.ankeshelf.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing

/**
 * 书籍管理弹层：长按封面/行后弹出（重命名 / 删除）。
 * 书架与“已下载”页共用；删除前有二次确认，重命名同步书架与原生书 meta。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BookManagementOverlay(
    manageBook: BookRecord?,
    onDismiss: () -> Unit,
    onRename: (BookRecord, String) -> Unit,
    onDelete: (BookRecord) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<BookRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<BookRecord?>(null) }

    manageBook?.let { rec ->
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AnkeSpacing.lg)
                    .padding(bottom = AnkeSpacing.xxl),
            ) {
                Text(
                    rec.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = AnkeSpacing.sm),
                )
                ManageRow(Icons.Filled.Edit, "重命名") {
                    renameTarget = rec
                    onDismiss()
                }
                ManageRow(Icons.Filled.Delete, "删除", destructive = true) {
                    deleteTarget = rec
                    onDismiss()
                }
            }
        }
    }

    renameTarget?.let { rec ->
        var name by remember(rec.id) { mutableStateOf(rec.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("书名") },
                    shape = MaterialTheme.shapes.medium,
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(rec, name.trim())
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书籍？") },
            text = { Text("将删除「${rec.title}」及其进度、断点与本地文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    onDelete(rec)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ManageRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AnkeRadius.small)
            .clickable(onClick = onClick)
            .padding(AnkeSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = AnkeSpacing.md),
        )
    }
}
