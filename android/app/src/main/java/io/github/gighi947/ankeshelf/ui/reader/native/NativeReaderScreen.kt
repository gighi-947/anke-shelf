package io.github.gighi947.ankeshelf.ui.reader.native

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.gighi947.ankeshelf.data.ChapterReadResult
import io.github.gighi947.ankeshelf.data.AnnotationPatch
import io.github.gighi947.ankeshelf.data.GululuSecretReveal
import io.github.gighi947.ankeshelf.data.Highlight
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.LogEvents
import io.github.gighi947.ankeshelf.service.ngaHeaders
import io.github.gighi947.ankeshelf.ui.reader.extractReaderParts
import io.github.gighi947.ankeshelf.ui.reader.buildReaderHtml
import io.github.gighi947.ankeshelf.ui.reader.ChapterProgressTracker
import io.github.gighi947.ankeshelf.ui.reader.ReaderJump
import io.github.gighi947.ankeshelf.ui.reader.ReaderSelection
import io.github.gighi947.ankeshelf.ui.reader.TocTree
import io.github.gighi947.ankeshelf.ui.reader.WebViewChapterView
import io.github.gighi947.ankeshelf.ui.reader.WebViewReaderCallbacks
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt


/** 章节内容 UI 状态：读取失败走显式错误分支，不静默渲染空白页。 */
private sealed interface ChapterUiState {
    data object Loading : ChapterUiState

    /** [plain] 为折叠纯文本（text_offset 坐标系），供百分比与书签摘要使用。 */
    data class Html(val html: String, val plain: String) : ChapterUiState {
        val len: Int get() = plain.length
    }

    data class Error(val message: String) : ChapterUiState
}

