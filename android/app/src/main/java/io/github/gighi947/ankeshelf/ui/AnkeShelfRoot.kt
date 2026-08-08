package io.github.gighi947.ankeshelf.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.ui.download.DownloadScreen
import io.github.gighi947.ankeshelf.ui.reader.ReaderScreen
import io.github.gighi947.ankeshelf.ui.search.SearchScreen
import io.github.gighi947.ankeshelf.ui.settings.SettingsScreen
import io.github.gighi947.ankeshelf.ui.shelf.BookshelfScreen
import io.github.gighi947.ankeshelf.ui.stats.StatsScreen
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme
import java.io.File

private data class TabSpec(
    val name: String,
    val label: String,
    val filled: ImageVector,
    val outlined: ImageVector,
)

private val TABS = listOf(
    TabSpec("shelf", "书架", Icons.Filled.LibraryBooks, Icons.Outlined.LibraryBooks),
    TabSpec("download", "下载", Icons.Filled.FileDownload, Icons.Outlined.FileDownload),
    TabSpec("search", "搜索", Icons.Filled.Search, Icons.Outlined.Search),
    TabSpec("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
    TabSpec("stats", "统计", Icons.Filled.Insights, Icons.Outlined.Insights),
)

/** 应用外壳（M4）：M3 底部导航 + 五页路由；阅读器全屏沉浸。 */
@Composable
fun AnkeShelfRoot(container: AppContainer) {
    var routeName by rememberSaveable { mutableStateOf("shelf") }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableIntStateOf(0) }
    var jumpOffset by rememberSaveable { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var settingsTick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val activity = LocalActivity.current

    val books = remember(refresh) { container.repository.listBooks() }
    val record = remember(bookId, refresh) {
        bookId?.let { id -> books.find { it.record.id == id }?.record }
    }
    val session = remember(record) { record?.let { container.repository.openSession(it) } }
    val savedProgress = remember(record) {
        record?.let { container.repository.progressOf(it.id) }
    }
    val statsGlobal = remember(refresh, settingsTick) { container.stats.getGlobal() }

    // Compose 1.11 的 MotionDurationScale 会自动按系统动画时长缩放（含"关闭动画"）。
    val duration = 200

    AnkeShelfTheme(settings = container.settings.getAll()) {
        AnimatedContent(
            targetState = routeName == "reader",
            transitionSpec = {
                fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2))
            },
            label = "reader-route",
        ) { isReader ->
            if (isReader) {
                if (session != null) {
                    ReaderScreen(
                        session = session,
                        initialChapter = chapter,
                        savedOffset = savedProgress?.text_offset ?: 0,
                        jumpOffset = jumpOffset,
                        readerSettings = container.settings.getAll(),
                        onProgress = { idx, offset ->
                            container.repository.saveProgress(session.id, idx, offset)
                        },
                        onSettingsPatch = { patch ->
                            container.settings.update(patch)
                            settingsTick++
                        },
                        onStatsTick = { secs, pages ->
                            container.stats.recordReading(session.id, secs, pages)
                        },
                        onBack = {
                            jumpOffset = null
                            routeName = "shelf"
                            refresh++
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { routeName = "shelf" }
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar {
                            TABS.forEach { tab ->
                                NavigationBarItem(
                                    selected = routeName == tab.name,
                                    onClick = { routeName = tab.name },
                                    icon = {
                                        Icon(
                                            if (routeName == tab.name) tab.filled else tab.outlined,
                                            contentDescription = tab.label,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    AnimatedContent(
                        targetState = routeName,
                        transitionSpec = {
                            (
                                fadeIn(tween(180)) +
                                    slideInHorizontally(tween(220)) { it / 10 }
                                ) togetherWith (
                                fadeOut(tween(90)) +
                                    slideOutHorizontally(tween(160)) { -it / 12 }
                                )
                        },
                        label = "tab-route",
                    ) { name ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            when (name) {
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
                                        jumpOffset = null
                                        routeName = "reader"
                                    },
                                    onSettings = { routeName = "settings" },
                                )
                                "download" -> DownloadScreen(
                                    container = container,
                                    onChanged = { refresh++ },
                                )
                                "search" -> SearchScreen(
                                    books = books,
                                    container = container,
                                    onOpenHit = { id, ch, offset ->
                                        bookId = id
                                        chapter = ch
                                        jumpOffset = offset
                                        routeName = "reader"
                                    },
                                    onBack = { routeName = "shelf" },
                                )
                                "settings" -> SettingsScreen(
                                    settings = container.settings,
                                    refreshKey = settingsTick,
                                    statsGlobal = statsGlobal,
                                    appPaths = container.appPaths,
                                    onOpenStats = { routeName = "stats" },
                                    onBack = { routeName = "shelf" },
                                    onChanged = { settingsTick++ },
                                    onClearAllData = {
                                        runCatching {
                                            File(context.filesDir, "AnkeShelf").deleteRecursively()
                                        }
                                        context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
                                            .edit().clear().apply()
                                        activity?.finish()
                                    },
                                )
                                "stats" -> StatsScreen(
                                    books = books,
                                    container = container,
                                    refreshKey = refresh,
                                    onBack = { routeName = "shelf" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
