package io.github.gighi947.ankeshelf.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.gighi947.ankeshelf.BuildConfig
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private val THEME_CYCLE = listOf("dark", "light", "sepia")

/** 分页模式下 JS 上报的页码信息。 */
data class PageInfo(
    val page: Int = 0,
    val total: Int = 0,
    val offset: Int = 0,
)

/**
 * WebView JS 桥：
 * - saveProgress(chapterIndex, value, isOffset)：isOffset=true 为 text_offset，
 *   false 为滚动比例 0..1；
 * - pageChanged(page, total, offset)：分页模式页码指示；
 * - requestChapter(delta)：分页翻到章首/章尾时请求切章；
 * - log(message)：调试日志。
 */
class ReaderBridge(
    private val onProgressValue: (Int, Double, Boolean) -> Unit,
    private val onPageChanged: (Int, Int, Int) -> Unit,
    private val onRequestChapter: (Int) -> Unit,
    private val onLog: (String) -> Unit,
) {
    // JS 桥方法运行在 WebView 的 JS 线程，Compose 状态必须在主线程更新。
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun saveProgress(chapterIndex: Int, value: Double, isOffset: Boolean) {
        main.post { onProgressValue(chapterIndex, value, isOffset) }
    }

    @JavascriptInterface
    fun pageChanged(page: Int, total: Int, offset: Int) {
        main.post { onPageChanged(page, total, offset) }
    }

    @JavascriptInterface
    fun requestChapter(delta: Int) {
        main.post { onRequestChapter(delta) }
    }

    @JavascriptInterface
    fun log(message: String) {
        onLog(message)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    session: BookSession,
    initialChapter: Int,
    savedOffset: Int,
    jumpOffset: Int? = null,
    readerSettings: SettingsData,
    onProgress: (chapterIndex: Int, textOffset: Int) -> Unit,
    onSettingsPatch: (SettingsPatch) -> Unit,
    onStatsTick: (seconds: Int, pagesFlipped: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var chapterIndex by rememberSaveable(session.id) {
        mutableIntStateOf(initialChapter.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0)))
    }
    var barsVisible by remember { mutableStateOf(true) }
    var barsHeld by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf(PageInfo()) }
    var scrollRatio by remember { mutableFloatStateOf(0f) }
    var pendingSeconds by remember { mutableIntStateOf(0) }
    var flippedPages by remember { mutableIntStateOf(0) }

    val systemDark = isSystemInDarkTheme()
    val theme = remember(readerSettings, systemDark) {
        readerTheme(readerSettings, systemDark)
    }
    val parts = remember(session, chapterIndex) {
        extractReaderParts(session.chapterText(chapterIndex).orEmpty())
    }
    val plainLength = remember(session, chapterIndex) {
        TextExtractor.extractDomText(parts.body).length
    }

    val lenRef = remember { mutableIntStateOf(plainLength) }
    LaunchedEffect(plainLength) { lenRef.intValue = plainLength }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val loadedChapter = remember { mutableIntStateOf(-1) }
    val pageReady = remember { mutableStateOf(false) }
    val loadSeqRef = remember { mutableIntStateOf(0) }
    val insetRef = remember { mutableStateOf(0 to 0) }

    // 供 WebView factory（仅创建一次）读取最新值。
    val settingsRef = remember { mutableStateOf(readerSettings) }
    val themeRef = remember { mutableStateOf(theme) }
    val pagedRef = remember { mutableStateOf(readerSettings.pagination) }
    val activity = LocalActivity.current
    LaunchedEffect(readerSettings) {
        settingsRef.value = readerSettings
    }
    LaunchedEffect(theme.background, theme.text, theme.accent) {
        themeRef.value = theme
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript(
                "AnkeReader.applyTheme({bg:'${theme.background}',fg:'${theme.text}',primary:'${theme.accent}'});",
                null,
            )
        }
    }

    // 异形屏/安全区：
    // - 栏显示时：顶部避开状态栏（含挖孔安全区），底部避开导航栏；
    // - 沉浸式（栏隐藏）时：自动模式保留挖孔安全区约 3/8（模拟器约 51px，
    //   为 3/4 的一半，顶部更紧凑）；设置页可手动覆盖（dp 滑块，-1 = 自动）。
    val density = LocalDensity.current
    val context = LocalContext.current
    val manualTopInsetDp = remember {
        context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
            .getInt("top_inset_dp", -1)
    }
    val manualTopInsetPx = if (manualTopInsetDp >= 0) {
        with(density) { manualTopInsetDp.dp.toPx().roundToInt() }
    } else {
        -1
    }
    val cutoutBottomRef = remember { mutableIntStateOf(0) }
    LaunchedEffect(activity) {
        val act = activity ?: return@LaunchedEffect
        cutoutBottomRef.intValue = ViewCompat.getRootWindowInsets(act.window.decorView)
            ?.displayCutout
            ?.boundingRects
            ?.maxOfOrNull { it.bottom }
            ?: 0
    }
    val topInset = if (manualTopInsetPx >= 0) {
        manualTopInsetPx
    } else {
        cutoutBottomRef.intValue * 3 / 8
    }
    val bottomInset = 0
    LaunchedEffect(topInset, bottomInset) {
        insetRef.value = topInset to bottomInset
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.setInsets($topInset,$bottomInset);", null)
        }
    }

    // 沉浸式：栏显示时恢复系统栏，栏隐藏时隐藏系统栏（滑动可临时唤出）。
    LaunchedEffect(activity, pageReady.value) {
        val act = activity ?: return@LaunchedEffect
        if (pageReady.value) {
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }


    // 自动隐藏：页面加载完成后再计时，栏显示 3 秒后收起（目录打开时暂停计时），
    // 避免字体/排版加载过程中栏和安全区突然变化。
    // 点中间唤出后保持显示；滚动/翻页一定距离后自动收起。
    fun hideBars() {
        barsVisible = false
        barsHeld = false
    }

    fun toggleBars() {
        barsVisible = !barsVisible
        barsHeld = barsVisible
    }

    LaunchedEffect(barsVisible, barsHeld, showToc, pageReady.value) {
        if (barsVisible && !barsHeld && !showToc && pageReady.value) {
            delay(3000)
            hideBars()
        }
    }

    fun saveNow(web: WebView?) {
        // 页面未就绪/已被切章中断时跳过，避免在旧页面销毁后执行 JS 报错。
        if (!pageReady.value) return
        val js = if (pagedRef.value) {
            "(function(){try{var o=AnkeReader.currentOffset();" +
                "AnkeReaderBridge.saveProgress($chapterIndex,o,true);}catch(e){}})();"
        } else {
            "(function(){var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);" +
                "try{AnkeReaderBridge.saveProgress($chapterIndex,r,false);}catch(e){}})();"
        }
        web?.evaluateJavascript(js, null)
    }

    fun changeChapter(delta: Int) {
        saveNow(webViewRef.value)
        val next = (chapterIndex + delta).coerceIn(0, session.chapters.lastIndex)
        if (next != chapterIndex) {
            chapterIndex = next
            showToc = false
        }
    }

    fun flipPage(dir: Int) {
        flippedPages++
        webViewRef.value?.evaluateJavascript("AnkeReader.flipPage($dir);", null)
    }

    // 阅读统计：5 秒心跳累计，满 60 秒落盘（桌面 stats.js 语义）。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000)
            if (pageReady.value) {
                pendingSeconds += 5
                if (pendingSeconds >= 60) {
                    onStatsTick(pendingSeconds, flippedPages)
                    pendingSeconds = 0
                    flippedPages = 0
                }
            }
        }
    }

    val bridge = remember {
        ReaderBridge(
            onProgressValue = { idx, value, isOffset ->
                if (isOffset) {
                    val offset = value.toInt().coerceIn(0, lenRef.intValue)
                    onProgress(idx, offset)
                } else {
                    val ratio = value.coerceIn(0.0, 1.0)
                    // 滚动模式下滑动一段距离（节流回调触发）后收起手动唤出的控制条。
                    if (!pagedRef.value && barsHeld) hideBars()
                    scrollRatio = ratio.toFloat()
                    val offset = (ratio * lenRef.intValue).roundToInt().coerceIn(0, lenRef.intValue)
                    onProgress(idx, offset)
                }
            },
            onPageChanged = { page, total, offset ->
                pageInfo = PageInfo(page = page, total = total, offset = offset)
            },
            onRequestChapter = { delta -> changeChapter(delta) },
            onLog = { Log.d("AnkeShelf", it) },
        )
    }

    LaunchedEffect(readerSettings.pagination) {
        val paged = readerSettings.pagination
        pagedRef.value = paged
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.setMode($paged);", null)
        }
    }

    LaunchedEffect(readerSettings.font_size, readerSettings.line_height) {
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript(
                "AnkeReader.applyTypography({fontSize:${readerSettings.font_size}," +
                    "lineHeight:${readerSettings.line_height}});",
                null,
            )
        }
        if (pageReady.value && pagedRef.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.onResize();", null)
        }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.onResize();", null)
        }
    }

    LaunchedEffect(chapterIndex) {
        pageInfo = PageInfo()
        scrollRatio = 0f
    }

    // 章节加载放在 LaunchedEffect：chapterIndex 变化后重组完成再构建 HTML，
    // 避免 AndroidView.update 闭包在重组前被调用时捕获旧章节 HTML（换章失效根因）。
    LaunchedEffect(chapterIndex, session) {
        val partsNow = extractReaderParts(session.chapterText(chapterIndex).orEmpty())
        val htmlNow = buildReaderHtml(partsNow, themeRef.value, settingsRef.value)
        lenRef.intValue = TextExtractor.extractDomText(partsNow.body).length
        loadedChapter.intValue = chapterIndex
        pageReady.value = false
        loadSeqRef.intValue++
        val web = webViewRef.value ?: return@LaunchedEffect
        web.tag = loadSeqRef.intValue
        web.loadDataWithBaseURL(
            "file:///android_asset/reader/",
            htmlNow,
            "text/html",
            "utf-8",
            null,
        )
    }

    // 系统返回键 = 保存进度并返回书架（避免直接退出应用）。
    BackHandler {
        saveNow(webViewRef.value)
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    settings.javaScriptEnabled = true
                    settings.setAllowFileAccess(false)
                    setBackgroundColor(Color.parseColor(themeRef.value.background))
                    addJavascriptInterface(bridge, "AnkeReaderBridge")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.d("AnkeShelf", "console: ${msg?.message()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            // 章节内链接/锚点在安卓阅读器里暂不支持跳转，一律拦截，
                            // 避免 WebView 导航到不存在的 file:// 路径出现错误页。
                            Log.d("AnkeShelf", "blocked nav: ${request?.url}")
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            Log.e("AnkeShelf", "page error: ${error?.description} code=${error?.errorCode}")
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            // 忽略过期加载回调：快速换章时旧页面的 onPageFinished 可能迟到，
                            // 避免用旧 DOM 初始化新章节（未完全加载时换章失效的根因之一）。
                            if (view.tag as? Int != loadSeqRef.intValue) {
                                Log.d("AnkeShelf", "stale page finished ignored")
                                return
                            }
                            pageReady.value = true
                            val s = settingsRef.value
                            val t = themeRef.value
                            val restoreOffset = if (chapterIndex == initialChapter) {
                                jumpOffset ?: savedOffset
                            } else {
                                0
                            }
                            view.evaluateJavascript(
                                "AnkeReader.init({chapterIndex:$chapterIndex,paged:${pagedRef.value}," +
                                    "offset:$restoreOffset,margin:${s.margin_px},gap:${s.gap_px}," +
                                    "pageWidth:${s.page_width},fontSize:${s.font_size}," +
                                    "lineHeight:${s.line_height},dualPage:${s.dual_page}," +
                                    "autoDual:${s.auto_dual}," +
                                    "topInset:${insetRef.value.first},bottomInset:${insetRef.value.second}," +
                                    "theme:{bg:'${t.background}',fg:'${t.text}',primary:'${t.accent}'}});",
                                null,
                            )
                            view.evaluateJavascript(
                                """(function(){
                                   var last=0;
                                    window.addEventListener('scroll',function(){
                                     var now=Date.now(); if(now-last<1200) return; last=now;
                                     var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);
                                     try{AnkeReaderBridge.saveProgress($chapterIndex,r,false);}catch(e){}
                                   });
                                   window.addEventListener('pagehide',function(){
                                     var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);
                                     try{AnkeReaderBridge.saveProgress($chapterIndex,r,false);}catch(e){}
                                   });
                                 })();""",
                                null,
                            )
                        }
                    }
                    var downX = 0f
                    var downY = 0f
                    setOnTouchListener { _, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = ev.x
                                downY = ev.y
                            }

                            MotionEvent.ACTION_UP -> {
                                val dx = ev.x - downX
                                val dy = ev.y - downY
                                val isSwipe = pagedRef.value && abs(dx) >= 60f && abs(dx) >= abs(dy) * 1.2f
                                val isTap = dx * dx + dy * dy < 50f * 50f
                                when {
                                    isSwipe -> {
                                        flipPage(if (dx < 0) 1 else -1)
                                        hideBars()
                                    }
                                    isTap -> {
                                        val w = width
                                        when {
                                            pagedRef.value && ev.x < w / 3f -> {
                                                flipPage(-1)
                                                hideBars()
                                            }
                                            pagedRef.value && ev.x > 2 * w / 3f -> {
                                                flipPage(1)
                                                hideBars()
                                            }
                                            ev.x >= w / 3f && ev.x <= 2 * w / 3f -> toggleBars()
                                            // 滚动模式下侧边点击不换章（防误触），换章走底部按钮。
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                        false
                    }
                    webViewRef.value = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 亮度遮罩：夜间调低屏幕亮度（桌面 Assist.setBrightness 的 Android 版）。
        if (readerSettings.brightness > 0.0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black.copy(alpha = readerSettings.brightness.toFloat().coerceIn(0f, 0.7f))),
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    saveNow(webViewRef.value)
                    onBack()
                }) { Text("← 返回") }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 章节标题左侧小竖条：比主题色稍深，增强层次。
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(
                                lerp(MaterialTheme.colorScheme.primary, ComposeColor.Black, 0.18f),
                                RoundedCornerShape(1.5.dp),
                            ),
                    )
                    Text(
                        text = session.chapterTitle(chapterIndex),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(onClick = { showToc = !showToc }) { Text("目录") }
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                val fraction = if (readerSettings.pagination && pageInfo.total > 0) {
                    (pageInfo.page + 1f) / pageInfo.total
                } else {
                    scrollRatio.coerceIn(0f, 1f)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { changeChapter(-1) }) { Text("上一章") }
                    TextButton(onClick = {
                        onSettingsPatch(
                            SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14)),
                        )
                    }) { Text("A-") }
                    TextButton(onClick = {
                        val next = THEME_CYCLE[(THEME_CYCLE.indexOf(readerSettings.theme) + 1) % THEME_CYCLE.size]
                        onSettingsPatch(SettingsPatch(theme = next))
                    }) { Text("主题") }
                    TextButton(onClick = {
                        onSettingsPatch(
                            SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28)),
                        )
                    }) { Text("A+") }
                    TextButton(onClick = { changeChapter(1) }) { Text("下一章") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination))
                    }) {
                        Text(
                            // 显示当前翻页模式；点击切换为另一种。
                            text = if (readerSettings.pagination) "分页" else "滚动",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        text = if (readerSettings.pagination && pageInfo.total > 0) {
                            "第 ${pageInfo.page + 1} / ${pageInfo.total} 页"
                        } else {
                            "${(scrollRatio * 100).roundToInt()}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    )
                }
            }
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
                    .width(300.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                Text("目录", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(session.chapters) { i, ch ->
                        TextButton(
                            onClick = {
                                saveNow(webViewRef.value)
                                chapterIndex = i
                                showToc = false
                            },
                        ) {
                            Text(
                                text = session.chapterTitle(i),
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
    }

    DisposableEffect(Unit) {
        onDispose {
            val web = webViewRef.value
            web?.removeJavascriptInterface("AnkeReaderBridge")
            saveNow(web)
            if (pendingSeconds >= 1) {
                onStatsTick(pendingSeconds, flippedPages)
            }
            // 退出阅读器时恢复系统栏，避免返回书架后仍处于沉浸式。
            val act = activity
            if (act != null) {
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
