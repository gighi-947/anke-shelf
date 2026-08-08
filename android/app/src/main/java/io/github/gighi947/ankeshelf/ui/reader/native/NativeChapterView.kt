package io.github.gighi947.ankeshelf.ui.reader.native

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import io.github.gighi947.ankeshelf.ui.reader.PagedLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import java.io.File

/** 高亮（id/起始/结束/色键，与 AnnotationStore 字段一致）。 */
data class NativeHighlight(val id: String, val start: Int, val end: Int, val color: String)

/** 原生阅读器回调。 */
data class NativeReaderCallbacks(
    val onProgress: (Int) -> Unit,
    val onPageChanged: (page: Int, total: Int, offset: Int) -> Unit,
    val onImageLongPress: (String) -> Unit,
    val onHighlightTap: (String) -> Unit,
    val onTapZone: (String) -> Unit = {},
    val onRequestChapter: (Int) -> Unit = {},
)

/** 内置字体单例缓存（26MB 只加载一次）。 */
object NativeFonts {
    @Volatile
    var lxgw: Typeface? = null
    private val customCache = HashMap<String, Typeface?>()

    fun ensure(context: android.content.Context) {
        if (lxgw == null) {
            lxgw = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/LXGWWenKai-Regular.ttf")
            }.getOrNull()
        }
    }

    /** 导入字体（fontsDir 下），缓存避免重复解析。 */
    fun custom(context: android.content.Context, name: String, dir: File): Typeface? {
        if (name.isBlank()) return lxgw
        return customCache.getOrPut(name) {
            runCatching { Typeface.createFromFile(File(dir, name)) }.getOrNull()
        }
    }
}

private val ReaderThemeColors.bgColor: Color get() = parseHtmlColor(background) ?: Color.White
private val ReaderThemeColors.fgColor: Color get() = parseHtmlColor(text) ?: Color(0xFF201A15)
private val ReaderThemeColors.accentColor: Color get() = parseHtmlColor(accent) ?: Color(0xFF2E86AB)

/** 新版 TextStyle 拆成 spanStyle + paragraphStyle（spanStyle 属性 internal），
 *  这里自己持有两份，派生样式时合并。 */
private class ReaderTextStyle(val span: SpanStyle, val para: ParagraphStyle) {
    fun style(extra: SpanStyle = SpanStyle()): TextStyle =
        TextStyle(
            color = span.color,
            fontSize = span.fontSize,
            fontWeight = span.fontWeight,
            fontStyle = span.fontStyle,
            fontFamily = span.fontFamily,
            lineHeight = para.lineHeight,
        ).merge(extra)

    val fontSize: Float get() = span.fontSize.value
}

