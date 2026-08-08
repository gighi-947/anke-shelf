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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gighi947.ankeshelf.BuildConfig
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.ngaHeaders
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Hybrid reader callbacks. Progress events carry the reporting chapter index so
 * stale async events from the previous page are attributed to the correct
 * chapter instead of being dropped or clamped with the wrong chapter length.
 */
data class WebViewReaderCallbacks(
    val onReady: () -> Unit = {},
    val onProgress: (chapter: Int, offset: Int) -> Unit = { _, _ -> },
    val onPageChanged: (chapter: Int, page: Int, total: Int, offset: Int) -> Unit = { _, _, _, _ -> },
    val onImageTap: (String) -> Unit = {},
    val onTapZone: (String) -> Unit = {},
    val onRequestChapter: (Int) -> Unit = {},
    val onChapterSwitch: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onFlush: () -> Unit = {},
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
    val loadSeq = remember { mutableIntStateOf(0) }
    val chapterRef = remember { mutableIntStateOf(chapterIndex) }
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
    val callbacksRef = rememberUpdatedState(callbacks)
    val sessionRef = rememberUpdatedState(session)
    val containerRef = rememberUpdatedState(container)
    val ngaConfig = remember { container.ngaConfig.load() }

    LaunchedEffect(chapterIndex, html) {
        val web = webViewRef.value
        val old = chapterRef.intValue
        if (web != null && old != chapterIndex) {
            // Desktop loadChapter: capture the old chapter's exact offset before
            // the new page replaces it. Run the latch off the main thread so the
            // evaluateJavascript callback (main thread) can complete. The WebView
            // call itself must be posted to the main thread (checkThread).
            if (pageReady.value) {
                val captured = withContext(Dispatchers.Default) {
                    val latch = CountDownLatch(1)
                    var value = 0
                    web.post {
                        web.evaluateJavascript(
                            "(function(){try{return AnkeReader.currentOffset();}catch(e){return 0;}})()",
                            ValueCallback { v ->
                                value = v?.toDoubleOrNull()?.toInt() ?: 0
                                latch.countDown()
                            },
                        )
                    }
                    if (latch.await(300, TimeUnit.MILLISECONDS)) value else 0
                }
                if (captured > 0) callbacksRef.value.onProgress(old, captured)
            }
            callbacksRef.value.onChapterSwitch(old, chapterIndex)
        }
        chapterRef.intValue = chapterIndex
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

    val bridge = remember {
        LiteBridge(callbacks = { callbacksRef.value })
    }

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
                                    WebResourceResponse(mime, null, resp.body.byteStream())
                                }
                            }.getOrNull()
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
                        view.evaluateJavascript(
                            "AnkeReader.init({chapterIndex:${chapterRef.intValue},paged:${pagedRef.value}," +
                                "offset:$initialOffset,margin:${s.marginPx},gap:${s.gapPx}," +
                                "pageWidth:${s.pageWidth},fontSize:${s.fontSize}," +
                                "lineHeight:${s.lineHeight},dualPage:${s.dualPage}," +
                                "autoDual:${s.autoDual}," +
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
                            val isTap = dx * dx + dy * dy < 50f * 50f
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
                                isTap && ev.x >= width / 3f && ev.x <= 2 * width / 3f -> {
                                    callbacksRef.value.onTapZone("middle")
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
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose {
            val web = webViewRef.value
            if (web != null) {
                val idx = chapterRef.intValue
                // Read the final offset before teardown; delay destroy so the
                // async value callback is delivered and the tracker flushes.
                web.evaluateJavascript(
                    "(function(){try{return AnkeReader.currentOffset();}catch(e){return 0;}})()",
                    ValueCallback { v ->
                        val o = v?.toDoubleOrNull()?.toInt() ?: 0
                        if (o > 0) callbacksRef.value.onProgress(idx, o)
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
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun log(msg: String) {
        Log.d("AnkeShelf", msg)
    }

    @JavascriptInterface
    fun saveProgress(idx: Int, value: Double, isOffset: Boolean) {
        if (!isOffset) return
        val offset = value.toInt()
        if (offset <= 0) return
        main.post { callbacks().onProgress(idx, offset) }
    }

    @JavascriptInterface
    fun pageChanged(idx: Int, page: Int, total: Int, offset: Int) {
        if (page < 0 || total <= 0) return
        main.post { callbacks().onPageChanged(idx, page, total, offset.coerceAtLeast(0)) }
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
    fun onReady() {
        main.post { callbacks().onReady() }
    }
}
