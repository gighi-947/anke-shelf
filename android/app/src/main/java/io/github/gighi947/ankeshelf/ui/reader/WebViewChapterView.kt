package io.github.gighi947.ankeshelf.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gighi947.ankeshelf.BuildConfig
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.LogEvents
import io.github.gighi947.ankeshelf.service.ngaHeaders
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** 正文选区（text_offset 区间 + 视口矩形，CSS px）。 */
data class ReaderSelection(
    val start: Int,
    val end: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 章内跳转请求：token 变化即触发一次跳转（书签/标注定位）。 */
data class ReaderJump(val token: Int, val offset: Int)

/**
 * Hybrid reader callbacks. Progress events carry the reporting chapter index so
 * stale async events from the previous page are attributed to the correct
 * chapter instead of being dropped or clamped with the wrong chapter length.
 */
data class WebViewReaderCallbacks(
    val onReady: () -> Unit = {},
    val onBridgeVersionMismatch: (expected: Int, actual: Int) -> Unit = { _, _ -> },
    val onMode: (paged: Boolean) -> Unit = {},
    val onProgress: (chapter: Int, offset: Int, page: Int, total: Int, ratio: Double) -> Unit = { _, _, _, _, _ -> },
    val onPagedAnchor: (chapter: Int, offset: Int) -> Unit = { _, _ -> },
    val onProgressNow: (chapter: Int, offset: Int, page: Int, total: Int, ratio: Double) -> Unit = { _, _, _, _, _ -> },
    val onScrollMoved: () -> Unit = {},
    val onPageChanged: (chapter: Int, page: Int, total: Int) -> Unit = { _, _, _ -> },
    val onImageTap: (String) -> Unit = {},
    val onTapZone: (String) -> Unit = {},
    val onRequestChapter: (Int) -> Unit = {},
    val onChapterSwitch: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onFlush: () -> Unit = {},
    /** 选区变化：null = 选区已消失。 */
    val onSelection: (ReaderSelection?) -> Unit = {},
    /** 点击已注入的高亮 mark（携带标注 id）。 */
    val onHighlightTap: (String) -> Unit = {},
    // ---- 骨碌碌宿主层（批 8/9）----
    /** 骰点分组被揭示（单组 / 批量），宿主负责持久化。 */
    val onGululuUnlock: (List<String>) -> Unit = {},
    /** 阅读线所在楼层变化（评论抽屉与弹幕据此换源）。 */
    val onGululuFloor: (Int) -> Unit = {},
    /** 当前楼层视效（空串=停止）。 */
    val onGululuVfx: (String) -> Unit = {},
    /** 氛围背景 URL（空串=清除）。 */
    val onGululuBackground: (String) -> Unit = {},
    /** 音乐：手动点击或自动标记到达阅读线。 */
    val onGululuMusic: (url: String, title: String, auto: Boolean) -> Unit = { _, _, _ -> },
    val onGululuMusicStop: () -> Unit = {},
    /** 秘密密文与线索口令（明文只在宿主弹窗出现）。 */
    val onGululuSecret: (title: String, cipher: String) -> Unit = { _, _ -> },
    val onGululuClue: (title: String, password: String) -> Unit = { _, _ -> },
    /** 点击段落评论徽标。 */
    val onGululuParagraphComments: (String) -> Unit = {},
    /** 本章骨碌碌元素统计：[组数, 未揭示组数, 秘密数, 线索数, 楼数]。 */
    val onGululuStats: (List<Int>) -> Unit = {},
)

/**
 * Minimal WebView rendering core: renders chapter HTML (chapter CSS + reader-lite.js
 * pagination/text_offset) while the Compose shell owns chrome, image viewer and
 * progress persistence via [ChapterProgressTracker].
 *
 * Progress safety rules (aligned with desktop reader.js):
 * - before loading a new chapter, read the old page's exact offset and flush it
 *   (loadChapter semantics); the load itself waits for that read.
 * - on disposal, read the final offset, report it, flush, then destroy the
 *   WebView slightly later so the async value callback is not dropped.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewChapterView(
    html: String,
    chapterIndex: Int,
    paged: Boolean,
    theme: ReaderThemeColors,
    fontSize: Int,
    lineHeight: Double,
    marginPx: Int,
    gapPx: Int,
    pageWidth: Double,
    dualPage: Boolean,
    autoDual: Boolean,
    topInsetPx: Int,
    initialOffset: Int,
    initialPage: Int = -1,
    initialTotal: Int = -1,
    initialRatio: Double = -1.0,
    /** 本章高亮（JSON 数组：[{id,start,end,color}]），随创建/删除变化即重新注入。 */
    highlightsJson: String = "[]",
    /** 章内跳转（书签/标注定位）；token 变化触发一次。 */
    jump: ReaderJump? = null,
    /** 清除 WebView 文本选区；token 变化触发一次（标注操作后收起系统选择手柄）。 */
    clearSelectionToken: Int = 0,
    /** 自动滚动/自动翻页开关与速度倍率（对齐桌面 assist.js setAutoScroll）。 */
    autoScroll: Boolean = false,
    autoScrollSpeed: Double = 2.0,
    /** 骨碌碌书籍才启用宿主层交互（骰点/秘密/音乐/背景/视效/段落评论）。 */
    gululu: Boolean = false,
    /** 已揭示的骰点分组（JSON 数组），首次 init 携带。 */
    gululuUnlockedJson: String = "[]",
    /** 段落评论计数（JSON：{paragraphId: count}），变化即重新注入徽标。 */
    paragraphCommentsJson: String = "{}",
    /** 一次性骨碌碌命令（如批量揭示/重置遮罩）；token 变化触发一次。 */
    gululuCommand: String = "",
    gululuCommandToken: Int = 0,
    session: BookSession,
    container: AppContainer,
    callbacks: WebViewReaderCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val pageReady = remember { mutableStateOf(false) }
    val settled = remember { mutableStateOf(false) }
    val loadSeq = remember { mutableIntStateOf(0) }
    val chapterRef = remember { mutableIntStateOf(chapterIndex) }
    val loadWasSwitch = remember { mutableStateOf(false) }
    val pagedRef = remember { mutableStateOf(paged) }
    val insetRef = remember { mutableIntStateOf(topInsetPx) }
    val settingsRef = remember {
        mutableStateOf(
            ReaderViewSettings(
                fontSize = fontSize,
                lineHeight = lineHeight,
                marginPx = marginPx,
                gapPx = gapPx,
                pageWidth = pageWidth,
                dualPage = dualPage,
                autoDual = autoDual,
            ),
        )
    }
    val themeRef = remember { mutableStateOf(theme) }
    val initialOffsetRef = rememberUpdatedState(initialOffset)
    val initialRatioRef = rememberUpdatedState(initialRatio)
    val highlightsRef = rememberUpdatedState(highlightsJson)
    // init 已注入的高亮快照：避免章节加载后再重复注入一次（多余重排）。
    val appliedHighlights = remember { mutableStateOf("") }
    val callbacksRef = rememberUpdatedState(callbacks)
    val sessionRef = rememberUpdatedState(session)
    val containerRef = rememberUpdatedState(container)
    val ngaConfig = remember { container.ngaConfig.load() }

    LaunchedEffect(chapterIndex, html) {
        settled.value = false
        val web = webViewRef.value
        val old = chapterRef.intValue
        if (web != null && old != chapterIndex) {
            loadWasSwitch.value = true
            // Desktop loadChapter: capture the old chapter's exact offset before
            // the new page replaces it. Run the latch off the main thread so the
            // evaluateJavascript callback (main thread) can complete. The WebView
            // call itself must be posted to the main thread (checkThread).
            if (pageReady.value) {
                val captured = withContext(Dispatchers.Default) {
                    val latch = CountDownLatch(1)
                    var value = 0
                    var ratio = -1.0
                    var paged = false
                    web.post {
                        web.evaluateJavascript(
                            "(function(){try{return AnkeReader.currentScrollState();}catch(e){return {o:0,r:-1,p:false};}})()",
                            ValueCallback { v ->
                                val st = runCatching { JSONObject(v ?: "{}") }
                                    .onFailure { Log.w("AnkeShelf", "解析 currentScrollState 失败：${it.message}") }
                                    .getOrNull()
                                value = st?.optInt("o", 0) ?: 0
                                ratio = st?.optDouble("r", -1.0) ?: -1.0
                                paged = st?.optBoolean("p", false) ?: false
                                latch.countDown()
                            },
                        )
                    }
                    if (latch.await(300, TimeUnit.MILLISECONDS)) {
                        Triple(value, ratio, paged)
                    } else {
                        Triple(0, -1.0, false)
                    }
                }
                if (captured.first > 0) {
                    if (captured.third) {
                        callbacksRef.value.onPagedAnchor(old, captured.first)
                    } else {
                        callbacksRef.value.onProgress(old, captured.first, -1, -1, captured.second)
                    }
                }
            }
            callbacksRef.value.onChapterSwitch(old, chapterIndex)
        }
        chapterRef.intValue = chapterIndex
        if (old == chapterIndex) loadWasSwitch.value = false
        pageReady.value = false
        loadSeq.intValue++
        web ?: return@LaunchedEffect
        web.tag = loadSeq.intValue
        val s = sessionRef.value
        val baseDir = s.chapterBaseDir(chapterIndex)
        val base = if (baseDir.isNotEmpty()) {
            "file:///android_epub/${s.id}/$baseDir/"
        } else {
            "file:///android_asset/reader/"
        }
        web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
    }

    // 加载/排版稳定最长屏蔽 5 秒，防止图片挂死导致永远无法操作。
    LaunchedEffect(chapterIndex, html) {
        delay(5000)
        settled.value = true
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

    LaunchedEffect(fontSize, lineHeight) {
        settingsRef.value = settingsRef.value.copy(fontSize = fontSize, lineHeight = lineHeight)
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript(
                "AnkeReader.applyTypography({fontSize:$fontSize,lineHeight:$lineHeight});",
                null,
            )
            if (pagedRef.value) {
                webViewRef.value?.evaluateJavascript("AnkeReader.onResize();", null)
            }
        }
    }

    LaunchedEffect(paged) {
        pagedRef.value = paged
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.setMode($paged);", null)
        }
    }

    LaunchedEffect(topInsetPx) {
        insetRef.intValue = topInsetPx
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.setInsets($topInsetPx,0);", null)
        }
    }

    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        if (pageReady.value) {
            webViewRef.value?.evaluateJavascript("AnkeReader.onResize();", null)
        }
    }

    // 高亮变化（新增/改色/删除）后重新注入；首次加载由 init 携带，避免重复排版。
    LaunchedEffect(highlightsJson, pageReady.value) {
        if (!pageReady.value) return@LaunchedEffect
        if (highlightsJson == appliedHighlights.value) return@LaunchedEffect
        appliedHighlights.value = highlightsJson
        webViewRef.value?.evaluateJavascript(
            "AnkeReader.applyHighlights(${JSONObject.quote(highlightsJson)});",
            null,
        )
    }

    // 章内跳转（书签/标注定位）：token 变化触发一次，落盘由 JS 按当前模式完成。
    LaunchedEffect(jump?.token, pageReady.value, settled.value) {
        val target = jump ?: return@LaunchedEffect
        if (!pageReady.value || !settled.value) return@LaunchedEffect
        webViewRef.value?.evaluateJavascript("AnkeReader.gotoTextOffset(${target.offset});", null)
    }

    LaunchedEffect(clearSelectionToken) {
        if (clearSelectionToken == 0 || !pageReady.value) return@LaunchedEffect
        webViewRef.value?.evaluateJavascript("AnkeReader.clearSelection();", null)
    }

    // 自动滚动：开关/速度/章节切换后重新下发（JS 状态随页面销毁，换章需重启）。
    LaunchedEffect(autoScroll, autoScrollSpeed, chapterIndex, pageReady.value, settled.value) {
        if (!pageReady.value) return@LaunchedEffect
        val web = webViewRef.value ?: return@LaunchedEffect
        if (autoScroll && settled.value) {
            web.evaluateJavascript("AnkeReader.startAutoScroll($autoScrollSpeed);", null)
        } else {
            web.evaluateJavascript("AnkeReader.stopAutoScroll();", null)
        }
    }

    // 段落评论徽标：评论加载完成/切换后重新注入（徽标带 data-textpos-exclude，不动坐标）。
    LaunchedEffect(paragraphCommentsJson, pageReady.value, settled.value) {
        if (!gululu || !pageReady.value || !settled.value) return@LaunchedEffect
        webViewRef.value?.evaluateJavascript(
            "AnkeReader.applyParagraphComments(${JSONObject.quote(paragraphCommentsJson)});",
            null,
        )
    }

    // 一次性骨碌碌命令（批量揭示 / 重置遮罩）。
    LaunchedEffect(gululuCommandToken) {
        if (gululuCommandToken == 0 || gululuCommand.isEmpty() || !pageReady.value) return@LaunchedEffect
        webViewRef.value?.evaluateJavascript(gululuCommand, null)
    }

    // 本章骨碌碌元素统计（总览面板用）：排版稳定后查询一次。
    LaunchedEffect(gululu, chapterIndex, settled.value, gululuCommandToken, gululuUnlockedJson) {
        if (!gululu || !pageReady.value || !settled.value) return@LaunchedEffect
        webViewRef.value?.evaluateJavascript("AnkeReader.gululuChapterInfo();") { raw ->
            val text = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: return@evaluateJavascript
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@evaluateJavascript
            callbacksRef.value.onGululuStats(
                listOf(
                    obj.optInt("groups", 0),
                    obj.optInt("locked", 0),
                    obj.optInt("secrets", 0),
                    obj.optInt("clues", 0),
                    obj.optInt("floors", 0),
                ),
            )
        }
    }

    val bridge = remember {
        LiteBridge(
            callbacks = { callbacksRef.value },
            onSettled = { settled.value = true },
            onMode = { pagedRef.value = it },
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                val fontsDir = containerRef.value.appPaths.fontsDir
                if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                settings.javaScriptEnabled = true
                settings.setAllowFileAccess(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = NgaConfig.DEFAULT_UA
                setBackgroundColor(Color.parseColor(themeRef.value.background))
                addJavascriptInterface(bridge, "AnkeReaderBridge")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        Log.d("AnkeShelf", "console: ${msg?.message()}")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        val s = sessionRef.value
                        val c = containerRef.value
                        if (url.startsWith("file:///android_epub/")) {
                            val rel = URLDecoder.decode(
                                url.removePrefix("file:///android_epub/").substringAfter('/'),
                                "UTF-8",
                            )
                            val bytes = s.readAsset(chapterRef.intValue, rel)
                            if (bytes != null) {
                                val mime = when (rel.substringAfterLast('.', "").lowercase()) {
                                    "png" -> "image/png"
                                    "gif" -> "image/gif"
                                    "webp" -> "image/webp"
                                    "svg" -> "image/svg+xml"
                                    "woff2" -> "font/woff2"
                                    "woff" -> "font/woff"
                                    "ttf" -> "font/ttf"
                                    "otf" -> "font/otf"
                                    else -> "application/octet-stream"
                                }
                                return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                            }
                        }
                        if (url.startsWith("file:///android_fonts/")) {
                            val name = URLDecoder.decode(url.removePrefix("file:///android_fonts/"), "UTF-8")
                            val f = File(fontsDir, name)
                            if (f.isFile) {
                                val mime = if (name.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
                                return WebResourceResponse(mime, null, FileInputStream(f))
                            }
                        }
                        if (url.startsWith("file:///android_images/")) {
                            val name = URLDecoder.decode(url.removePrefix("file:///android_images/"), "UTF-8")
                            val f = File(c.appPaths.root, "images/$name")
                            if (f.isFile) {
                                val mime = when (f.extension.lowercase()) {
                                    "png" -> "image/png"
                                    "gif" -> "image/gif"
                                    "webp" -> "image/webp"
                                    else -> "image/jpeg"
                                }
                                return WebResourceResponse(mime, null, FileInputStream(f))
                            }
                        }
                        if (
                            url.startsWith("http") &&
                            (
                                url.contains("img.nga.cn", ignoreCase = true) ||
                                    url.contains("nga.178.com", ignoreCase = true) ||
                                    url.contains("ngabbs.com", ignoreCase = true)
                                )
                        ) {
                            return runCatching {
                                val req = Request.Builder()
                                    .url(url)
                                    .ngaHeaders(ngaConfig)
                                    .build()
                                val resp = c.okHttp.newCall(req).execute()
                                if (!resp.isSuccessful) {
                                    resp.close()
                                    null
                                } else {
                                    val mime = resp.header("Content-Type")?.substringBefore(";")
                                        ?: "image/jpeg"
                                    val body = resp.body
                                    // WebView 关闭返回流时同步释放 OkHttp Response，避免连接泄漏。
                                    val stream = object : FilterInputStream(body.byteStream()) {
                                        override fun close() {
                                            try {
                                                super.close()
                                            } finally {
                                                resp.close()
                                            }
                                        }
                                    }
                                    WebResourceResponse(mime, null, stream)
                                }
                            }.onFailure { Log.w("AnkeShelf", "NGA 图片代理失败 $url：${it.message}") }
                                .getOrNull()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest?,
                    ): Boolean = true

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        Log.e("AnkeShelf", "page error: ${error?.description} code=${error?.errorCode}")
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (view.tag as? Int != loadSeq.intValue) {
                            Log.d("AnkeShelf", "stale page finished ignored")
                            return
                        }
                        pageReady.value = true
                        val s = settingsRef.value
                        val t = themeRef.value
                        appliedHighlights.value = highlightsRef.value
                        view.evaluateJavascript(
                            "AnkeReader.init({chapterIndex:${chapterRef.intValue},paged:${pagedRef.value}," +
                                "offset:${initialOffsetRef.value},margin:${s.marginPx},gap:${s.gapPx}," +
                                "scrollRatio:${initialRatioRef.value}," +
                                "highlights:${JSONObject.quote(highlightsRef.value)}," +
                                "gululu:$gululu," +
                                "gululuUnlocked:${JSONObject.quote(gululuUnlockedJson)}," +
                                "pageWidth:${s.pageWidth},fontSize:${s.fontSize}," +
                                "lineHeight:${s.lineHeight},dualPage:${s.dualPage}," +
                                "autoDual:${s.autoDual}," +
                                "wasSwitch:${loadWasSwitch.value}," +
                                "page:$initialPage,total:$initialTotal," +
                                "topInset:${insetRef.intValue},bottomInset:0," +
                                "theme:{bg:'${t.background}',fg:'${t.text}',primary:'${t.accent}'}});",
                            null,
                        )
                    }
                }
                var downX = 0f
                var downY = 0f
                var longPressTask: Runnable? = null
                fun cancelLongPress() {
                    longPressTask?.let { removeCallbacks(it) }
                    longPressTask = null
                }
                setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.x
                            downY = ev.y
                            val x = ev.x
                            val y = ev.y
                            val task = Runnable {
                                longPressTask = null
                                evaluateJavascript(
                                    "AnkeReader.openImageAt(${x / density.density},${y / density.density});",
                                    object : ValueCallback<String> {
                                        override fun onReceiveValue(value: String?) {
                                            if (value == "true") cancelLongPress()
                                        }
                                    },
                                )
                            }
                            longPressTask = task
                            postDelayed(task, 450)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (abs(ev.x - downX) + abs(ev.y - downY) > 24f) {
                                cancelLongPress()
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            cancelLongPress()
                            val dx = ev.x - downX
                            val dy = ev.y - downY
                            val isSwipe = pagedRef.value && abs(dx) >= 60f && abs(dx) >= abs(dy) * 1.2f
                            val isTap = dx * dx + dy * dy < 24f * 24f
                            when {
                                isSwipe -> {
                                    evaluateJavascript("AnkeReader.flipPage(${if (dx < 0) 1 else -1});", null)
                                    callbacksRef.value.onTapZone("hide")
                                }
                                isTap && pagedRef.value && ev.x < width / 3f -> {
                                    evaluateJavascript("AnkeReader.flipPage(-1);", null)
                                    callbacksRef.value.onTapZone("hide")
                                }
                                isTap && pagedRef.value && ev.x > 2 * width / 3f -> {
                                    evaluateJavascript("AnkeReader.flipPage(1);", null)
                                    callbacksRef.value.onTapZone("hide")
                                }
                                isTap &&
                                    abs(ev.x - width / 2f) <= width * 0.18f &&
                                    abs(ev.y - height / 2f) <= height * 0.20f -> {
                                    // 悬浮菜单触发区：屏幕正中心一块自适应区域，避免误触。
                                    val cssX = ev.x / density.density
                                    val cssY = ev.y / density.density
                                    evaluateJavascript(
                                        "AnkeReader.hitGululuInteractive($cssX,$cssY);",
                                        ValueCallback { value ->
                                            if (value != "true") {
                                                callbacksRef.value.onTapZone("middle")
                                            }
                                        },
                                    )
                                }
                                else -> Unit
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
        // 跳转/排版未就绪前屏蔽视野与触摸：看不到中间布局、也不会误触翻页
        // 把位置拉回章首；JS 侧 onSettled（字体/图片就绪并恢复完成）后撤下。
        if (!pageReady.value || !settled.value) {
            val shieldColor = runCatching {
                androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(themeRef.value.background))
            }.getOrDefault(androidx.compose.ui.graphics.Color.White)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(shieldColor)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "加载中…",
                    color = runCatching {
                        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(themeRef.value.text))
                    }.getOrDefault(androidx.compose.ui.graphics.Color.Gray).copy(alpha = 0.55f),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val web = webViewRef.value
            if (web != null) {
                val idx = chapterRef.intValue
                // Read the final offset before teardown; delay destroy so the
                // async value callback is delivered and the tracker flushes.
                web.evaluateJavascript(
                    // 分页模式返回 -1：退出保存直接 flush 已保存的锚点（翻页即时落盘），
                    // 不能用“当前页顶采样”覆盖——字体/图片重排后页顶会比锚点靠前，
                    // 每次退出都把进度往回拉（9.48 漂移根因）。滚动模式才需要取即时值。
                    "(function(){try{return AnkeReader.currentScrollState();}catch(e){return {o:0,r:-1,p:false};}})()",
                    ValueCallback { v ->
                        val st = runCatching { JSONObject(v ?: "{}") }
                            .onFailure { Log.w("AnkeShelf", "解析 dispose currentScrollState 失败：${it.message}") }
                            .getOrNull()
                        val o = st?.optInt("o", 0) ?: 0
                        val r = st?.optDouble("r", -1.0) ?: -1.0
                        val isPaged = st?.optBoolean("p", false) ?: false
                        Log.d("AnkeShelf", "dispose query o=$o r=$r paged=$isPaged settled=${settled.value} cfgPaged=${pagedRef.value}")
                        // 加载/排版未稳定（遮罩期间）退出的查询值不可信，
                        // 保留已保存的锚点，不覆盖进度。
                        // 分页模式忽略 o（flush 已保存锚点，9.48）；滚动模式才采用 o/r。
                        if (!isPaged && o > 0 && settled.value) {
                            callbacksRef.value.onProgress(idx, o, -1, -1, r)
                        }
                        callbacksRef.value.onFlush()
                    },
                )
                web.postDelayed({
                    web.removeJavascriptInterface("AnkeReaderBridge")
                    web.destroy()
                }, 200)
            } else {
                callbacksRef.value.onFlush()
            }
            webViewRef.value = null
        }
    }
}