@Composable
fun NativeChapterView(
    doc: ReaderDoc,
    paged: Boolean,
    theme: ReaderThemeColors,
    fontSize: Int,
    lineHeight: Double,
    pageWidth: Double,
    marginPx: Int,
    gapPx: Int,
    dualPage: Boolean,
    autoDual: Boolean,
    topInsetPx: Int,
    bottomInsetPx: Int,
    initialOffset: Int,
    highlights: List<NativeHighlight>,
    imageBytes: suspend (String) -> ByteArray?,
    customFont: String = "",
    fontsDir: File? = null,
    callbacks: NativeReaderCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    NativeFonts.ensure(context)
    val fontFamily = remember(customFont, fontsDir) {
        when {
            customFont == "system" -> FontFamily.SansSerif
            customFont.isBlank() || customFont.startsWith("sys:") ->
                NativeFonts.lxgw?.let { FontFamily(it) } ?: FontFamily.SansSerif
            else -> NativeFonts.custom(context, customFont, fontsDir ?: File(context.filesDir, "AnkeShelf/fonts"))
                ?.let { FontFamily(it) } ?: FontFamily.SansSerif
        }
    }
    val baseStyle = remember(fontSize, lineHeight, theme) {
        ReaderTextStyle(
            span = SpanStyle(
                fontFamily = fontFamily ?: FontFamily.SansSerif,
                fontSize = fontSize.sp,
                color = theme.fgColor,
            ),
            para = ParagraphStyle(lineHeight = (fontSize * lineHeight).sp),
        )
    }
    val highlightColors = remember {
        mapOf(
            "yellow" to Color(0x66FDD835), "green" to Color(0x6666BB6A),
            "blue" to Color(0x6642A5F5), "pink" to Color(0x66EC407A),
            "purple" to Color(0x66AB47BC), "cyan" to Color(0x6626C6DA),
        )
    }

    if (paged) {
        NativePagedView(
            doc = doc,
            theme = theme,
            baseStyle = baseStyle,
            fontSize = fontSize,
            lineHeight = lineHeight,
            pageWidth = pageWidth,
            marginPx = marginPx,
            gapPx = gapPx,
            dualPage = dualPage,
            autoDual = autoDual,
            topInsetPx = topInsetPx,
            bottomInsetPx = bottomInsetPx,
            initialOffset = initialOffset,
            highlights = highlights,
            highlightColors = highlightColors,
            imageBytes = imageBytes,
            customFont = customFont,
            fontsDir = fontsDir,
            callbacks = callbacks,
            modifier = modifier,
        )
    } else {
        NativeScrollView(
            doc = doc,
            theme = theme,
            baseStyle = baseStyle,
            highlights = highlights,
            highlightColors = highlightColors,
            initialOffset = initialOffset,
            imageBytes = imageBytes,
            customFont = customFont,
            fontsDir = fontsDir,
            pageWidth = pageWidth,
            fontSize = fontSize,
            topInsetPx = topInsetPx,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

/* ---------------- 滚动模式 ---------------- */

@Composable
private fun NativeScrollView(
    doc: ReaderDoc,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
    initialOffset: Int,
    imageBytes: suspend (String) -> ByteArray?,
    customFont: String,
    fontsDir: File?,
    pageWidth: Double,
    fontSize: Int,
    topInsetPx: Int,
    callbacks: NativeReaderCallbacks,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val len = doc.plainText.length.coerceAtLeast(1)
    val maxWidth = with(density) { (46f * pageWidth.toFloat() * fontSize).sp.toDp() }

    LaunchedEffect(Unit) {
        // 首帧布局后再恢复（maxValue 才有效），对齐桌面 scrollToOffset。
        delay(50)
        val ratio = initialOffset.toFloat() / len
        val target = (ratio * scroll.maxValue).roundToInt().coerceIn(0, scroll.maxValue)
        scroll.scrollTo(target)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { scroll.value }
            .debounce(450)
            .collect { v ->
                val ratio = if (scroll.maxValue > 0) v.toFloat() / scroll.maxValue else 0f
                callbacks.onProgress((ratio * len).roundToInt().coerceIn(0, len))
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bgColor)
            .verticalScroll(scroll)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width
                    // 滚动模式：点中间唤出/收起控制条；左右两侧不换章（移动端筛选结果）。
                    if (pos.x >= w * 0.25f && pos.x <= w * 0.75f) {
                        callbacks.onTapZone("middle")
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .padding(horizontal = 16.dp)
                .padding(top = (18 + topInsetPx).dp, bottom = 24.dp),
        ) {
            doc.blocks.forEachIndexed { bi, block ->
                NativeBlock(
                    block = block,
                    theme = theme,
                    baseStyle = baseStyle,
                    scrollMode = true,
                    highlights = highlights,
                    highlightColors = highlightColors,
                    imageBytes = imageBytes,
                    onImageLongPress = callbacks.onImageLongPress,
                )
                if (bi < doc.blocks.lastIndex) Spacer(theme)
            }
            // 滚动模式底部换章按钮（对齐桌面 chapter-nav-row）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { callbacks.onRequestChapter(-1) }) { Text("← 上一章") }
                TextButton(onClick = { callbacks.onRequestChapter(1) }) { Text("下一章 →") }
            }
        }
    }
}

@Composable
private fun Spacer(theme: ReaderThemeColors) {
    Box(Modifier.fillMaxWidth().height(4.dp))
}

/* ---------------- 分页模式 ---------------- */

private class Frag {
    var ann: AnnotatedString? = null
    var style: TextStyle? = null
    var heightPx: Int = 0
    var image: String? = null
    var table: ReaderBlock.Table? = null
    var floorCard: Boolean = false
    var head: String? = null
    var headColor: Color? = null
    var topPad: Int = 0
    var bottomPad: Int = 0
    var cardColor: Color? = null
    var borderColor: Color? = null
    var accentColor: Color? = null
    var plainStart: Int = -1
}

private class NativePage {
    /** 每屏的列（双页=2，单页=1），按桌面几何从第一列填到最后一列。 */
    val columns = mutableListOf<MutableList<Frag>>()
    var startOffset: Int = 0
    var startBlock: Int = 0
    var marginPx: Int = 0
    var gapPx: Int = 0
    var topInsetPx: Int = 0
    var bottomInsetPx: Int = 0
}

@Composable
private fun NativePagedView(
    doc: ReaderDoc,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    fontSize: Int,
    lineHeight: Double,
    pageWidth: Double,
    marginPx: Int,
    gapPx: Int,
    dualPage: Boolean,
    autoDual: Boolean,
    topInsetPx: Int,
    bottomInsetPx: Int,
    initialOffset: Int,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
    imageBytes: suspend (String) -> ByteArray?,
    customFont: String,
    fontsDir: File?,
    callbacks: NativeReaderCallbacks,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<NativePage>?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = 0) { pages?.size ?: 1 }
    // 用视图实际宽高分页（分页几何与桌面一致）。
    val viewSize = remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bgColor)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width
                    when {
                        pos.x < w / 3f ->
                            scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                        pos.x > 2 * w / 3f ->
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1).coerceAtMost((pagerState.pageCount - 1).coerceAtLeast(0)),
                                )
                            }
                        else -> callbacks.onTapZone("middle")
                    }
                }
            }
            .onSizeChanged { size -> viewSize.value = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()) },
    ) {
        val size = viewSize.value
        if (size.width > 0f && size.height > 0f) {
            val key = remember(doc, fontSize, lineHeight, pageWidth, marginPx, gapPx, dualPage, autoDual, size, topInsetPx, bottomInsetPx) {
                listOf(doc.plainText.length, fontSize, lineHeight, pageWidth, marginPx, gapPx, dualPage, autoDual, size.width.roundToInt(), size.height.roundToInt(), topInsetPx, bottomInsetPx)
            }
            LaunchedEffect(key) {
                val result = paginate(
                    doc = doc,
                    textMeasurer = textMeasurer,
                    baseStyle = baseStyle,
                    fwPx = size.width.roundToInt(),
                    fhPx = size.height.roundToInt(),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    pageWidth = pageWidth,
                    marginPx = marginPx,
                    gapPx = gapPx,
                    dualPage = dualPage,
                    autoDual = autoDual,
                    topInsetPx = topInsetPx,
                    bottomInsetPx = bottomInsetPx,
                    density = density,
                    textColor = theme.fgColor,
                    mutedColor = theme.fgColor.copy(alpha = 0.7f),
                    accentColor = theme.accentColor,
                )
                pages = result
                if (result.isNotEmpty()) {
                    var idx = result.indexOfLast { it.startOffset <= initialOffset }
                    if (idx < 0) idx = 0
                    currentPage = idx
                    // 等 pager 页数同步后再定位，避免 pageCount 仍为旧值导致越界崩溃。
                    snapshotFlow { pagerState.pageCount }
                        .first { it == result.size }
                    pagerState.scrollToPage(idx)
                }
            }

            val pageList = pages
            if (pageList == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在排版…", style = baseStyle.style(SpanStyle(color = theme.fgColor.copy(alpha = 0.7f))))
                }
            } else if (pageList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("本章为空", style = baseStyle.style(SpanStyle(color = theme.fgColor.copy(alpha = 0.7f))))
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    NativePageContent(
                        page = pageList[page],
                        theme = theme,
                        baseStyle = baseStyle,
                        highlights = highlights,
                        highlightColors = highlightColors,
                        imageBytes = imageBytes,
                        onImageLongPress = callbacks.onImageLongPress,
                    )
                }
                LaunchedEffect(pagerState.currentPage, pageList.size) {
                    // 翻页后上报页首偏移（桌面 page-start 语义）。
                    val idx = pagerState.currentPage
                    val p = pageList.getOrNull(idx) ?: return@LaunchedEffect
                    currentPage = idx
                    callbacks.onPageChanged(idx, pageList.size, p.startOffset)
                    callbacks.onProgress(p.startOffset)
                }
            }
        }
    }
}

