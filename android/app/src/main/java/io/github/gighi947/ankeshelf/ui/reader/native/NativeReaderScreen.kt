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
import io.github.gighi947.ankeshelf.ui.reader.WebViewChapterView
import io.github.gighi947.ankeshelf.ui.reader.WebViewReaderCallbacks
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
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
    data class Html(val html: String, val len: Int) : ChapterUiState
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
    // 手动唤出的控制条保持显示（不再 3 秒自动收），滚动/翻页后才收起。
    var barsHeld by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf(Pair(0, 1)) }
    var scrollRatio by remember { mutableFloatStateOf(0f) }
    var lightboxSrc by remember { mutableStateOf<String?>(null) }
    var pendingSeconds by remember { mutableIntStateOf(0) }
    var flippedPages by remember { mutableIntStateOf(0) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
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
    val restoreOffset = remember(chapterIndex, session.id) {
        // desktop loadChapter(i, 0): only the initial open/search jump restores an
        // offset; in-session chapter navigation always starts at the chapter head.
        if (chapterIndex == initialChapter) progressTracker.restoreOffsetFor(chapterIndex) else 0
    }
    val restorePage = remember(chapterIndex, session.id) {
        if (chapterIndex == initialChapter) progressTracker.restorePageFor(chapterIndex) else -1
    }
    val restoreTotal = remember(chapterIndex, session.id) {
        if (chapterIndex == initialChapter) progressTracker.restoreTotalFor(chapterIndex) else -1
    }
    val restoreRatio = remember(chapterIndex, session.id) {
        if (chapterIndex == initialChapter) progressTracker.restoreRatioFor(chapterIndex) else -1.0
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

    // 章节 HTML：换章/换书时后台组装一次；主题/字号后续用 JS 桥更新，不重载页面。
    // 读取失败进入显式分支（NotFound/Corrupt/Io），不静默渲染空白。
    val chapterState = remember(session.id, chapterIndex) {
        when (val r = session.chapterText(chapterIndex)) {
            is ChapterReadResult.Success -> runCatching {
                val parts = extractReaderParts(r.text)
                val len = TextExtractor.extractDomText(parts.body).length
                val html = buildReaderHtml(parts, theme, readerSettings)
                ChapterUiState.Html(html, len)
            }.getOrElse { e -> ChapterUiState.Error(e.message ?: "章节渲染失败") }
            is ChapterReadResult.NotFound -> ChapterUiState.Error("章节不存在，请返回目录")
            is ChapterReadResult.Corrupt -> ChapterUiState.Error("章节文件损坏：${r.detail}")
            is ChapterReadResult.Io -> ChapterUiState.Error("章节读取失败：${r.detail}")
        }
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
    val topInsetPx = if (manualTopInsetDp >= 0) {
        with(density) { manualTopInsetDp.dp.toPx().roundToInt() }
    } else {
        cutoutBottom * 3 / 8
    }

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

    // 沉浸式：进入阅读器隐藏系统栏，退出恢复。
    LaunchedEffect(activity) {
        val act = activity ?: return@LaunchedEffect
        WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        when {
            lightboxSrc != null -> lightboxSrc = null
            showToc -> showToc = false
            else -> {
                saveProgress()
                onBack()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(themeColor(theme.background, Color.White))) {
        when (val state = chapterState) {
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
                initialOffset = restoreOffset,
                initialPage = restorePage,
                initialTotal = restoreTotal,
                // 滚动比例只属于滚动模式：分页模式打开时强制 -1（跨模式隔离，
                // 全图页的滚动比例不能参与分页文本定位，避免“上次全图退出→本次分页打开”错位）。
                initialRatio = if (!readerSettings.pagination) restoreRatio else -1.0,
                session = session,
                container = container,
                callbacks = WebViewReaderCallbacks(
                    onProgress = { ch, offset, _, _, ratio ->
                        progressTracker.onOffset(ch, offset, ratio)
                        if (ch == chapterIndex) {
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
                    },
                    onProgressNow = { ch, offset, page, total, _ ->
                        // 翻页/换章立即落盘（不等待 500ms 防抖），退出时进度不落后。
                        progressTracker.onPageTurn(ch, offset, page, total)
                    },
                    // 唤出浮动栏后发生新的滚动才自动收起（滚动事件发生时即时通知，
                    // 不等防抖保存回调，避免唤出前的迟到滚动把刚唤出的控制条误收）。
                    onScrollMoved = {
                        if (!readerSettings.pagination && barsHeld) {
                            barsHeld = false
                            barsVisible = false
                        }
                    },
                    onPageChanged = { ch, page, total ->
                        // 纯 UI 事件：页码指示；进度落盘只走 onProgress（JS 端仅在
                        // 用户翻页时上报），恢复/重排的中间页不会污染已保存进度。
                        if (ch == chapterIndex) pageInfo = page to total
                    },
                    onChapterSwitch = { from, _ -> progressTracker.onChapterSwitch(from) },
                    onFlush = { flushProgress("webview") },
                    onImageTap = { lightboxSrc = it },
                    onTapZone = { zone ->
                        when (zone) {
                            "middle" -> {
                                barsVisible = !barsVisible
                                barsHeld = barsVisible
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
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ?????
        ReaderBrightnessOverlay(brightness = readerSettings.brightness)

        ReaderTopBar(
            visible = barsVisible,
            title = session.chapterTitle(chapterIndex),
            barBg = barBg,
            fg = fg,
            onBack = { saveProgress(); onBack() },
            onToggleToc = { showToc = !showToc },
        )

        ReaderBottomBar(
            visible = barsVisible,
            barBg = barBg,
            fg = fg,
            theme = readerSettings.theme,
            pagination = readerSettings.pagination,
            pageInfo = pageInfo,
            scrollRatio = scrollRatio,
            onPrevChapter = { chapterIndex = (chapterIndex - 1).coerceAtLeast(0) },
            onNextChapter = { chapterIndex = (chapterIndex + 1).coerceAtMost(session.chapters.lastIndex) },
            onFontDec = { onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14))) },
            onFontInc = { onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28))) },
            onThemeChange = { onSettingsPatch(SettingsPatch(theme = it)) },
            onTogglePagination = { onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination)) },
        )

        ReaderTocDrawer(
            visible = showToc,
            chapters = session.chapters,
            currentChapter = chapterIndex,
            titleFn = { session.chapterTitle(it) },
            onDismiss = { showToc = false },
            onSelect = { i -> saveProgress(); chapterIndex = i; showToc = false },
        )

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
            if (event == Lifecycle.Event.ON_STOP) {
                // 按 Home 退后台/切走：立即落盘，避免进程被杀丢进度（对齐 Legado onPause save）。
                flushProgress("reader_stop")
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
