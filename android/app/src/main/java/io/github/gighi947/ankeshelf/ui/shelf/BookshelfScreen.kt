package io.github.gighi947.ankeshelf.ui.shelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import java.io.File
import kotlin.math.roundToInt

/** 书架页：空态 + 网格（M2：SAF 导入 EPUB、显示进度、进入阅读器）。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BookshelfScreen(
    books: List<BookUi>,
    coversDir: File,
    onImport: (Uri) -> Unit,
    onOpen: (BookRecord) -> Unit,
    onSettings: () -> Unit,
    shelfView: String,
    onShelfViewChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }
    val launchPicker = {
        launcher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { PageHeaderTitle("安科书架") },
                actions = {
                    IconButton(onClick = {
                        onShelfViewChange(if (shelfView == "list") "grid" else "list")
                    }) {
                        Icon(
                            if (shelfView == "list") Icons.Filled.ViewModule else Icons.Filled.ViewList,
                            contentDescription = if (shelfView == "list") "切换到网格视图" else "切换到列表视图",
                        )
                    }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = launchPicker) { Text("导入") }
                    TextButton(onClick = onSettings) { Text("设置") }
                },
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(AnkeSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("书架为空", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "导入 EPUB 开始阅读",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.sm, bottom = AnkeSpacing.lg),
                )
                Button(shape = MaterialTheme.shapes.small, onClick = launchPicker) { Text("导入 EPUB") }
            }
        } else {
            if (shelfView == "list") {
                LazyColumn(
                    contentPadding = PaddingValues(AnkeSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    lazyItems(books, key = { it.record.id }) { ui ->
                        BookListRow(ui, coversDir, onClick = { onOpen(ui.record) })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(AnkeSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    items(books, key = { it.record.id }) { ui ->
                        BookCard(ui, coversDir, onClick = { onOpen(ui.record) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BookListRow(ui: BookUi, coversDir: File, onClick: () -> Unit) {
    val coverFile = ui.record.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    contentDescription = ui.record.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(ui.record.title.take(1).ifBlank { "书" }, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AnkeSpacing.md),
        ) {
            Text(
                ui.record.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.record.author.ifBlank { "未知作者" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AnkeSpacing.xxs),
            )
            Text(
                "${ui.progressPct.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AnkeSpacing.xxs),
            )
        }
    }
}

@Composable
private fun BookCard(ui: BookUi, coversDir: File, onClick: () -> Unit) {
    val coverFile = ui.record.cover_rel?.let { File(coversDir, it.substringAfterLast('/')) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    contentDescription = ui.record.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    ui.record.title.take(1).ifBlank { "书" },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Text(
            ui.record.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AnkeSpacing.xs),
        )
        Text(
            ui.record.author.ifBlank { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${ui.progressPct.roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