private fun paginate(
    doc: ReaderDoc,
    textMeasurer: TextMeasurer,
    baseStyle: ReaderTextStyle,
    fwPx: Int,
    fhPx: Int,
    fontSize: Int,
    lineHeight: Double,
    pageWidth: Double,
    marginPx: Int,
    gapPx: Int,
    dualPage: Boolean,
    autoDual: Boolean,
    topInsetPx: Int,
    bottomInsetPx: Int,
    density: androidx.compose.ui.unit.Density,
    textColor: Color,
    mutedColor: Color,
    accentColor: Color,
): List<NativePage> {
    val g = PagedLayout.geometry(
        fw = fwPx, fh = fhPx,
        paged = true, dualPage = dualPage, autoDual = autoDual,
        margin = marginPx, gap = gapPx,
        pageWidth = pageWidth, fontSize = fontSize,
    )
    val colW = g.colW.toInt().coerceAtLeast(120)
    val pageH = (fhPx - topInsetPx - bottomInsetPx).coerceAtLeast(240)
    val colCount = if (g.dual) 2 else 1
    val pages = mutableListOf<NativePage>()
    var page = NativePage()
    page.marginPx = g.margin.toInt()
    page.gapPx = g.gap.toInt()
    page.topInsetPx = topInsetPx
    page.bottomInsetPx = bottomInsetPx
    var colIndex = 0
    var used = 0
    var plainCursor = 0

    fun ensureCols() {
        while (page.columns.size <= colIndex) page.columns.add(mutableListOf())
    }

    fun addFrag(f: Frag) {
        ensureCols()
        if (used > 0 && used + f.heightPx > pageH) {
            if (colIndex < colCount - 1) {
                colIndex++
                used = 0
                ensureCols()
            } else {
                pages.add(page)
                page = NativePage()
                page.marginPx = g.margin.toInt()
                page.gapPx = g.gap.toInt()
                page.topInsetPx = topInsetPx
                page.bottomInsetPx = bottomInsetPx
                page.startOffset = plainCursor
                colIndex = 0
                used = 0
                ensureCols()
            }
        }
        if (f.heightPx > pageH) f.heightPx = pageH
        page.columns[colIndex].add(f)
        used += f.heightPx
    }

    fun measureLines(ann: AnnotatedString, style: TextStyle, heightPx: Int): List<Frag> {
        val layout = textMeasurer.measure(
            text = ann,
            style = style,
            constraints = Constraints(maxWidth = colW),
        )
        val out = mutableListOf<Frag>()
        val lines = layout.lineCount
        for (i in 0 until lines) {
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i, visibleEnd = true)
            if (end <= start) continue
            val f = Frag()
            f.ann = ann.subSequence(start, end)
            f.style = style
            f.heightPx = heightPx
            out.add(f)
        }
        return out
    }

    fun blockText(b: ReaderBlock): String {
        val sb = StringBuilder()
        fun walk(x: ReaderBlock) {
            when (x) {
                is ReaderBlock.Paragraph -> x.spans.forEach { sb.append(it.text).append(' ') }
                is ReaderBlock.Heading -> x.spans.forEach { sb.append(it.text).append(' ') }
                is ReaderBlock.Quote -> x.body.forEach { walk(it) }
                is ReaderBlock.Dice -> sb.append(x.text).append(' ')
                is ReaderBlock.Comment -> x.spans.forEach { sb.append(it.text).append(' ') }
                is ReaderBlock.Floor -> x.body.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(b)
        return sb.toString()
    }

    fun spansAnn(spans: List<ReaderSpan>, base: TextStyle, defaultColor: Color): AnnotatedString =
        buildAnnotatedString {
            for (s in spans) {
                val c = parseHtmlColor(s.color) ?: defaultColor
                val style = SpanStyle(
                    color = c,
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                )
                append(AnnotatedString(s.text, style))
            }
        }

    fun pushTextBlock(block: ReaderBlock, blockIdx: Int, plainStart: Int, padTop: Int, padBottom: Int, card: Boolean, border: Color?, accent: Color?, bg: Color?) {
        val ann = when (block) {
            is ReaderBlock.Paragraph -> spansAnn(block.spans, baseStyle.style(), textColor)
            is ReaderBlock.Heading -> spansAnn(block.spans, baseStyle.style(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseStyle.fontSize * 1.1f).sp)), textColor)
            is ReaderBlock.Dice -> buildAnnotatedString { append(AnnotatedString(block.text, SpanStyle(color = Color(0xFFB8860B), fontWeight = FontWeight.Bold))) }
            is ReaderBlock.Comment -> spansAnn(block.spans, baseStyle.style(SpanStyle(fontSize = (baseStyle.fontSize * 0.92f).sp)), textColor)
            else -> buildAnnotatedString { append(blockText(block)) }
        }
        val lhPx = baseStyle.fontSize * lineHeight.toFloat() * density.density
        val lines = measureLines(ann, baseStyle.style(), lhPx.roundToInt())
        if (lines.isEmpty()) return
        lines.forEachIndexed { i, frag ->
            frag.plainStart = plainStart
            frag.floorCard = card
            frag.borderColor = border
            frag.accentColor = accent
            frag.cardColor = bg
            frag.topPad = if (i == 0) padTop else 0
            frag.bottomPad = if (i == lines.lastIndex) padBottom else 0
            addFrag(frag)
        }
    }

    doc.blocks.forEachIndexed { bi, block ->
        plainCursor = doc.blockOffsets[bi]
        page.startOffset = plainCursor
        page.startBlock = bi
        when (block) {
            is ReaderBlock.Floor -> {
                // 楼层卡片：头部一行 + 正文块；跨页时每片断都带卡片边框（对齐桌面 break-inside:auto）。
                val border = accentColor.copy(alpha = 0.25f)
                val headFrag = Frag()
                headFrag.head = "${block.lou}楼 · ${block.likes}赞 · ${block.username}(${block.userId}) · ${block.time}"
                headFrag.headColor = mutedColor
                headFrag.heightPx = (16 * density.density).roundToInt()
                headFrag.floorCard = true
                headFrag.borderColor = border
                headFrag.accentColor = accentColor
                headFrag.cardColor = Color.Transparent
                headFrag.topPad = (12 * density.density).roundToInt()
                headFrag.plainStart = plainCursor
                addFrag(headFrag)
                used += (6 * density.density).roundToInt()
                if (block.body.isEmpty()) {
                    used += (12 * density.density).roundToInt()
                } else {
                    block.body.forEach { sub ->
                        when (sub) {
                            is ReaderBlock.Paragraph, is ReaderBlock.Heading, is ReaderBlock.Dice, is ReaderBlock.Comment ->
                                pushTextBlock(sub, bi, plainCursor, 0, 0, true, border, accentColor, null)
                            is ReaderBlock.Quote -> {
                                val inner = mutableListOf<ReaderBlock>()
                                inner.addAll(sub.body)
                                val pad = (6 * density.density).roundToInt()
                                pushTextBlock(ReaderBlock.Paragraph(inner.flatMap { it.spansOrEmpty() }), bi, plainCursor, pad, pad, false, null, null, textColor.copy(alpha = 0.05f))
                            }
                            is ReaderBlock.Image -> {
                                val f = Frag()
                                f.image = sub.src
                                f.heightPx = minOf((220 * density.density).roundToInt(), (pageH * 0.6f).roundToInt())
                                f.floorCard = true
                                f.borderColor = border
                                f.accentColor = accentColor
                                addFrag(f)
                            }
                            is ReaderBlock.Table -> {
                                val f = Frag()
                                f.table = sub
                                f.heightPx = (minOf(sub.rows.size * 30, 260) * density.density).roundToInt()
                                f.floorCard = true
                                f.borderColor = border
                                f.accentColor = accentColor
                                addFrag(f)
                            }
                            else -> Unit
                        }
                    }
                    used += (12 * density.density).roundToInt()
                }
            }
            is ReaderBlock.Paragraph, is ReaderBlock.Heading, is ReaderBlock.Dice, is ReaderBlock.Comment ->
                pushTextBlock(block, bi, plainCursor, 0, 0, false, null, null, null)
            is ReaderBlock.Quote -> {
                val pad = (10 * density.density).roundToInt()
                pushTextBlock(ReaderBlock.Paragraph(block.body.flatMap { it.spansOrEmpty() }), bi, plainCursor, pad, pad, false, null, null, textColor.copy(alpha = 0.05f))
            }
            is ReaderBlock.Image -> {
                val f = Frag()
                f.image = block.src
                f.heightPx = minOf((260 * density.density).roundToInt(), (pageH * 0.6f).roundToInt())
                addFrag(f)
            }
            is ReaderBlock.Table -> {
                val f = Frag()
                f.table = block
                f.heightPx = (minOf(block.rows.size * 30, 260) * density.density).roundToInt()
                addFrag(f)
            }
            is ReaderBlock.Blank -> {
                val f = Frag()
                f.heightPx = (8 * density.density).roundToInt()
                addFrag(f)
            }
        }
    }
    if (page.columns.any { it.isNotEmpty() }) pages.add(page)
    return pages
}

