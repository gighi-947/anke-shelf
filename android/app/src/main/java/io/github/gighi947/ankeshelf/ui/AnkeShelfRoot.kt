package io.github.gighi947.ankeshelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.ui.reader.ReaderScreen
import io.github.gighi947.ankeshelf.ui.settings.SettingsScreen
import io.github.gighi947.ankeshelf.ui.shelf.BookshelfScreen
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme

/** 应用外壳（M2）：书架/设置/阅读器路由；阅读器全屏，其余页面带底部导航。 */
@Composable
fun AnkeShelfRoot(container: AppContainer) {
    var routeName by rememberSaveable { mutableStateOf("shelf") }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var settingsTick by remember { mutableIntStateOf(0) }
    val themeName = remember(settingsTick) { container.settings.getAll().theme }
    val context = LocalContext.current

    val books = remember(refresh) { container.repository.listBooks() }
    val record = remember(bookId, refresh) {
        bookId?.let { id -> books.find { it.record.id == id }?.record }
    }
    val session = remember(record) { record?.let { container.repository.openSession(it) } }
    val savedProgress = remember(record) {
        record?.let { container.repository.progressOf(it.id) }
    }

    AnkeShelfTheme(themeName = themeName) {
        if (routeName == "reader") {
            if (session != null) {
                ReaderScreen(
                    session = session,
                    initialChapter = chapter,
                    savedOffset = savedProgress?.text_offset ?: 0,
                    readerSettings = container.settings.getAll(),
                    onProgress = { idx, offset ->
                        container.repository.saveProgress(session.id, idx, offset)
                    },
                    onBack = {
                        routeName = "shelf"
                        refresh++
                    },
                )
            } else {
                LaunchedEffect(Unit) { routeName = "shelf" }
            }
        } else {
            val tabs = listOf(
                "shelf" to "📚 书架",
                "download" to "⬇️ 下载",
                "search" to "🔍 搜索",
                "settings" to "⚙️ 设置",
                "stats" to "📊 统计",
            )
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, (name, label) ->
                            NavigationBarItem(
                                selected = routeName == name,
                                onClick = { routeName = name },
                                icon = { Text(label.substringBefore(' ')) },
                                label = { Text(label.substringAfter(' ')) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    when (routeName) {
                        "settings" -> SettingsScreen(
                            settings = container.settings,
                            onBack = { routeName = "shelf" },
                            onChanged = { settingsTick++ },
                        )
                        "shelf" -> BookshelfScreen(
                            books = books,
                            coversDir = container.appPaths.coversDir,
                            onImport = { uri ->
                                container.repository.importEpub(context, uri)
                                refresh++
                            },
                            onOpen = { rec ->
                                bookId = rec.id
                                chapter = 0
                                routeName = "reader"
                            },
                            onSettings = { routeName = "settings" },
                        )
                        else -> PlaceholderScreen(tabs.first { it.first == routeName }.second)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
        Text(
            "该页面将在后续里程碑实现",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
