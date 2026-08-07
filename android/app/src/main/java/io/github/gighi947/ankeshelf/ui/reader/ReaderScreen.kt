package io.github.gighi947.ankeshelf.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
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
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.BookRepository
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
    onBack: () -> Unit,
) {
    var chapterIndex by rememberSaveable(session.id) {
        mutableIntStateOf(initialChapter.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0)))
    }
    val chapterText = remember(session, chapterIndex) {
        session.chapterText(chapterIndex).orEmpty()
    }
    val plainLength = remember(session, chapterIndex) {
        TextExtractor.extractDomText(chapterText).length
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
    val theme = remember(readerSettings.theme) { readerTheme(readerSettings.theme) }

    fun saveNow(web: WebView?) {
        web?.evaluateJavascript(
            "(function(){var r=window.scrollY/Math.max(1,document.body.scrollHeight-window.innerHeight);" +
                "try{AnkeReaderBridge.saveProgress($chapterIndex,r);}catch(e){}})();",
            null,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
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
            IconButton(onClick = {
                saveNow(webViewRef.value)
                if (chapterIndex > 0) chapterIndex--
            }) { Text("↑") }
            IconButton(onClick = {
                saveNow(webViewRef.value)
                if (chapterIndex < session.chapters.lastIndex) chapterIndex++
            }) { Text("↓") }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.setAllowFileAccess(false)
                        setBackgroundColor(Color.parseColor(theme.background))
                        addJavascriptInterface(bridge, "AnkeReaderBridge")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                val css =
                                    "body{background:${theme.background}!important;" +
                                        "color:${theme.text}!important;" +
                                        "font-size:${readerSettings.font_size}px!important;" +
                                        "line-height:${readerSettings.line_height}!important;}" +
                                        "a{color:${theme.accent}!important}img{max-width:100%!important}"
                                val cssLiteral = org.json.JSONObject.quote(css)
                                val ratio = if (chapterIndex == initialChapter) initialRatio else 0.0
                                view.evaluateJavascript(
                                    """(function(){
                                       var s=document.createElement('style');
                                       s.textContent=$cssLiteral;
                                       document.head.appendChild(s);
                                       var h=document.body.scrollHeight-window.innerHeight;
                                       window.scrollTo(0,h*$ratio);
                                       var last=0;
                                       window.addEventListener('scroll',function(){
                                         var now=Date.now(); if(now-last<1500) return; last=now;
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
                        webViewRef.value = this
                    }
                },
                update = { view ->
                    if (loadedChapter.intValue != chapterIndex) {
                        loadedChapter.intValue = chapterIndex
                        view.loadDataWithBaseURL(null, chapterText, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.removeJavascriptInterface("AnkeReaderBridge")
        }
    }
}