/**
 * 阅读页：Compose 外壳 + 安卓专用 WebView 渲染内核（reader-lite.js）。
 * 主题、安全区、进度（text_offset）、目录、图片查看/保存由 Compose 层实现，
 * 正文渲染/分页/楼层样式由 WebView 内核负责。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeReaderScreen(
    session: BookSession,
    initialChapter: Int,
    savedOffset: Int,
    initialPage: Int = -1,
    initialTotal: Int = -1,
    container: AppContainer,
    readerSettings: SettingsData,
    onSettingsPatch: (SettingsPatch) -> Unit,
    onStatsTick: (seconds: Int, pagesFlipped: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var chapterIndex by remember(session.id) { mutableIntStateOf(initialChapter.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0))) }
    var showToc by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    var barsShownAt by remember { mutableStateOf(0L) }
    var lastScrollAt by remember { mutableStateOf(0L) }
    // 手动唤出的控制条保持显示（不再 3 秒自动收），滚动/翻页后才收起。
    var barsHeld by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf(Pair(0, 1)) }
    var scrollRatio by remember { mutableFloatStateOf(0f) }
    var lightboxSrc by remember { mutableStateOf<String?>(null) }
    var pendingSeconds by remember { mutableIntStateOf(0) }
    var flippedPages by remember { mutableIntStateOf(0) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
    // 标注（批 1）：选区 / 新建笔记 / 编辑已有高亮 / 抽屉 / 章内跳转
    var selection by remember { mutableStateOf<ReaderSelection?>(null) }
    var pendingNote by remember { mutableStateOf<ReaderSelection?>(null) }
    var editingHighlight by remember { mutableStateOf<Highlight?>(null) }
    var editingNote by remember { mutableStateOf<Highlight?>(null) }
    var showAnnotations by remember { mutableStateOf(false) }
    // 阅读设置（批 2）：统一设置面板 / 自动滚动 / 标尺 / 速读
    var showSettings by remember { mutableStateOf(false) }
    var autoScrollOn by remember { mutableStateOf(false) }
    var rulerOn by remember { mutableStateOf(readerSettings.show_ruler) }
    var rsvpOn by remember { mutableStateOf(false) }
    // 骨碌碌宿主层（批 8/9）：仅骨碌碌来源的书启用
    val gululuSourceId = session.gululuSourceId
    val isGululu = gululuSourceId > 0
    var gululuFloor by remember(session.id) { mutableIntStateOf(0) }
    var gululuVfx by remember { mutableStateOf("") }
    var gululuBackground by remember { mutableStateOf("") }
    var gululuComments by remember { mutableStateOf<List<GululuCommentUi>>(emptyList()) }
    var gululuCommentStale by remember { mutableStateOf(false) }
    var gululuCommentError by remember { mutableStateOf("") }
    var showGululuComments by remember { mutableStateOf(false) }
    var paragraphFilter by remember { mutableStateOf("") }
    var showOverview by remember { mutableStateOf(false) }
    var showGululuQuickMenu by remember { mutableStateOf(false) }
    var showMusicQuickMenu by remember { mutableStateOf(false) }
    var danmakuOn by remember { mutableStateOf(false) }
    var secretDialog by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var musicTitle by remember { mutableStateOf("") }
    var unlockTick by remember { mutableIntStateOf(0) }
    var chapterStats by remember { mutableStateOf(listOf(0, 0, 0, 0, 0)) }
    var gululuCommand by remember { mutableStateOf("") }
    var gululuCommandToken by remember { mutableIntStateOf(0) }
    val unlockedJson = remember(session.id, unlockTick) {
        if (!isGululu) {
            "[]"
        } else {
            val groups = container.gululuUnlocks.unlockedGroups(session.id)
            android.util.Log.d(
                "AnkeShelf",
                "gululu unlockedJson book=${session.id} count=${groups.size} json=${groups.joinToString(",", "[", "]") { JSONObject.quote(it) }}",
            )
            groups.joinToString(",", "[", "]") { JSONObject.quote(it) }
        }
    }
    val paragraphCommentsJson = remember(gululuComments) {
        val counts = gululuComments.filter { it.paragraphId.isNotEmpty() }
            .groupingBy { it.paragraphId }.eachCount()
        counts.entries.joinToString(",", "{", "}") { (id, count) ->
            "${JSONObject.quote(id)}:$count"
        }
    }

    // 当前楼评论：楼层变化即按需加载（5 分钟缓存 + 离线回退在 service 层）
    LaunchedEffect(gululuSourceId, gululuFloor) {
        if (!isGululu || gululuFloor <= 0) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            container.gululuComments.getComments(gululuSourceId, listOf(gululuFloor))
        }
        val scope = result.floors.firstOrNull()
        gululuComments = flattenGululuComments(scope?.comments ?: emptyList())
        gululuCommentStale = scope?.stale == true
        gululuCommentError = scope?.error.orEmpty().ifEmpty { result.error }
    }

    // 音乐播放器：同曲再点=停止，切歌=换源；退出阅读器释放。
    val musicPlayer = remember { android.media.MediaPlayer() }
    var musicUrl by remember { mutableStateOf("") }
    fun stopMusic() {
        runCatching { if (musicPlayer.isPlaying) musicPlayer.stop() }
        runCatching { musicPlayer.reset() }
        musicUrl = ""
        musicTitle = ""
    }
    fun playMusic(url: String, title: String) {
        if (url == musicUrl) {
            stopMusic()
            return
        }
        runCatching {
            musicPlayer.reset()
            musicPlayer.setDataSource(url)
            musicPlayer.isLooping = true
            musicPlayer.setOnPreparedListener { it.start() }
            musicPlayer.prepareAsync()
            musicUrl = url
            musicTitle = title.ifEmpty { "BGM" }
        }.onFailure {
            LogEvents.event("gululu", "music_failed", "error" to (it.message ?: "unknown"))
            stopMusic()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { musicPlayer.release() }
        }
    }
    var annotationsTick by remember { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf<ReaderJump?>(null) }
    var jumpToken by remember { mutableIntStateOf(0) }
    var clearSelectionToken by remember { mutableIntStateOf(0) }
    // 跨章标注跳转：换章后由 init 的 offset 完成定位（与搜索跳转同路径）。
    var crossJump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var liveOffset by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 恢复锚点每章只取一次：书架刷新等外部变化会重建 savedOffset（可能读到旧值），
    // 不能让它在阅读中途变化并把滚动/分页位置拉回章节首。
    val progressTracker = remember(session.id) {
        ChapterProgressTracker(
            bookId = session.id,
            initialChapter = initialChapter,
            initialOffset = savedOffset,
            restoreFrom = { container.progress.get(it) },
            persist = { id, idx, offset, page, total, ratio ->
                container.repository.saveProgress(id, idx, offset, page, total, ratio)
            },
        )
    }
    // 恢复锚点单点策略（见 RestoreAnchor.kt）：crossJump 跨章跳转按文本锚点
    // 定位（page/total/ratio 全部让位）；initialChapter 按存储恢复；会话内
    // 普通换章从章头开始。此前 4 个平行 remember 各自维护该策略，restoreRatio
    // 漏接 crossJump 导致跳转被旧滚动比例覆盖（详见 RestoreAnchorTest）。
    val restoreAnchor = remember(chapterIndex, session.id, crossJump) {
        restoreAnchorFor(
            chapterIndex = chapterIndex,
            initialChapter = initialChapter,
            crossJump = crossJump,
            savedAnchor = RestoreAnchor(
                offset = progressTracker.restoreOffsetFor(chapterIndex),
                page = progressTracker.restorePageFor(chapterIndex),
                total = progressTracker.restoreTotalFor(chapterIndex),
                ratio = progressTracker.restoreRatioFor(chapterIndex),
            ),
        )
    }
    val activity = androidx.activity.compose.LocalActivity.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val theme = remember(readerSettings, systemDark) { readerTheme(readerSettings, systemDark) }
    val ngaConfig = remember { container.ngaConfig.load() }
    val fg = remember(theme) {
        runCatching { Color(android.graphics.Color.parseColor(theme.text)) }.getOrDefault(Color.Black)
    }
    // 悬浮栏配色跟随阅读器主题（背景=正文背景、文字=正文前景、强调=主题色），
    // 不再依赖 MaterialTheme：应用主题与阅读器深色主题分离时不会出现黑字。
    val barBg = remember(theme) {
        runCatching { Color(android.graphics.Color.parseColor(theme.background)) }.getOrDefault(Color.White)
    }

    // 章节 HTML：后台线程组装，避免大章节 Jsoup 清洗/纯文本提取阻塞主线程
    // 造成“从书架进入阅读器卡一下”。读取失败走显式错误分支。
    val chapterState by produceState<ChapterUiState>(
        initialValue = ChapterUiState.Loading,
        session.id,
        chapterIndex,
        readerSettings.book_fonts,
        readerSettings.custom_font,
    ) {
        value = withContext(Dispatchers.Default) {
            when (val r = session.chapterText(chapterIndex)) {
                is ChapterReadResult.Success -> runCatching {
                    val parts = extractReaderParts(r.text)
                    // 自建 HTML 壳不会自动加载章节 <link rel="stylesheet">；
                    // 这里按链接把 EPUB 自带 CSS 读出来，内联进页面（骨碌碌楼层卡片样式）。
                    val linkedCss = parts.styleHrefs.joinToString(" ") { href ->
                        session.readAsset(chapterIndex, href)?.decodeToString().orEmpty()
                    }
                    val partsWithCss = parts.copy(
                        headStyles = listOf(parts.headStyles, linkedCss)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                    )
                    val plain = TextExtractor.extractDomText(parts.body)
                    val html = buildReaderHtml(partsWithCss, theme, readerSettings, session.id)
                    ChapterUiState.Html(html, plain)
                }.getOrElse { e -> ChapterUiState.Error(e.message ?: "章节渲染失败") }
                is ChapterReadResult.NotFound -> ChapterUiState.Error("章节不存在，请返回目录")
                is ChapterReadResult.Corrupt -> ChapterUiState.Error("章节文件损坏：${r.detail}")
                is ChapterReadResult.Io -> ChapterUiState.Error("章节读取失败：${r.detail}")
            }
        }
    }

    // 标注数据：annotationsTick 变化即重读（CRUD 后刷新正文注入与抽屉列表）。
    val allHighlights = remember(session.id, annotationsTick) {
        container.annotations.getHighlights(session.id).sortedWith(
            compareBy({ it.chapter_index }, { it.start_offset }),
        )
    }
    val allBookmarks = remember(session.id, annotationsTick) {
        container.annotations.getBookmarks(session.id).sortedWith(
            compareBy({ it.chapter_index }, { it.offset }),
        )
    }
    val chapterHighlights = remember(allHighlights, chapterIndex) {
        allHighlights.filter { it.chapter_index == chapterIndex }
    }
    // 传给 WebView 的注入载荷：只包含定位与配色所需字段（不带笔记正文）。
    val highlightsJson = remember(chapterHighlights) {
        buildString {
            append('[')
            chapterHighlights.forEachIndexed { i, h ->
                if (i > 0) append(',')
                append("{\"id\":").append(JSONObject.quote(h.id))
                append(",\"start\":").append(h.start_offset)
                append(",\"end\":").append(h.end_offset)
                append(",\"color\":").append(JSONObject.quote(h.color))
                append('}')
            }
            append(']')
        }
    }
    val bookmarkAtCurrent = remember(allBookmarks, chapterIndex, liveOffset) {
        allBookmarks.firstOrNull {
            it.chapter_index == chapterIndex && kotlin.math.abs(it.offset - liveOffset) <= 80
        }
    }
    val tocNodes = remember(session.id) {
        session.tocNodes().ifEmpty {
            TocTree.fromChapters(session.chapters) { session.chapterTitle(it) }
        }
    }
    // 可选字体：内置 + 系统 + 已导入字体文件（与设置页 ReadingPanel 同来源）。
    val availableFonts = remember(session.id) {
        buildList {
            add("sys:weidqczfkyxk.ttf")
            add("system")
            container.appPaths.fontsDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
                ?.sortedBy { it.name }
                ?.forEach { add(it.name) }
        }
    }
    // 与书架百分比同口径：分页用 page_index/page_total，滚动优先 scroll_ratio，
    // 文本页回退 text_offset / 本章纯文本长度。
    val chapterProgress = remember(pageInfo, scrollRatio, liveOffset, chapterState, readerSettings.pagination) {
        if (readerSettings.pagination) {
            if (pageInfo.second > 0) {
                (pageInfo.first.toFloat() / pageInfo.second).coerceIn(0f, 1f)
            } else {
                0f
            }
        } else {
            if (scrollRatio in 0f..1f) {
                scrollRatio
            } else {
                val len = (chapterState as? ChapterUiState.Html)?.len ?: 0
                if (len > 0) (liveOffset.toFloat() / len).coerceIn(0f, 1f) else 0f
            }
        }
    }
    val bookProgress = remember(chapterIndex, chapterProgress, session.id) {
        val total = session.chapters.size
        if (total <= 0) 0f
        else ((chapterIndex + chapterProgress) / total).coerceIn(0f, 1f)
    }

    // 异形屏安全区：沉浸式时顶部保留挖孔约 3/8（手动 dp 可覆盖）。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val manualTopInsetDp = remember {
        context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
            .getInt("top_inset_dp", -1)
    }
    val cutoutBottom = remember(activity) {
        val v = activity?.window?.decorView ?: return@remember 0
        ViewCompat.getRootWindowInsets(v)
            ?.displayCutout
            ?.boundingRects
            ?.maxOfOrNull { it.bottom } ?: 0
    }
    // 安全区自动模式只负责内容区 top inset（原口径，不再下移正文）。
    val topInsetPx = if (manualTopInsetDp >= 0) {
        with(density) { manualTopInsetDp.dp.toPx().roundToInt() }
    } else {
        cutoutBottom * 3 / 8
    }
    // 悬浮操作栏下移量：自动模式固定 50dp（避开异形屏），手动模式由用户自定。
    val topBarExtraDp = if (manualTopInsetDp >= 0) 0 else 30

    // 图片字节：EPUB 走压缩包相对路径；NGA 在线图走 OkHttp（防盗链头）。
    suspend fun imageBytes(src: String): ByteArray? = withContext(Dispatchers.IO) {
        when {
            src.startsWith("file:///android_epub/") -> {
                val rel = src.removePrefix("file:///android_epub/").substringAfter('/')
                session.readAsset(chapterIndex, rel)
            }
            src.startsWith("http") -> runCatching {
                val req = Request.Builder().url(src).ngaHeaders(ngaConfig).build()
                container.okHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body.bytes() else null
                }
            }.getOrNull()
            else -> null
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        val src = pendingSaveUrl ?: return@rememberLauncherForActivityResult
        pendingSaveUrl = null
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = imageBytes(src) ?: return@runCatching false
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                            true
                        } ?: false
                    }.getOrDefault(false)
                }
                android.widget.Toast.makeText(
                    context,
                    if (ok) "图片已保存到所选位置" else "图片保存失败",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun flushProgress(source: String) {
        try {
            progressTracker.flush()
        } catch (e: Exception) {
            LogEvents.event("progress", "tracker_flush_failed", "source" to source, "error" to e)
        }
        container.progress.flush().exceptionOrNull()?.let { error ->
            LogEvents.event("progress", "flush_failed", "source" to source, "error" to error)
        }
    }

    fun saveProgress() {
        flushProgress("reader_action")
    }

    // 换章/换书：当前位置回到本章恢复锚点（书签命中判定与进度滑块用）。
    LaunchedEffect(session.id, chapterIndex, restoreAnchor.offset) {
        liveOffset = restoreAnchor.offset
    }

    // 5 秒心跳统计。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000)
            pendingSeconds += 5
            if (pendingSeconds >= 60) {
                onStatsTick(pendingSeconds, flippedPages)
                pendingSeconds = 0
                flippedPages = 0
            }
        }
    }

    // 控制条：非手动保持时 3 秒自动收起（对齐 WebView 时代最终行为）。
    LaunchedEffect(barsVisible, barsHeld, showToc) {
        if (barsVisible && !barsHeld && !showToc) {
            delay(3000)
            barsVisible = false
        }
    }

    // 悬浮栏收起来后，骨碌碌快捷菜单与音乐播放菜单也跟着关掉，
    // 避免下次唤出时还残留。
    LaunchedEffect(barsVisible) {
        if (!barsVisible) {
            showGululuQuickMenu = false
            showMusicQuickMenu = false
        }
    }

    // 沉浸式：进入阅读器隐藏系统栏，退出恢复。
    LaunchedEffect(activity) {
        val act = activity ?: return@LaunchedEffect
        WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        // 与覆盖层叠加顺序保持一致：优先关闭最上层，都不存在时才退出阅读器。
        when {
            lightboxSrc != null -> lightboxSrc = null
            secretDialog != null -> secretDialog = null
            showOverview -> showOverview = false
            showGululuComments -> {
                showGululuComments = false
                paragraphFilter = ""
            }
            showMusicQuickMenu -> showMusicQuickMenu = false
            showGululuQuickMenu -> showGululuQuickMenu = false
            editingNote != null -> editingNote = null
            editingHighlight != null -> editingHighlight = null
            pendingNote != null -> pendingNote = null
            selection != null -> {
                selection = null
                clearSelectionToken++
            }
            showAnnotations -> showAnnotations = false
            showToc -> showToc = false
            rsvpOn -> rsvpOn = false
            showSettings -> showSettings = false
            else -> {
                saveProgress()
                onBack()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(themeColor(theme.background, Color.White))) {
        when (val state = chapterState) {
            is ChapterUiState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = fg.copy(alpha = 0.8f))
            }
            is ChapterUiState.Error -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "章节读取失败\n${state.message}",
                    color = fg.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
            is ChapterUiState.Html -> WebViewChapterView(
                html = state.html,
                chapterIndex = chapterIndex,
                paged = readerSettings.pagination,
                theme = theme,
                fontSize = readerSettings.font_size,
                lineHeight = readerSettings.line_height,
                pageWidth = readerSettings.page_width,
                marginPx = readerSettings.margin_px,
                gapPx = readerSettings.gap_px,
                dualPage = readerSettings.dual_page,
                autoDual = readerSettings.auto_dual != false,
                topInsetPx = topInsetPx,
                initialOffset = restoreAnchor.offset,
                initialPage = restoreAnchor.page,
                initialTotal = restoreAnchor.total,
                // 滚动比例只属于滚动模式：分页模式打开时强制 -1（跨模式隔离，
                // 全图页的滚动比例不能参与分页文本定位，避免“上次全图退出→本次分页打开”错位）。
                initialRatio = if (!readerSettings.pagination) restoreAnchor.ratio else -1.0,
                highlightsJson = highlightsJson,
                jump = jump,
                clearSelectionToken = clearSelectionToken,
                autoScroll = autoScrollOn,
                autoScrollSpeed = readerSettings.autoscroll_speed,
                gululu = isGululu,
                gululuAutoMusic = readerSettings.gululu_immersive.autoMusic,
                gululuUnlockedJson = unlockedJson,
                paragraphCommentsJson = paragraphCommentsJson,
                gululuCommand = gululuCommand,
                gululuCommandToken = gululuCommandToken,
                session = session,
                container = container,
                callbacks = WebViewReaderCallbacks(
                    onProgress = { ch, offset, _, _, ratio ->
                        progressTracker.onOffset(ch, offset, ratio)
                        if (!readerSettings.pagination) {
                            lastScrollAt = android.os.SystemClock.uptimeMillis()
                        }
                        if (ch == chapterIndex) {
                            liveOffset = offset
                            // UI 百分比：全图页直接用 JS 滚动比例，文本页用 text_offset 比例。
                            scrollRatio = if (ratio in 0.0..1.0) {
                                ratio.toFloat()
                            } else if (state.len > 0) {
                                offset.toFloat() / state.len
                            } else {
                                0f
                            }
                        }
                    },
                    onPagedAnchor = { ch, offset ->
                        progressTracker.onPagedAnchor(ch, offset)
                        if (ch == chapterIndex) liveOffset = offset
                    },
                    onProgressNow = { ch, offset, page, total, _ ->
                        // 翻页/换章立即落盘（不等待 500ms 防抖），退出时进度不落后。
                        progressTracker.onPageTurn(ch, offset, page, total)
                        if (ch == chapterIndex) liveOffset = offset
                    },
                    // 唤出浮动栏后发生新的滚动才自动收起（滚动事件发生时即时通知，
                    // 不等防抖保存回调，避免唤出前的迟到滚动把刚唤出的控制条误收）。
                    onScrollMoved = {
                        lastScrollAt = android.os.SystemClock.uptimeMillis()
                        // 滚动模式：悬浮栏可见且距“唤出时刻”超过 350ms 才自动收起，
                        // 避免“刚滚完 → 快速唤出 → 迟到的滚动事件又把栏收走”。
                        if (!readerSettings.pagination && barsVisible &&
                            android.os.SystemClock.uptimeMillis() - barsShownAt > 350
                        ) {
                            barsHeld = false
                            barsVisible = false
                        }
                    },
                    onPageChanged = { ch, page, total ->
                        // 纯 UI 事件：页码指示；进度落盘只走 onProgress（JS 端仅在
                        // 用户翻页时上报），恢复/重排的中间页不会污染已保存进度。
                        if (ch == chapterIndex) pageInfo = page to total
                        // 分页模式翻页后同样收起悬浮栏（对齐桌面翻页隐藏）。
                        if (readerSettings.pagination && barsVisible) {
                            barsHeld = false
                            barsVisible = false
                        }
                    },
                    onChapterSwitch = { from, _ -> progressTracker.onChapterSwitch(from) },
                    onFlush = { flushProgress("webview") },
                    onImageTap = { lightboxSrc = it },
                    onTapZone = { zone ->
                        when (zone) {
                            "middle" -> {
                                // 快速滚动后轻点停下的那一下不应唤出悬浮栏。
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastScrollAt > 200) {
                                    barsVisible = !barsVisible
                                    barsHeld = barsVisible
                                    if (barsVisible) barsShownAt = now
                                }
                            }
                            "hide" -> {
                                barsVisible = false
                                barsHeld = false
                            }
                        }
                    },
                    onRequestChapter = { delta ->
                        chapterIndex = (chapterIndex + delta)
                            .coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0))
                    },
                    onSelection = { selection = it },
                    onHighlightTap = { id ->
                        editingHighlight = allHighlights.firstOrNull { it.id == id }
                    },
                    onReady = {
                        // 跨章跳转已由本章 init 定位完成：清除一次性锚点，
                        // 之后再回到本章仍按"换章从章首开始"的语义。
                        if (crossJump?.first == chapterIndex) crossJump = null
                    },
                    onGululuUnlock = { ids ->
                        if (container.gululuUnlocks.unlockAll(session.id, ids) > 0) unlockTick++
                    },
                    onGululuFloor = { floorId -> gululuFloor = floorId },
                    onGululuVfx = { kind -> gululuVfx = kind },
                    onGululuBackground = { url -> gululuBackground = url },
                    onGululuMusic = { url, title, auto ->
                        // 自动音乐只在首次到达时触发（JS 侧已去重），手动点击同曲即停止
                        if (auto) {
                            if (url != musicUrl) playMusic(url, title)
                        } else {
                            playMusic(url, title)
                        }
                    },
                    onGululuMusicStop = { stopMusic() },
                    onGululuSecret = { title, cipher ->
                        val reveal = container.gululuUnlocks.revealSecret(session.id, cipher)
                        secretDialog = title to (reveal as? GululuSecretReveal.Ok)?.plaintext
                    },
                    onGululuClue = { title, password ->
                        if (container.gululuUnlocks.collectClue(session.id, title, password)) {
                            android.widget.Toast.makeText(
                                context,
                                "已收集线索：$title",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onGululuParagraphComments = { paragraphId ->
                        paragraphFilter = paragraphId
                        showGululuComments = true
                    },
                    onGululuStats = { stats -> chapterStats = stats },
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 亮度遮罩（阅读器外层）
        ReaderBrightnessOverlay(brightness = readerSettings.brightness)

        // 悬浮操作栏隐藏后，底部细条展示本章进度（分页=页码比例，滚动=滚动比例）
        ReaderChapterProgressBar(
            visible = !barsVisible,
            progress = chapterProgress,
            fg = fg,
        )

        if (isGululu) {
            GululuBackgroundOverlay(url = gululuBackground)
            GululuVfxOverlay(kind = gululuVfx)
            GululuDanmakuOverlay(enabled = danmakuOn, comments = gululuComments)
            GululuFloatingQuickButton(
                visible = barsVisible,
                quickMenuOpen = showGululuQuickMenu,
                barBg = barBg,
                fg = fg,
                danmakuOn = danmakuOn,
                musicPlaying = musicTitle.isNotEmpty(),
                onToggleQuickMenu = { showGululuQuickMenu = !showGululuQuickMenu },
                onOpenOverview = {
                    showOverview = true
                    showGululuQuickMenu = false
                },
                onOpenComments = {
                    paragraphFilter = ""
                    showGululuComments = true
                    showGululuQuickMenu = false
                },
                onToggleDanmaku = {
                    danmakuOn = !danmakuOn
                    showGululuQuickMenu = false
                },
                onStopMusic = {
                    stopMusic()
                    showGululuQuickMenu = false
                },
            )
        }

        if (!isGululu) {
            GululuMusicQuickButton(
                visible = barsVisible && musicTitle.isNotEmpty(),
                quickMenuOpen = showMusicQuickMenu,
                barBg = barBg,
                fg = fg,
                playingTitle = musicTitle,
                autoPlay = readerSettings.gululu_immersive.autoMusic,
                onToggleQuickMenu = { showMusicQuickMenu = !showMusicQuickMenu },
                onToggleAutoPlay = {
                    val cur = readerSettings.gululu_immersive
                    onSettingsPatch(
                        SettingsPatch(gululu_immersive = cur.copy(autoMusic = !cur.autoMusic)),
                    )
                },
                onStopMusic = {
                    stopMusic()
                    showMusicQuickMenu = false
                },
            )
        }

        ReaderTopBar(
            visible = barsVisible,
            title = session.chapterTitle(chapterIndex),
            barBg = barBg,
            fg = fg,
            bookmarked = bookmarkAtCurrent != null,
            extraTopDp = topBarExtraDp,
            onBack = { saveProgress(); onBack() },
            onToggleBookmark = {
                val existing = bookmarkAtCurrent
                if (existing != null) {
                    container.annotations.deleteBookmark(session.id, existing.id)
                } else {
                    val plain = (chapterState as? ChapterUiState.Html)?.plain.orEmpty()
                    val label = if (plain.isNotEmpty()) {
                        val from = liveOffset.coerceIn(0, plain.length)
                        plain.substring(from, (from + 60).coerceAtMost(plain.length)).trim()
                    } else {
                        ""
                    }
                    container.annotations.addBookmark(
                        bookId = session.id,
                        chapterIndex = chapterIndex,
                        offset = liveOffset,
                        text = label.ifBlank { session.chapterTitle(chapterIndex) },
                    )
                }
                annotationsTick++
            },
            onOpenAnnotations = { showAnnotations = true },
            onToggleToc = { showToc = !showToc },
            gululu = isGululu,
            onOpenGululu = { showOverview = true },
        )

        ReaderBottomBar(
            visible = barsVisible,
            barBg = barBg,
            fg = fg,
            bookProgress = bookProgress,
            onSeek = { fraction ->
                val total = session.chapters.size
                if (total > 0) {
                    val target = (fraction * total).toInt().coerceIn(0, total - 1)
                    val len = (chapterState as? ChapterUiState.Html)?.len ?: 0
                    if (target == chapterIndex && len > 0) {
                        val within = (fraction * total) - target
                        jumpToken++
                        jump = ReaderJump(jumpToken, (within * len).toInt().coerceIn(0, len))
                    } else if (target != chapterIndex) {
                        saveProgress()
                        chapterIndex = target
                    }
                }
            },
            onPrevChapter = { chapterIndex = (chapterIndex - 1).coerceAtLeast(0) },
            onNextChapter = { chapterIndex = (chapterIndex + 1).coerceAtMost(session.chapters.lastIndex) },
            onOpenSettings = { showSettings = true },
        )

        ReaderSettingsSheet(
            visible = showSettings,
            barBg = barBg,
            fg = fg,
            fontSize = readerSettings.font_size,
            lineHeight = readerSettings.line_height,
            pagination = readerSettings.pagination,
            theme = readerSettings.theme,
            autoScroll = autoScrollOn,
            autoScrollSpeed = readerSettings.autoscroll_speed,
            rulerOn = rulerOn,
            rsvpOn = rsvpOn,
            fonts = availableFonts,
            bookFont = readerSettings.book_fonts[session.id].orEmpty(),
            onFontSizeDec = { onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14))) },
            onFontSizeInc = { onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28))) },
            onLineHeightDec = { onSettingsPatch(SettingsPatch(line_height = ((readerSettings.line_height - 0.1) * 10).roundToInt() / 10.0)) },
            onLineHeightInc = { onSettingsPatch(SettingsPatch(line_height = ((readerSettings.line_height + 0.1) * 10).roundToInt() / 10.0)) },
            onTogglePagination = { onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination)) },
            onThemeCycle = {
                val next = when (readerSettings.theme) {
                    "dark" -> "light"
                    "light" -> "sepia"
                    "sepia" -> "dark"
                    else -> "dark"
                }
                onSettingsPatch(SettingsPatch(theme = next))
            },
            onToggleAutoScroll = { autoScrollOn = it },
            onSpeedChange = {
                onSettingsPatch(SettingsPatch(autoscroll_speed = (it * 10).roundToInt() / 10.0))
            },
            onToggleRuler = {
                rulerOn = it
                onSettingsPatch(SettingsPatch(show_ruler = it))
            },
            onToggleRsvp = { rsvpOn = it },
            onBookFontChange = { font ->
                val next = readerSettings.book_fonts.toMutableMap()
                if (font.isBlank()) next.remove(session.id) else next[session.id] = font
                onSettingsPatch(SettingsPatch(book_fonts = next))
            },
            onDismiss = { showSettings = false },
        )

        ReaderRulerOverlay(visible = rulerOn, fg = fg)

        if (rsvpOn) {
            ReaderRsvpOverlay(
                plainText = (chapterState as? ChapterUiState.Html)?.plain.orEmpty(),
                fromOffset = liveOffset,
                rate = readerSettings.rsvp_rate,
                barBg = barBg,
                fg = fg,
                onRateChange = { onSettingsPatch(SettingsPatch(rsvp_rate = it)) },
                onClose = { rsvpOn = false },
            )
        }

        ReaderTocDrawer(
            visible = showToc,
            nodes = tocNodes,
            currentChapter = chapterIndex,
            onDismiss = { showToc = false },
            onSelect = { i -> saveProgress(); chapterIndex = i; showToc = false },
        )

        ReaderAnnotationsDrawer(
            visible = showAnnotations,
            highlights = allHighlights,
            bookmarks = allBookmarks,
            chapterTitleFn = { session.chapterTitle(it) },
            onJump = { ch, offset ->
                showAnnotations = false
                saveProgress()
                if (ch == chapterIndex) {
                    jumpToken++
                    jump = ReaderJump(jumpToken, offset)
                } else {
                    // 跨章跳转：换章后由 init 的 offset 完成定位（与搜索跳转同路径）。
                    crossJump = ch to offset
                    chapterIndex = ch.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0))
                }
            },
            onDeleteHighlight = { id ->
                container.annotations.deleteAnnotation(session.id, id)
                annotationsTick++
            },
            onDeleteBookmark = { id ->
                container.annotations.deleteBookmark(session.id, id)
                annotationsTick++
            },
            onDismiss = { showAnnotations = false },
        )

        selection?.let { sel ->
            ReaderSelectionBar(
                selection = sel,
                barBg = barBg,
                fg = fg,
                onColor = { color ->
                    container.annotations.addHighlight(
                        bookId = session.id,
                        chapterIndex = chapterIndex,
                        startOffset = sel.start,
                        endOffset = sel.end,
                        text = sel.text,
                        color = color,
                    )
                    annotationsTick++
                    selection = null
                    clearSelectionToken++
                },
                onNote = {
                    pendingNote = sel
                    selection = null
                    clearSelectionToken++
                },
                onDismiss = {
                    selection = null
                    clearSelectionToken++
                },
            )
        }

        pendingNote?.let { sel ->
            ReaderNoteDialog(
                initialNote = "",
                quote = sel.text,
                onConfirm = { note ->
                    container.annotations.addHighlight(
                        bookId = session.id,
                        chapterIndex = chapterIndex,
                        startOffset = sel.start,
                        endOffset = sel.end,
                        text = sel.text,
                        note = note,
                    )
                    annotationsTick++
                    pendingNote = null
                },
                onDismiss = { pendingNote = null },
            )
        }

        editingHighlight?.let { h ->
            ReaderHighlightDialog(
                highlight = h,
                onColor = { color ->
                    container.annotations.updateAnnotation(
                        session.id,
                        h.id,
                        AnnotationPatch(color = color),
                    )
                    annotationsTick++
                    editingHighlight = null
                },
                onEditNote = {
                    editingNote = h
                    editingHighlight = null
                },
                onDelete = {
                    container.annotations.deleteAnnotation(session.id, h.id)
                    annotationsTick++
                    editingHighlight = null
                },
                onDismiss = { editingHighlight = null },
            )
        }

        editingNote?.let { h ->
            ReaderNoteDialog(
                initialNote = h.note,
                quote = h.text,
                onConfirm = { note ->
                    container.annotations.updateAnnotation(
                        session.id,
                        h.id,
                        AnnotationPatch(note = note),
                    )
                    annotationsTick++
                    editingNote = null
                },
                onDismiss = { editingNote = null },
            )
        }

        if (isGululu) {
            GululuCommentDrawer(
                visible = showGululuComments,
                floorId = gululuFloor,
                paragraphFilter = paragraphFilter,
                comments = gululuComments,
                stale = gululuCommentStale,
                error = gululuCommentError,
                onClearParagraphFilter = { paragraphFilter = "" },
                onDismiss = { showGululuComments = false; paragraphFilter = "" },
            )
            GululuOverviewSheet(
                visible = showOverview,
                barBg = barBg,
                fg = fg,
                groups = chapterStats[0],
                lockedGroups = chapterStats[1],
                secrets = chapterStats[2],
                clues = chapterStats[3],
                floors = chapterStats[4],
                danmaku = danmakuOn,
                playingTitle = musicTitle,
                onRevealNext = {
                    gululuCommand = "AnkeReader.revealNextGululuGroups(10);"
                    gululuCommandToken++
                },
                onResetUnlocks = {
                    container.gululuUnlocks.reset(session.id)
                    unlockTick++
                    gululuCommand = "AnkeReader.gululuResetUnlocks();"
                    gululuCommandToken++
                    showOverview = false
                },
                onToggleDanmaku = { danmakuOn = !danmakuOn },
                onStopMusic = { stopMusic() },
                onOpenComments = {
                    paragraphFilter = ""
                    showGululuComments = true
                    showOverview = false
                },
                onDismiss = { showOverview = false },
            )
            secretDialog?.let { (title, plaintext) ->
                GululuSecretDialog(
                    title = title,
                    plaintext = plaintext,
                    onDismiss = { secretDialog = null },
                )
            }
        }

        lightboxSrc?.let { src ->
            ReaderLightbox(
                src = src,
                onClose = { lightboxSrc = null },
                onSave = {
                    pendingSaveUrl = src
                    saveLauncher.launch("AnkeShelf-image.jpg")
                },
                imageBytes = ::imageBytes,
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 切到其他应用再回来，系统状态栏可能被系统恢复；
                    // 阅读器要求沉浸式，需再次隐藏。
                    val act = activity
                    if (act != null) {
                        runCatching {
                            WindowCompat.getInsetsController(act.window, act.window.decorView)
                                .hide(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 按 Home 退后台/切走：立即落盘，避免进程被杀丢进度（对齐 Legado onPause save）。
                    flushProgress("reader_stop")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushProgress("reader_dispose")
            // 延迟关闭追踪器：WebView 销毁（+200ms）期间若还有迟到的桥事件，
            // 不应覆盖刚 flush 的正确进度。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { progressTracker.close() },
                400,
            )
            val act = activity
            if (act != null) {
                runCatching {
                    WindowCompat.getInsetsController(act.window, act.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
}