private fun ReaderBlock.spansOrEmpty(): List<ReaderSpan> = when (this) {
    is ReaderBlock.Paragraph -> spans
    is ReaderBlock.Heading -> spans
    is ReaderBlock.Comment -> spans
    is ReaderBlock.Dice -> listOf(ReaderSpan(text))
    else -> emptyList()
}

/* ---------------- 渲染片段 ---------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NativePageContent(
    page: NativePage,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
    imageBytes: suspend (String) -> ByteArray?,
    onImageLongPress: (String) -> Unit,
) {
    // 双页 = 一屏两列（对齐桌面 PAGINATION_OVERRIDE：列宽 (fw-2P-G)/2，间距 G，边距 P）。
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = page.marginPx.dp)
            .padding(top = page.topInsetPx.dp, bottom = page.bottomInsetPx.dp),
        horizontalArrangement = Arrangement.spacedBy(page.gapPx.dp),
    ) {
        page.columns.forEach { col ->
            Column(Modifier.weight(1f)) {
                col.forEach { frag ->
                    RenderFrag(frag, theme, baseStyle, highlights, highlightColors, imageBytes, onImageLongPress)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderFrag(
    frag: Frag,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
    imageBytes: suspend (String) -> ByteArray?,
    onImageLongPress: (String) -> Unit,
) {
    val content: @Composable () -> Unit = {
        when {
            frag.image != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(frag.heightPx.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NativeImage(frag.image!!, theme, imageBytes, onImageLongPress)
                }
            }
            frag.table != null -> NativeTable(frag.table!!, theme, baseStyle, Modifier.height(frag.heightPx.dp))
            frag.head != null -> {
                Text(
                    frag.head!!,
                    style = baseStyle.style(SpanStyle(
                        fontSize = (baseStyle.fontSize * 0.82f).sp,
                        color = frag.headColor ?: theme.fgColor.copy(alpha = 0.7f),
                    )),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(
                    color = theme.fgColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 3.dp, bottom = 5.dp),
                )
            }
            frag.ann != null -> {
                val ann = applyHighlights(frag.ann!!, frag.plainStart, highlights, highlightColors)
                Text(
                    text = ann,
                    style = frag.style ?: baseStyle.style(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
    if (frag.floorCard) {
        // 楼层卡片碎片：每片断都带边框 + 左侧主题色条（对齐桌面 break-inside:auto 的碎片化边框）。
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .border(1.dp, frag.borderColor ?: Color.Transparent, MaterialTheme.shapes.small),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(frag.accentColor ?: Color.Transparent),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(
                        start = 10.dp,
                        end = 10.dp,
                        top = frag.topPad.dp,
                        bottom = frag.bottomPad.dp,
                    ),
            ) {
                content()
            }
        }
    } else {
        content()
    }
}

@Composable
private fun NativeBlock(
    block: ReaderBlock,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    scrollMode: Boolean,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
    imageBytes: suspend (String) -> ByteArray?,
    onImageLongPress: (String) -> Unit,
) {
    when (block) {
        is ReaderBlock.Floor -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(theme.bgColor),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(theme.accentColor.copy(alpha = 0.35f)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, theme.accentColor.copy(alpha = 0.25f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "${block.lou}楼 · ${block.likes}赞 · ${block.username}(${block.userId}) · ${block.time}",
                        style = baseStyle.style(SpanStyle(
                            fontSize = (baseStyle.fontSize * 0.82f).sp,
                            color = theme.fgColor.copy(alpha = 0.7f),
                        )),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider(
                        color = theme.fgColor.copy(alpha = 0.15f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    )
                    block.body.forEach { sub ->
                        NativeBlock(sub, theme, baseStyle, scrollMode, highlights, highlightColors, imageBytes, onImageLongPress)
                    }
                }
            }
        }
        is ReaderBlock.Paragraph -> NativeTextBlock(block.spans, baseStyle.style(), theme, highlights, highlightColors)
        is ReaderBlock.Heading -> NativeTextBlock(
            block.spans,
            baseStyle.style(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseStyle.fontSize * 1.15f).sp)),
            theme,
            highlights,
            highlightColors,
        )
        is ReaderBlock.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(theme.accentColor.copy(alpha = 0.45f)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .background(theme.bgColor.copy(alpha = 0.5f))
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (block.title.isNotBlank()) {
                    Text(block.title, style = baseStyle.style(SpanStyle(fontWeight = FontWeight.Medium, fontSize = (baseStyle.fontSize * 0.9f).sp)))
                }
                block.body.forEach { NativeBlock(it, theme, baseStyle, scrollMode, highlights, highlightColors, imageBytes, onImageLongPress) }
            }
        }
        is ReaderBlock.Dice -> Text(
            block.text,
            style = baseStyle.style(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFB8860B))),
        )
        is ReaderBlock.Comment -> Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
                .border(1.dp, theme.accentColor.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                .background(theme.bgColor.copy(alpha = 0.5f))
                .padding(8.dp),
        ) {
            if (block.lou > 0 || block.username.isNotBlank()) {
                Text(
                    "${if (block.lou > 0) "${block.lou}楼 · " else ""}${block.username}",
                    style = baseStyle.style(SpanStyle(
                        fontSize = (baseStyle.fontSize * 0.8f).sp,
                        color = theme.fgColor.copy(alpha = 0.7f),
                    )),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                block.spans.joinToString("") { it.text },
                style = baseStyle.style(SpanStyle(
                    fontSize = (baseStyle.fontSize * 0.92f).sp,
                    color = theme.fgColor.copy(alpha = 0.9f),
                )),
            )
        }
        is ReaderBlock.Image -> NativeImage(block.src, theme, imageBytes, onImageLongPress)
        is ReaderBlock.Table -> NativeTable(block, theme, baseStyle, Modifier.heightIn(max = 320.dp))
        is ReaderBlock.Blank -> Box(Modifier.height(8.dp))
    }
}

@Composable
private fun NativeTextBlock(
    spans: List<ReaderSpan>,
    style: TextStyle,
    theme: ReaderThemeColors,
    highlights: List<NativeHighlight>,
    highlightColors: Map<String, Color>,
) {
    val ann = buildAnnotatedString {
        for (s in spans) {
            val c = parseHtmlColor(s.color) ?: theme.fgColor
            append(
                AnnotatedString(
                    s.text,
                    SpanStyle(
                        color = c,
                        fontWeight = if (s.bold) FontWeight.Bold else null,
                        fontStyle = if (s.italic) FontStyle.Italic else null,
                    ),
                ),
            )
        }
    }
    Text(text = ann, style = style, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun NativeImage(
    src: String,
    theme: ReaderThemeColors,
    imageBytes: suspend (String) -> ByteArray?,
    onLongPress: (String) -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, src) {
        value = withContext(Dispatchers.IO) {
            imageBytes(src)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(theme.bgColor.copy(alpha = 0.3f))
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(src) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("加载中…", style = TextStyle(color = theme.fgColor.copy(alpha = 0.6f), fontSize = 14.sp))
        }
    }
}

@Composable
private fun NativeTable(
    table: ReaderBlock.Table,
    theme: ReaderThemeColors,
    baseStyle: ReaderTextStyle,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
    ) {
        Column {
            table.rows.forEach { row ->
                Row {
                    row.cells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .width(160.dp)
                                .border(0.5.dp, theme.fgColor.copy(alpha = 0.2f))
                                .padding(4.dp),
                        ) {
                            Text(
                                cell.joinToString("") { it.text },
                                style = baseStyle.style(SpanStyle(fontSize = (baseStyle.fontSize * 0.9f).sp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 工具 ---------------- */

