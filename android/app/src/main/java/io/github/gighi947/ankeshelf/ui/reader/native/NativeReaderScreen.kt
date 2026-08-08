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
import androidx.core.view.ViewCompat
import io.github.gighi947.ankeshelf.data.AnnotationStore
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.ngaHeaders
import io.github.gighi947.ankeshelf.ui.reader.extractReaderParts
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    annotations: AnnotationStore,
    container: AppContainer,
    readerSettings: SettingsData,
    onProgress: (chapterIndex: Int, textOffset: Int) -> Unit,
    onSettingsPatch: (SettingsPatch) -> Unit,
    onStatsTick: (seconds: Int, pagesFlipped: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var chapterIndex by remember(session.id) { mutableIntStateOf(initialChapter.coerceIn(0, session.chapters.lastIndex.coerceAtLeast(0))) }
    var doc by remember { mutableStateOf<ReaderDoc?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    var pageInfo by remember { mutableStateOf(Pair(0, 1)) }
    var scrollRatio by remember { mutableFloatStateOf(0f) }
    var lastOffset by remember { mutableIntStateOf(0) }
    var lightboxSrc by remember { mutableStateOf<String?>(null) }
    var pendingSeconds by remember { mutableIntStateOf(0) }
    var flippedPages by remember { mutableIntStateOf(0) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = androidx.activity.compose.LocalActivity.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val theme = remember(readerSettings, systemDark) { readerTheme(readerSettings, systemDark) }
    val ngaConfig = remember { container.ngaConfig.load() }
    val fg = remember(theme) {
        runCatching { Color(android.graphics.Color.parseColor(theme.text)) }.getOrDefault(Color.Black)
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

    // 章节解析放后台。
    LaunchedEffect(chapterIndex, session) {
        doc = null
        val d = withContext(Dispatchers.Default) {
            runCatching {
                ReaderHtmlModel.parse(
                    extractReaderParts(session.chapterText(chapterIndex).orEmpty()).body,
                )
            }.getOrNull()
        }
        doc = d
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

    val highlights = remember(doc, chapterIndex) {
        annotations.getHighlights(session.id)
            .filter { it.chapter_index == chapterIndex }
            .map { NativeHighlight(it.id, it.start_offset, it.end_offset, it.color) }
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
        onProgress(chapterIndex, lastOffset)
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
        val currentDoc = doc
        if (currentDoc == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载章节…", color = fg.copy(alpha = 0.7f))
            }
        } else {
            NativeChapterView(
                doc = currentDoc,
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
                bottomInsetPx = 0,
                initialOffset = if (chapterIndex == initialChapter) savedOffset else 0,
                highlights = highlights,
                imageBytes = ::imageBytes,
                callbacks = NativeReaderCallbacks(
                    onProgress = { offset ->
                        lastOffset = offset
                        onProgress(chapterIndex, offset)
                        if (readerSettings.pagination) {
                            scrollRatio = if (currentDoc.plainText.length > 0) {
                                offset.toFloat() / currentDoc.plainText.length
                            } else 0f
                        }
                    },
                    onPageChanged = { page, total, offset ->
                        lastOffset = offset
                        pageInfo = page to total
                        onProgress(chapterIndex, offset)
                    },
                    onImageLongPress = { lightboxSrc = it },
                    onHighlightTap = { },
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { saveProgress(); onBack() }) { Text("← 返回") }
                Text(
                    session.chapterTitle(chapterIndex),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { chapterIndex = (chapterIndex - 1).coerceAtLeast(0) }) { Text("上一章") }
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14)))
                    }) { Text("A-") }
                    TextButton(onClick = {
                        val next = THEME_CYCLE[(THEME_CYCLE.indexOf(readerSettings.theme) + 1) % THEME_CYCLE.size]
                        onSettingsPatch(SettingsPatch(theme = next))
                    }) { Text("主题") }
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28)))
                    }) { Text("A+") }
                    TextButton(onClick = {
                        chapterIndex = (chapterIndex + 1).coerceAtMost(session.chapters.lastIndex)
                    }) { Text("下一章") }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination))
                    }) {
                        Text(if (readerSettings.pagination) "分页" else "滚动")
                    }
                    Text(
                        if (readerSettings.pagination && pageInfo.second > 0) {
                            "第 ${pageInfo.first + 1} / ${pageInfo.second} 页"
                        } else {
                            "${(scrollRatio * 100).roundToInt()}%"
                        },
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
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

    DisposableEffect(Unit) {
        onDispose {
            runCatching { saveProgress() }
            runCatching { container.progress.flush() }
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