private data class ReaderViewSettings(
    val fontSize: Int,
    val lineHeight: Double,
    val marginPx: Int,
    val gapPx: Int,
    val pageWidth: Double,
    val dualPage: Boolean,
    val autoDual: Boolean,
)

/**
 * Minimal JS bridge: reports events to the Compose shell. All callbacks are
 * posted to the main thread because @JavascriptInterface runs on a WebView
 * background thread and Compose state must only be mutated on the main thread.
 * Stale chapters are NOT dropped: the tracker attributes them by index.
 */
private class LiteBridge(
    private val callbacks: () -> WebViewReaderCallbacks,
    private val onSettled: () -> Unit,
    private val onMode: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun log(msg: String) {
        Log.d("AnkeShelf", msg)
    }

    @JavascriptInterface
    fun saveProgress(idx: Int, value: Double, isOffset: Boolean, page: Int, total: Int, ratio: Double) {
        if (!isOffset) return
        val offset = value.toInt()
        if (offset <= 0) return
        main.post { callbacks().onProgress(idx, offset, page, total, ratio) }
    }

    @JavascriptInterface
    fun saveProgressNow(idx: Int, value: Double, isOffset: Boolean, page: Int, total: Int, ratio: Double) {
        if (!isOffset) return
        val offset = value.toInt()
        if (offset <= 0) return
        main.post { callbacks().onProgressNow(idx, offset, page, total, ratio) }
    }

    @JavascriptInterface
    fun onScrollMoved() {
        main.post { callbacks().onScrollMoved() }
    }

    @JavascriptInterface
    fun pageChanged(idx: Int, page: Int, total: Int) {
        if (page < 0 || total <= 0) return
        main.post { callbacks().onPageChanged(idx, page, total) }
    }

    @JavascriptInterface
    fun requestChapter(delta: Int) {
        main.post { callbacks().onRequestChapter(delta) }
    }

    @JavascriptInterface
    fun openImage(src: String) {
        if (src.isNotBlank()) main.post { callbacks().onImageTap(src) }
    }

    @JavascriptInterface
    fun onReady(payload: String) {
        val ready = BridgeProtocol.parseReady(payload)
        if (ready == null) {
            Log.e("AnkeShelf", "[bridge] malformed ready payload")
            LogEvents.event("bridge", "ready_malformed", "expected" to BridgeProtocol.VERSION)
            main.post { callbacks().onBridgeVersionMismatch(BridgeProtocol.VERSION, -1) }
            return
        }
        Log.d(
            "AnkeShelf",
            "[bridge] ready version=${ready.version} capabilities=${ready.capabilities.sorted()}",
        )
        if (!BridgeProtocol.isCompatible(ready)) {
            Log.e(
                "AnkeShelf",
                "[bridge] incompatible version=${ready.version} expected=${BridgeProtocol.VERSION}",
            )
            LogEvents.event(
                "bridge",
                "version_mismatch",
                "expected" to BridgeProtocol.VERSION,
                "actual" to ready.version,
            )
            main.post { callbacks().onBridgeVersionMismatch(BridgeProtocol.VERSION, ready.version) }
            return
        }
        main.post { callbacks().onReady() }
    }

    @JavascriptInterface
    fun onSettled() {
        main.post { onSettled() }
    }

    @JavascriptInterface
    fun onMode(paged: Boolean) {
        main.post { onMode(paged) }
    }

    /** 选区变化：空串 = 选区消失（宿主收起标注工具条）。 */
    @JavascriptInterface
    fun onSelection(payload: String?) {
        if (payload.isNullOrBlank()) {
            main.post { callbacks().onSelection(null) }
            return
        }
        val obj = runCatching { JSONObject(payload) }
            .onFailure { Log.w("AnkeShelf", "解析 onSelection 失败：${it.message}") }
            .getOrNull() ?: return
        val start = obj.optInt("start", -1)
        val end = obj.optInt("end", -1)
        if (start < 0 || end <= start) return
        val selection = ReaderSelection(
            start = start,
            end = end,
            text = obj.optString("text", ""),
            left = obj.optDouble("left", 0.0).toFloat(),
            top = obj.optDouble("top", 0.0).toFloat(),
            right = obj.optDouble("right", 0.0).toFloat(),
            bottom = obj.optDouble("bottom", 0.0).toFloat(),
        )
        main.post { callbacks().onSelection(selection) }
    }

    @JavascriptInterface
    fun onHighlightTap(id: String?) {
        val annId = id?.trim().orEmpty()
        if (annId.isEmpty()) return
        main.post { callbacks().onHighlightTap(annId) }
    }

    // ---- 骨碌碌宿主层 ----

    @JavascriptInterface
    fun gululuUnlock(groupId: String?) {
        val id = groupId?.trim().orEmpty()
        if (id.isEmpty()) return
        main.post { callbacks().onGululuUnlock(listOf(id)) }
    }

    @JavascriptInterface
    fun gululuUnlockAll(payload: String?) {
        val ids = runCatching {
            org.json.JSONArray(payload ?: "[]").let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            }
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        main.post { callbacks().onGululuUnlock(ids) }
    }

    @JavascriptInterface
    fun gululuFloor(floorId: Int) {
        if (floorId <= 0) return
        main.post { callbacks().onGululuFloor(floorId) }
    }

    @JavascriptInterface
    fun gululuVfx(kind: String?) {
        val value = kind?.trim().orEmpty()
        main.post { callbacks().onGululuVfx(value) }
    }

    @JavascriptInterface
    fun gululuBackground(url: String?) {
        val value = url?.trim().orEmpty()
        main.post { callbacks().onGululuBackground(value) }
    }

    @JavascriptInterface
    fun gululuMusic(url: String?, title: String?, auto: Boolean) {
        val value = url?.trim().orEmpty()
        if (!value.startsWith("https://")) return
        main.post { callbacks().onGululuMusic(value, title?.trim().orEmpty(), auto) }
    }

    @JavascriptInterface
    fun gululuMusicStop() {
        main.post { callbacks().onGululuMusicStop() }
    }

    @JavascriptInterface
    fun gululuSecret(title: String?, cipher: String?) {
        val c = cipher?.trim().orEmpty()
        if (c.isEmpty()) return
        main.post { callbacks().onGululuSecret(title?.trim().orEmpty(), c) }
    }

    @JavascriptInterface
    fun gululuClue(title: String?, password: String?) {
        val t = title?.trim().orEmpty()
        val p = password?.trim().orEmpty()
        if (t.isEmpty() || p.isEmpty()) return
        main.post { callbacks().onGululuClue(t, p) }
    }

    @JavascriptInterface
    fun gululuParagraphComments(paragraphId: String?) {
        val id = paragraphId?.trim().orEmpty()
        if (id.isEmpty()) return
        main.post { callbacks().onGululuParagraphComments(id) }
    }
}