private fun applyHighlights(
    ann: AnnotatedString,
    plainStart: Int,
    highlights: List<NativeHighlight>,
    colors: Map<String, Color>,
): AnnotatedString {
    if (highlights.isEmpty() || plainStart < 0) return ann
    val out = AnnotatedString.Builder(ann)
    // 粗略：整行命中高亮区间时加背景（后续按 span 精修）。
    highlights.forEach { h ->
        val bg = colors[h.color] ?: return@forEach
        if (plainStart in h.start until h.end) {
            out.addStyle(
                SpanStyle(background = bg),
                0,
                out.length,
            )
        }
    }
    return out.toAnnotatedString()
}

/** HTML 颜色 → Compose Color（#hex / rgb() / 常见命名色）；失败返回 null 用主题色。 */
private fun parseHtmlColor(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    if (s.startsWith("#")) {
        val hex = s.removePrefix("#")
        return when (hex.length) {
            3 -> Color(
                red = hex[0].digitToIntOrNull(16)?.let { it * 17 } ?: 255,
                green = hex[1].digitToIntOrNull(16)?.let { it * 17 } ?: 255,
                blue = hex[2].digitToIntOrNull(16)?.let { it * 17 } ?: 255,
            )
            6 -> runCatching { Color(("FF" + hex).toLong(16)) }.getOrNull()
            8 -> runCatching { Color(hex.toLong(16)) }.getOrNull()
            else -> null
        }
    }
    if (s.startsWith("rgb(")) {
        val parts = s.removePrefix("rgb(").removeSuffix(")")
            .split(",").map { it.trim().toIntOrNull() ?: return null }
        if (parts.size >= 3) {
            return Color(red = parts[0], green = parts[1], blue = parts[2])
        }
    }
    return namedColor(s)
}

