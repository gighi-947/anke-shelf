package io.github.gighi947.ankeshelf.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gighi947.ankeshelf.BuildConfig
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.BookSession
import kotlin.math.roundToInt

/** 阅读器配色（与 Compose 色板同源；M4 将并入 PALETTES 全量移植）。 */
data class ReaderThemeColors(
    val background: String,
    val text: String,
    val accent: String,
)

fun readerTheme(name: String): ReaderThemeColors = when (name.lowercase()) {
    "dark" -> ReaderThemeColors("#171412", "#e9e2d8", "#e0b684")
    "sepia" -> ReaderThemeColors("#f4ecd8", "#3b3226", "#8b5a2b")
    else -> ReaderThemeColors("#ffffff", "#201a15", "#8b5a2b")
}

private val THEME_CYCLE = listOf("dark", "light", "sepia")

/** WebView JS 桥：章节滚动比例 → Kotlin 进度（text_offset 坐标）。 */
class ReaderBridge(
    private val onProgress: (chapterIndex: Int, ratio: Double) -> Unit,
) {
    @JavascriptInterface
    fun saveProgress(chapterIndex: Int, ratio: Double) {
        onProgress(chapterIndex, ratio.coerceIn(0.0, 1.0))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    session: BookSession,
    initialChapter: Int,
    savedOffset: Int,
    readerSettings: SettingsData,
    onProgress: (chapterIndex: Int, textOffset: Int) -> Unit,
    onSettingsPatch: (SettingsPatch) -> Unit,
    onBack: () -> Unit,
) {
    var chapterIndex by rememberSaveable(session.id) {
        mutableIntStateOf(initialChapter.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0)))
    }
    var barsVisible by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }

    val theme = remember(readerSettings.theme) { readerTheme(readerSettings.theme) }
    val parts = remember(session, chapterIndex) {
        extractReaderParts(session.chapterText(chapterIndex).orEmpty())
    }
    val plainLength = remember(session, chapterIndex) {
        TextExtractor.extractDomText(parts.body).length
    }
    val wrapperHtml = remember(parts, readerSettings, theme) {
        buildReaderHtml(parts, theme, readerSettings)
    }
    val initialRatio = remember(initialChapter, savedOffset, plainLength) {
        if (plainLength > 0) (savedOffset.toDouble() / plainLength).coerceIn(0.0, 1.0) else 0.0
    }

    val lenRef = remember { mutableIntStateOf(plainLength) }
    LaunchedEffect(plainLength) { lenRef.intValue = plainLength }

    val bridge = remember {
        ReaderBridge { idx, ratio ->
            val offset = (ratio * lenRef.intValue).roundToInt().coerceIn(0, lenRef.intValue)
            onProgress(idx, offset)
        }
    }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val loadedChapter = remember { mutableIntStateOf(-1) }

    fun saveNow(web: WebView?) {
        web?.evaluateJavascript(
            "(function(){var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);" +
                "try{AnkeReaderBridge.saveProgress($chapterIndex,r);}catch(e){}})();",
            null,
        )
    }

    fun changeChapter(delta: Int) {
        saveNow(webViewRef.value)
        val next = (chapterIndex + delta).coerceIn(0, session.chapters.lastIndex)
        if (next != chapterIndex) {
            chapterIndex = next
            showToc = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    settings.javaScriptEnabled = true
                    settings.setAllowFileAccess(false)
                    setBackgroundColor(Color.parseColor(theme.background))
                    addJavascriptInterface(bridge, "AnkeReaderBridge")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.d("AnkeShelf", "console: ${msg?.message()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            Log.e("AnkeShelf", "page error: ${error?.description} code=${error?.errorCode}")
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            val ratio = if (chapterIndex == initialChapter) initialRatio else 0.0
                            view.evaluateJavascript(
                                """(function(){
                                   var h=document.body.scrollHeight-window.innerHeight;
                                   window.scrollTo(0,h*$ratio);
                                   var last=0;
                                   window.addEventListener('scroll',function(){
                                     var now=Date.now(); if(now-last<1200) return; last=now;
                                     var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);
                                     try{AnkeReaderBridge.saveProgress($chapterIndex,r);}catch(e){}
                                   });
                                   window.addEventListener('pagehide',function(){
                                     var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);
                                     try{AnkeReaderBridge.saveProgress($chapterIndex,r);}catch(e){}
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
                                if (dx * dx + dy * dy < 50f * 50f) {
                                    val w = width
                                    when {
                                        ev.x < w / 3f -> changeChapter(-1)
                                        ev.x > 2 * w / 3f -> changeChapter(1)
                                        else -> barsVisible = !barsVisible
                                    }
                                }
                            }
                        }
                        false
                    }
                    webViewRef.value = this
                }
            },
            update = { view ->
                if (loadedChapter.intValue != chapterIndex) {
                    loadedChapter.intValue = chapterIndex
                    view.loadDataWithBaseURL(null, wrapperHtml, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

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
                Text(
                    text = session.chapterTitle(chapterIndex),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showToc = !showToc }) { Text("目录") }
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { changeChapter(-1) }) { Text("↑上一章") }
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
                TextButton(onClick = { changeChapter(1) }) { Text("下一章↓") }
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
        }
    }
}
