package io.github.gighi947.ankeshelf.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FileDownload
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
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.RepoResult
import io.github.gighi947.ankeshelf.ui.download.DownloadScreen
import io.github.gighi947.ankeshelf.ui.reader.native.NativeReaderScreen
import io.github.gighi947.ankeshelf.ui.search.SearchScreen
import io.github.gighi947.ankeshelf.ui.settings.GuideScreen
import io.github.gighi947.ankeshelf.ui.settings.SettingsScreen
import io.github.gighi947.ankeshelf.ui.shelf.BookshelfScreen
import io.github.gighi947.ankeshelf.ui.stats.StatsScreen
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class TabSpec(
    val name: String,
    val label: String,
    val filled: ImageVector,
    val outlined: ImageVector,
)

private val TABS = listOf(
    TabSpec("shelf", "书架", Icons.AutoMirrored.Filled.LibraryBooks, Icons.AutoMirrored.Outlined.LibraryBooks),
    TabSpec("download", "下载", Icons.Filled.FileDownload, Icons.Outlined.FileDownload),
    TabSpec("search", "搜索", Icons.Filled.Search, Icons.Outlined.Search),
    TabSpec("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** 应用外壳：底部导航（书架/下载/搜索/设置）+ 阅读器全屏沉浸。 */
@Composable
fun AnkeShelfRoot(container: AppContainer) {
    var routeName by rememberSaveable { mutableStateOf("shelf") }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableIntStateOf(0) }
    var jumpOffset by rememberSaveable { mutableStateOf<Int?>(null) }
    var guideReturn by rememberSaveable { mutableStateOf("settings") }
    var refresh by remember { mutableIntStateOf(0) }
    var settingsTick by remember { mutableIntStateOf(0) }
    var lastServiceStage by remember { mutableStateOf("") }
    var lastShelfMtime by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    val activity = LocalActivity.current

    val books = remember(refresh) { container.repository.listBooks() }
    val record = remember(bookId, refresh) {
        bookId?.let { id -> books.find { it.record.id == id }?.record }
    }
    val sessionResult = remember(record) { record?.let { container.repository.openSession(it) } }
    val session = remember(sessionResult) { (sessionResult as? RepoResult.Ok)?.value }
    // 打开失败显式提示，不再静默回书架（review3 C3）。
    LaunchedEffect(sessionResult) {
        if (routeName == "reader" && sessionResult is RepoResult.Err) {
            Toast.makeText(
                context,
                "打开书籍失败：${(sessionResult as RepoResult.Err).error.message}",
                Toast.LENGTH_LONG,
            ).show()
            routeName = "shelf"
        }
    }
    // 不能用 record 对象做 key：书架 map 里 record 是稳定实例，refresh++ 后
    // remember 不会重算，导致重进阅读器时读到旧的 savedOffset（进度落后）。
    val savedProgress = remember(refresh, record?.id) {
        record?.let {
            val p = container.repository.progressOf(it.id)
            runCatching { android.util.Log.d("AnkeShelf", "shelf read off=${p?.text_offset} page=${p?.page_index}") }
            p
        }
    }
    val statsGlobal = remember(refresh, settingsTick) { container.stats.getGlobal() }

    // 下载服务状态变化 / 书架文件变化后自动刷新书架，避免下载完看不到新书。
    LaunchedEffect(Unit) {
        while (isActive) {
            val shelfFile = container.appPaths.shelfFile
            val mtime = if (shelfFile.exists()) shelfFile.lastModified() else 0L
            if (lastShelfMtime != 0L && mtime != lastShelfMtime) {
                container.shelf.load()
                refresh++
            }
            lastShelfMtime = mtime
            val stage = NgaServiceStatus.snapshot().stage
            if (lastServiceStage != stage &&
                (stage == "done" || stage == "error" || stage == "cancelled")
            ) {
                refresh++
                val msg = when (stage) {
                    "error" -> "NGA 任务失败：${NgaServiceStatus.error}"
                    "cancelled" -> "NGA 任务已取消"
                    else -> NgaServiceStatus.detail.ifBlank { "NGA 任务完成" }
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            lastServiceStage = stage
            delay(1000)
        }
    }

    // 系统返回/侧滑返回：按当前页面回到上一级，避免从子页面直接退出应用。
    // 阅读器内由 NativeReaderScreen 自己的 BackHandler 处理（返回书架）。
    BackHandler(enabled = routeName != "shelf" && routeName != "reader") {
        when (routeName) {
            "download", "search", "settings" -> routeName = "shelf"
            "stats" -> routeName = "settings"
            "guide" -> routeName = guideReturn
        }
    }

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
                    NativeReaderScreen(
                        session = session,
                        initialChapter = chapter,
                        savedOffset = jumpOffset ?: (savedProgress?.text_offset ?: 0),
                        initialPage = if (jumpOffset == null) savedProgress?.page_index ?: -1 else -1,
                        initialTotal = if (jumpOffset == null) savedProgress?.page_total ?: -1 else -1,
                        container = container,
                        readerSettings = container.settings.getAll(),
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
                                    container = container,
                                    shelfView = container.settings.getAll().shelf_view,
                                    onOpenDownload = { routeName = "download" },
                                    onOpenGuide = {
                                        guideReturn = "shelf"
                                        routeName = "guide"
                                    },
                                    onRename = { rec, newTitle ->
                                        container.repository.renameBook(rec, newTitle)
                                        refresh++
                                    },
                                    onDelete = { rec ->
                                        if (!container.repository.removeBook(rec)) {
                                            Toast.makeText(
                                                context,
                                                "删除书籍文件失败，书架条目已移除",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        refresh++
                                    },
                                    onSetCover = { rec, uri ->
                                        when (val result = container.repository.setCustomCover(rec, uri, context)) {
                                            is RepoResult.Ok -> Unit
                                            is RepoResult.Err ->
                                                Toast.makeText(context, result.error.message, Toast.LENGTH_SHORT).show()
                                        }
                                        refresh++
                                    },
                                    onResetCover = { rec ->
                                        container.repository.resetCover(rec)
                                        refresh++
                                    },
                                    onEditTags = { rec, tags ->
                                        container.repository.updateBookTags(rec.id, tags)
                                        refresh++
                                    },
                                    onShelfViewChange = { view ->
                                        container.settings.update(SettingsPatch(shelf_view = view))
                                        settingsTick++
                                    },
                                    sort = container.settings.getAll().shelf_sort,
                                    onSortChange = { value ->
                                        container.settings.update(SettingsPatch(shelf_sort = value))
                                        settingsTick++
                                    },
                                    onToggleHideBrackets = {
                                        val cur = container.settings.getAll().hide_title_brackets
                                        container.settings.update(
                                            SettingsPatch(hide_title_brackets = !cur),
                                        )
                                        settingsTick++
                                    },
                                    onImport = { uri ->
                                        when (val result = container.repository.importEpub(context, uri)) {
                                            is RepoResult.Ok -> Unit
                                            is RepoResult.Err ->
                                                Toast.makeText(context, result.error.message, Toast.LENGTH_SHORT).show()
                                        }
                                        refresh++
                                    },
                                    onOpen = { rec ->
                                        // 书架打开：恢复上次阅读章节（text_offset 由
                                        // savedProgress 一并传给阅读器，同一章内续读）。
                                        val p = container.repository.progressOf(rec.id)
                                        bookId = rec.id
                                        chapter = p?.chapter_index
                                            ?.coerceIn(0, (rec.chapter_count - 1).coerceAtLeast(0))
                                            ?: 0
                                        jumpOffset = null
                                        routeName = "reader"
                                    },
                                )
                                "download" -> DownloadScreen(
                                    container = container,
                                    onChanged = { refresh++ },
                                    onBack = { routeName = "shelf" },
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
                                    books = books,
                                    annotations = container.annotations,
                                    statsGlobal = statsGlobal,
                                    appPaths = container.appPaths,
                                    onOpenStats = { routeName = "stats" },
                                    onOpenGuide = {
                                        guideReturn = "settings"
                                        routeName = "guide"
                                    },
                                    onBack = { routeName = "shelf" },
                                    onChanged = { settingsTick++ },
                                    onClearAllData = {
                                        runCatching {
                                            container.appPaths.root.deleteRecursively()
                                        }
                                        context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
                                            .edit().clear().apply()
                                        context.getSharedPreferences("guide", android.content.Context.MODE_PRIVATE)
                                            .edit().clear().apply()
                                        activity?.finish()
                                    },
                                )
                                "stats" -> StatsScreen(
                                    books = books,
                                    container = container,
                                    refreshKey = refresh,
                                    onBack = { routeName = "settings" },
                                )
                                "guide" -> GuideScreen(onBack = { routeName = guideReturn })
                            }
                        }
                    }
                }
            }
        }
    }
}
