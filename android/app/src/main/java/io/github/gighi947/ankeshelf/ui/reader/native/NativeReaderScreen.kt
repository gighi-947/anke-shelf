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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
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

private val THEME_CYCLE = listOf("dark", "light", "sepia")

private fun themeColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/**
 * 原生（Compose）阅读页：替代 WebView 渲染。NGA 楼层/引用/骰子/表格/颜色、
 * 主题、安全区、进度（text_offset）、目录、图片查看/保存均为原生实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeReaderScreen(
    session: BookSession,
    initialChapter: Int,
    savedOffset: Int,
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
            persist = { id, idx, offset -> container.repository.saveProgress(id, idx, offset) },
        )
    }
    val restoreOffset = remember(chapterIndex, session.id) {
        // desktop loadChapter(i, 0): only the initial open/search jump restores an
        // offset; in-session chapter navigation always starts at the chapter head.
        if (chapterIndex == initialChapter) progressTracker.restoreOffsetFor(chapterIndex) else 0
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
    val htmlState = remember(session.id, chapterIndex) {
        runCatching {
            val parts = extractReaderParts(session.chapterText(chapterIndex).orEmpty())
            val len = TextExtractor.extractDomText(parts.body).length
            val html = buildReaderHtml(parts, theme, readerSettings)
            html to len
        }.getOrNull()
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

    fun saveProgress() {
        progressTracker.flush()
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
        val html = htmlState
        if (html == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载章节…", color = fg.copy(alpha = 0.7f))
            }
        } else {
            WebViewChapterView(
                html = html.first,
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
                session = session,
                container = container,
                callbacks = WebViewReaderCallbacks(
                    onProgress = { ch, offset ->
                        progressTracker.onOffset(ch, offset)
                        if (ch == chapterIndex) {
                            // 滚动一段距离后收起手动唤出的控制条（对齐 WebView 时代行为）。
                            if (!readerSettings.pagination && barsHeld) {
                                barsHeld = false
                                barsVisible = false
                            }
                            scrollRatio = if (html.second > 0) {
                                offset.toFloat() / html.second
                            } else {
                                0f
                            }
                        }
                    },
                    onProgressNow = { ch, offset ->
                        // 翻页/换章立即落盘（不等待 500ms 防抖），退出时进度不落后。
                        progressTracker.onPageTurn(ch, offset)
                    },
                    onPageChanged = { ch, page, total ->
                        // 纯 UI 事件：页码指示；进度落盘只走 onProgress（JS 端仅在
                        // 用户翻页时上报），恢复/重排的中间页不会污染已保存进度。
                        if (ch == chapterIndex) pageInfo = page to total
                    },
                    onChapterSwitch = { from, to -> progressTracker.onChapterSwitch(from, to) },
                    onFlush = { progressTracker.flush() },
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

        // 亮度遮罩。
        if (readerSettings.brightness > 0.0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = readerSettings.brightness.toFloat().coerceIn(0f, 0.7f))),
            )
        }

        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBg.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { saveProgress(); onBack() }) { Text("← 返回", color = fg) }
                Text(
                    session.chapterTitle(chapterIndex),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { showToc = !showToc }) { Text("目录", color = fg) }
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBg.copy(alpha = 0.96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { chapterIndex = (chapterIndex - 1).coerceAtLeast(0) }) {
                        Text("上一章", color = fg)
                    }
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14)))
                    }) { Text("A-", color = fg) }
                    TextButton(onClick = {
                        val next = THEME_CYCLE[(THEME_CYCLE.indexOf(readerSettings.theme) + 1) % THEME_CYCLE.size]
                        onSettingsPatch(SettingsPatch(theme = next))
                    }) { Text("主题", color = fg) }
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28)))
                    }) { Text("A+", color = fg) }
                    TextButton(onClick = {
                        chapterIndex = (chapterIndex + 1).coerceAtMost(session.chapters.lastIndex)
                    }) { Text("下一章", color = fg) }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination))
                    }) {
                        Text(if (readerSettings.pagination) "分页" else "滚动", color = fg)
                    }
                    Text(
                        if (readerSettings.pagination && pageInfo.second > 0) {
                            "第 ${pageInfo.first + 1} / ${pageInfo.second} 页"
                        } else {
                            "${(scrollRatio * 100).roundToInt()}%"
                        },
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        color = fg,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }

        // 目录弹层。
        AnimatedVisibility(
            visible = showToc,
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
                    ) { showToc = false },
            )
        }
        AnimatedVisibility(
            visible = showToc,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                Text("目录", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(session.chapters) { i, _ ->
                        TextButton(onClick = {
                            saveProgress()
                            chapterIndex = i
                            showToc = false
                        }) {
                            Text(
                                session.chapterTitle(i),
                                color = if (i == chapterIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }

        // 图片查看（长按打开，×/返回关闭，保存走 SAF）。
        lightboxSrc?.let { src ->
            var zoom by remember(src) { mutableFloatStateOf(1f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* 点空白不退出，防误触 */ },
            ) {
                NativeLightboxImage(
                    src = src,
                    imageBytes = ::imageBytes,
                    zoom = zoom,
                    onZoom = { zoom = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    IconButton(onClick = {
                        pendingSaveUrl = src
                        saveLauncher.launch("AnkeShelf-image.jpg")
                    }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "保存", tint = Color.White)
                    }
                    IconButton(onClick = { lightboxSrc = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // 按 Home 退后台/切走：立即落盘，避免进程被杀丢进度（对齐 Legado onPause save）。
                runCatching { progressTracker.flush() }
                runCatching { container.progress.flush() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { progressTracker.flush() }
            runCatching { container.progress.flush() }
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

@Composable
private fun NativeLightboxImage(
    src: String,
    imageBytes: suspend (String) -> ByteArray?,
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, src) {
        value = withContext(Dispatchers.IO) {
            imageBytes(src)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onZoom(if (zoom == 1f) 2f else 1f) },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("图片加载中…", color = Color.White.copy(alpha = 0.7f))
        }
    }
}