private val NAMED_COLORS = mapOf(
    "red" to 0xFFFF0000, "blue" to 0xFF0000FF, "green" to 0xFF008000,
    "white" to 0xFFFFFFFF, "black" to 0xFF000000, "gray" to 0xFF808080,
    "grey" to 0xFF808080, "silver" to 0xFFC0C0C0, "orange" to 0xFFFFA500,
    "purple" to 0xFF800080, "teal" to 0xFF008080, "navy" to 0xFF000080,
    "maroon" to 0xFF800000, "olive" to 0xFF808000, "lime" to 0xFF00FF00,
    "aqua" to 0xFF00FFFF, "fuchsia" to 0xFFFF00FF, "yellow" to 0xFFFFFF00,
    "skyblue" to 0xFF87CEEB, "royalblue" to 0xFF4169E1, "darkblue" to 0xFF00008B,
    "orangered" to 0xFFFF4500, "crimson" to 0xFFDC143C, "firebrick" to 0xFFB22222,
    "darkred" to 0xFF8B0000, "limegreen" to 0xFF32CD32, "seagreen" to 0xFF2E8B57,
    "deeppink" to 0xFFFF1493, "tomato" to 0xFFFF6347, "coral" to 0xFFFF7F50,
    "indigo" to 0xFF4B0082, "burlywood" to 0xFFDEB887, "sandybrown" to 0xFFF4A460,
    "chocolate" to 0xFFD2691E, "sienna" to 0xFFA0522D, "gold" to 0xFFFFD700,
    "brown" to 0xFFA52A2A, "azure" to 0xFF007FFF,
)

private fun namedColor(name: String): Color? =
    NAMED_COLORS[name.lowercase()]?.let { Color(it) }
