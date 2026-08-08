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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
private val ReaderThemeColors.fgColor: Color
    get() = parseHtmlColor(text) ?: Color(red = 0x20, green = 0x1A, blue = 0x15)
private val ReaderThemeColors.accentColor: Color
    get() = parseHtmlColor(accent) ?: Color(red = 0x2E, green = 0x86, blue = 0xAB)

/** 桌面 NGA 楼层/引用/追评配色（浅色/深色两套，来自原生书 CSS）。 */
private data class CardPalette(
    val border: Color,
    val head: Color,
    val quoteBg: Color,
    val commentBg: Color,
    val dice: Color,
)

private fun cardPalette(theme: ReaderThemeColors): CardPalette {
    val light = (parseHtmlColor(theme.background)?.let { bg ->
        0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue
    } ?: 1f) > 0.5f
    return if (light) {
        CardPalette(
            border = Color(224 / 255f, 224 / 255f, 224 / 255f),
            head = Color(136 / 255f, 136 / 255f, 136 / 255f),
            quoteBg = Color(247 / 255f, 247 / 255f, 247 / 255f),
            commentBg = Color(250 / 255f, 250 / 255f, 250 / 255f),
            dice = Color(red = 0xB8, green = 0x86, blue = 0x0B),
        )
    } else {
        CardPalette(
            border = Color(58 / 255f, 58 / 255f, 58 / 255f),
            head = Color(138 / 255f, 138 / 255f, 138 / 255f),
            quoteBg = Color(42 / 255f, 42 / 255f, 42 / 255f),
            commentBg = Color(38 / 255f, 38 / 255f, 38 / 255f),
            dice = Color(red = 0xD9, green = 0xB4, blue = 0x5B),
        )
    }
}

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
            "yellow" to Color(red = 0xFD, green = 0xD8, blue = 0x35, alpha = 0x66),
            "green" to Color(red = 0x66, green = 0xBB, blue = 0x6A, alpha = 0x66),
            "blue" to Color(red = 0x42, green = 0xA5, blue = 0xF5, alpha = 0x66),
            "pink" to Color(red = 0xEC, green = 0x40, blue = 0x7A, alpha = 0x66),
            "purple" to Color(red = 0xAB, green = 0x47, blue = 0xBC, alpha = 0x66),
            "cyan" to Color(red = 0x26, green = 0xC6, blue = 0xDA, alpha = 0x66),
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
                if (bi < doc.blocks.lastIndex) BlockSpacing(doc.blocks[bi], doc.blocks[bi + 1])
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
private fun BlockSpacing(prev: ReaderBlock, next: ReaderBlock) {
    val h = when {
        prev is ReaderBlock.Floor || next is ReaderBlock.Floor -> 10.dp
        prev is ReaderBlock.Quote || next is ReaderBlock.Quote -> 10.dp
        prev is ReaderBlock.Comment || next is ReaderBlock.Comment -> 10.dp
        prev is ReaderBlock.Table || next is ReaderBlock.Table -> 10.dp
        prev is ReaderBlock.Image || next is ReaderBlock.Image -> 8.dp
        prev is ReaderBlock.Blank || next is ReaderBlock.Blank -> 8.dp
        else -> 0.dp
    }
    Spacer(Modifier.height(h))
}

/* ---------------- 分页模式 ---------------- */

private class Frag {
    var ann: AnnotatedString? = null
    var style: TextStyle? = null
    var heightPx: Int = 0
    var image: String? = null
    var table: ReaderBlock.Table? = null
    /** 带 1px 边框的卡片（楼层/追评）：cardTop/cardBottom 控制上下边线，跨页时只画段落边界。 */
    var card: Boolean = false
    var cardBg: Color? = null
    var cardTop: Boolean = false
    var cardBottom: Boolean = false
    /** 左侧装饰条（楼层 4dp 强调色；引用 3dp 边框色）。 */
    var leftBar: Color? = null
    var leftBarWidthDp: Int = 4
    /** 引用块底色/左条（单独成组时使用）。 */
    var quoteBg: Color? = null
    var quoteBar: Color? = null
    var head: String? = null
    var headColor: Color? = null
    var headDivider: Boolean = true
    var padStartPx: Int = 0
    var padEndPx: Int = 0
    var topPad: Int = 0
    var bottomPad: Int = 0
    var marginTopPx: Int = 0
    var marginBottomPx: Int = 0
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
                    cardBorder = cardPalette(theme).border,
                    quoteBg = cardPalette(theme).quoteBg,
                    commentBg = cardPalette(theme).commentBg,
                    diceColor = cardPalette(theme).dice,
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
    cardBorder: Color,
    quoteBg: Color,
    commentBg: Color,
    diceColor: Color,
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

    var lastCardFrag: Frag? = null
    var nextCardTop = false

    fun addFrag(f: Frag) {
        ensureCols()
        val totalH = f.heightPx + f.marginTopPx + f.marginBottomPx
        if (used > 0 && used + totalH > pageH) {
            // 跨列/跨页前封口当前卡片段落，下一页第一片断补顶边线（对齐桌面 break-inside:auto）。
            lastCardFrag?.cardBottom = true
            if (colIndex < colCount - 1) {
                colIndex++
                used = 0
                nextCardTop = true
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
                nextCardTop = true
                ensureCols()
            }
        }
        if (f.heightPx > pageH) f.heightPx = pageH
        if (f.card && nextCardTop) {
            f.cardTop = true
            nextCardTop = false
        }
        page.columns[colIndex].add(f)
        if (f.card) lastCardFrag = f else lastCardFrag = null
        used += totalH
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

    fun pushTextBlock(
        block: ReaderBlock,
        blockIdx: Int,
        plainStart: Int,
        padTop: Int,
        padBottom: Int,
        card: Boolean,
        border: Color?,
        accent: Color?,
        bg: Color?,
        fontScale: Float = 1f,
        quote: Boolean = false,
        quoteBar: Color? = null,
        padStart: Int = 0,
        padEnd: Int = 0,
        marginTop: Int = 0,
        marginBottom: Int = 0,
    ): List<Frag> {
        val scaled = SpanStyle(fontSize = (baseStyle.fontSize * fontScale).sp)
        val ann = when (block) {
            is ReaderBlock.Paragraph -> spansAnn(block.spans, baseStyle.style(scaled), textColor)
            is ReaderBlock.Heading -> spansAnn(block.spans, baseStyle.style(scaled.merge(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseStyle.fontSize * fontScale * 1.1f).sp))), textColor)
            is ReaderBlock.Dice -> buildAnnotatedString {
                append(
                    AnnotatedString(
                        block.text,
                        SpanStyle(color = diceColor, fontWeight = FontWeight.Bold, fontSize = scaled.fontSize),
                    ),
                )
            }
            is ReaderBlock.Comment -> spansAnn(block.spans, baseStyle.style(SpanStyle(fontSize = (baseStyle.fontSize * 0.92f).sp)), textColor)
            else -> buildAnnotatedString { append(blockText(block)) }
        }
        val lhPx = baseStyle.fontSize * lineHeight.toFloat() * density.density
        val lines = measureLines(ann, baseStyle.style(scaled), lhPx.roundToInt())
        if (lines.isEmpty()) return emptyList()
        lines.forEachIndexed { i, frag ->
            frag.plainStart = plainStart
            frag.card = card
            frag.borderColor = border
            frag.accentColor = accent
            frag.cardBg = bg
            frag.leftBar = accent
            frag.quoteBg = if (quote) quoteBg else null
            frag.quoteBar = if (quote) quoteBar else null
            frag.padStartPx = padStart
            frag.padEndPx = padEnd
            frag.topPad = if (i == 0) padTop else 0
            frag.bottomPad = if (i == lines.lastIndex) padBottom else 0
            frag.marginTopPx = if (i == 0) marginTop else 0
            frag.marginBottomPx = if (i == lines.lastIndex) marginBottom else 0
            addFrag(frag)
        }
        return lines
    }

    doc.blocks.forEachIndexed { bi, block ->
        plainCursor = doc.blockOffsets[bi]
        page.startOffset = plainCursor
        page.startBlock = bi
        when (block) {
            is ReaderBlock.Floor -> {
                // 楼层卡片：头部一行 + 正文块；跨页时每片断都带卡片边框（对齐桌面 break-inside:auto）。
                val border = cardBorder
                val headFrag = Frag()
                headFrag.head = "${block.lou}楼 · ${block.likes}赞 · ${block.username}(${block.userId}) · ${block.time}"
                headFrag.headColor = mutedColor
                headFrag.heightPx = (24 * density.density).roundToInt()
                headFrag.card = true
                headFrag.borderColor = border
                headFrag.accentColor = accentColor
                headFrag.cardBg = null
                headFrag.leftBar = accentColor
                headFrag.cardTop = true
                headFrag.topPad = (10 * density.density).roundToInt()
                headFrag.marginTopPx = (10 * density.density).roundToInt()
                headFrag.plainStart = plainCursor
                addFrag(headFrag)
                headFrag.head = "${block.lou}\u697c \u00b7 ${block.likes}\u8d5e \u00b7 ${block.username}(${block.userId}) \u00b7 ${block.time} \u00b7 pid:${block.pid}"
                if (block.body.isEmpty()) {
                } else {
                    block.body.forEach { sub ->
                        when (sub) {
                            is ReaderBlock.Paragraph, is ReaderBlock.Heading, is ReaderBlock.Dice, is ReaderBlock.Comment ->
                                pushTextBlock(sub, bi, plainCursor, 0, 0, true, border, accentColor, null)
                            is ReaderBlock.Quote -> {
                                val inner = mutableListOf<ReaderBlock>()
                                inner.addAll(sub.body)
                                val pad = (8 * density.density).roundToInt()
                                val m = (10 * density.density).roundToInt()
                                pushTextBlock(
                                    ReaderBlock.Paragraph(inner.flatMap { it.spansOrEmpty() }),
                                    bi, plainCursor, pad, pad,
                                    card = true, border = border, accent = accentColor, bg = null,
                                    fontScale = 0.95f, quote = true, quoteBar = border,
                                    marginTop = m, marginBottom = m,
                                )
                            }
                            is ReaderBlock.Image -> {
                                val f = Frag()
                                f.image = sub.src
                                f.heightPx = minOf((220 * density.density).roundToInt(), (pageH * 0.6f).roundToInt())
                                f.card = true
                                f.borderColor = border
                                f.accentColor = accentColor
                                f.cardBg = null
                                addFrag(f)
                            }
                            is ReaderBlock.Table -> {
                                val f = Frag()
                                f.table = sub
                                f.heightPx = (minOf(sub.rows.size * 30, 260) * density.density).roundToInt()
                                f.card = true
                                f.borderColor = border
                                f.accentColor = accentColor
                                f.cardBg = null
                                addFrag(f)
                            }
                            else -> Unit
                        }
                    }
                }
                lastCardFrag?.cardBottom = true
                lastCardFrag?.bottomPad = (10 * density.density).roundToInt()
            }
            is ReaderBlock.Comment -> {
                // 追评卡片：commentBg + 1px 边框 + 8/10 内边距 + 10px 外边距，头行 muted。
                val pad = (8 * density.density).roundToInt()
                val m = (10 * density.density).roundToInt()
                val headFrag = Frag()
                headFrag.head = buildString {
                    if (block.lou > 0) append("${block.lou}\u697c")
                    if (block.username.isNotBlank()) {
                        if (block.lou > 0) append(' ')
                        append(block.username)
                    }
                }
                headFrag.headColor = mutedColor
                headFrag.heightPx = (14 * density.density).roundToInt()
                headFrag.card = true
                headFrag.cardBg = commentBg
                headFrag.borderColor = cardBorder
                headFrag.cardTop = true
                headFrag.padStartPx = (10 * density.density).roundToInt()
                headFrag.padEndPx = (10 * density.density).roundToInt()
                headFrag.topPad = pad
                headFrag.marginTopPx = m
                headFrag.headDivider = false
                headFrag.plainStart = plainCursor
                addFrag(headFrag)
                pushTextBlock(
                    block, bi, plainCursor, 0, 0,
                    card = true, border = cardBorder, accent = null, bg = commentBg,
                    padStart = (10 * density.density).roundToInt(),
                    padEnd = (10 * density.density).roundToInt(),
                )
                lastCardFrag?.cardBottom = true
                lastCardFrag?.bottomPad = pad
            }
            is ReaderBlock.Paragraph, is ReaderBlock.Heading, is ReaderBlock.Dice ->
                pushTextBlock(block, bi, plainCursor, 0, 0, false, null, null, null)
            is ReaderBlock.Quote -> {
                val pad = (8 * density.density).roundToInt()
                val m = (10 * density.density).roundToInt()
                pushTextBlock(
                    ReaderBlock.Paragraph(block.body.flatMap { it.spansOrEmpty() }),
                    bi, plainCursor, pad, pad,
                    card = false, border = null, accent = null, bg = quoteBg,
                    fontScale = 0.95f, quote = true, quoteBar = cardBorder,
                    padStart = (12 * density.density).roundToInt(),
                    padEnd = (12 * density.density).roundToInt(),
                    marginTop = m, marginBottom = m,
                )
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
                if (frag.accentColor != null) {
                    FloorHead(
                        head = frag.head!!,
                        baseStyle = baseStyle,
                        accent = frag.accentColor!!,
                        muted = frag.headColor ?: theme.fgColor.copy(alpha = 0.7f),
                    )
                } else {
                    Text(
                        frag.head!!,
                        style = baseStyle.style(SpanStyle(
                            fontSize = (baseStyle.fontSize * 0.8f).sp,
                            color = frag.headColor ?: theme.fgColor.copy(alpha = 0.7f),
                        )),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (frag.headDivider) {
                    DottedDivider(
                        color = frag.borderColor ?: theme.fgColor.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = 3.dp, bottom = 5.dp),
                    )
                }
            }
            frag.ann != null -> {
                val line: @Composable () -> Unit = {
                    val ann = applyHighlights(frag.ann!!, frag.plainStart, highlights, highlightColors)
                    Text(
                        text = ann,
                        style = frag.style ?: baseStyle.style(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (frag.quoteBg != null && frag.card) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(frag.quoteBar ?: Color.Transparent),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .background(frag.quoteBg!!)
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            line()
                        }
                    }
                } else {
                    line()
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (frag.marginTopPx > 0) Spacer(Modifier.height(frag.marginTopPx.dp))
        when {
            frag.card -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(frag.cardBg ?: Color.Transparent)
                        .drawBehind {
                            val bc = frag.borderColor ?: return@drawBehind
                            val stroke = 1.dp.toPx()
                            if (frag.cardTop) {
                                drawLine(bc, Offset(0f, 0f), Offset(size.width, 0f), stroke)
                            }
                            if (frag.cardBottom) {
                                drawLine(bc, Offset(size.width, size.height), Offset(0f, size.height), stroke)
                            }
                            drawLine(bc, Offset(0f, 0f), Offset(0f, size.height), stroke)
                            drawLine(bc, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                        },
                ) {
                    if (frag.leftBar != null) {
                        Box(
                            Modifier
                                .width(frag.leftBarWidthDp.dp)
                                .fillMaxHeight()
                                .background(frag.leftBar!!),
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(
                                start = if (frag.padStartPx > 0) frag.padStartPx.dp else 12.dp,
                                end = if (frag.padEndPx > 0) frag.padEndPx.dp else 12.dp,
                                top = frag.topPad.dp,
                                bottom = frag.bottomPad.dp,
                            ),
                    ) {
                        content()
                    }
                }
            }
            frag.quoteBg != null -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(frag.quoteBar ?: Color.Transparent),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .background(frag.quoteBg!!)
                            .padding(
                                start = if (frag.padStartPx > 0) frag.padStartPx.dp else 12.dp,
                                end = if (frag.padEndPx > 0) frag.padEndPx.dp else 12.dp,
                                top = frag.topPad.dp,
                                bottom = frag.bottomPad.dp,
                            ),
                    ) {
                        content()
                    }
                }
            }
            else -> content()
        }
        if (frag.marginBottomPx > 0) Spacer(Modifier.height(frag.marginBottomPx.dp))
    }
}

@Composable
private fun FloorHead(
    head: String,
    baseStyle: ReaderTextStyle,
    accent: Color,
    muted: Color,
) {
    val idx = head.indexOf("楼 · ")
    if (idx > 0) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                head.substring(0, idx + 1),
                style = baseStyle.style(SpanStyle(
                    fontSize = (baseStyle.fontSize * 0.82f).sp,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )),
            )
            Text(
                head.substring(idx + 1),
                style = baseStyle.style(SpanStyle(
                    fontSize = (baseStyle.fontSize * 0.82f).sp,
                    color = muted,
                )),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            head,
            style = baseStyle.style(SpanStyle(fontSize = (baseStyle.fontSize * 0.82f).sp, color = muted)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 桌面 .floor-head 的 1px dotted 分隔线（小圆点）。 */
@Composable
private fun DottedDivider(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val step = 6.dp.toPx()
                val dot = 2.dp.toPx()
                val y = size.height / 2f
                var x = 0f
                while (x < size.width) {
                    drawLine(color, Offset(x, y), Offset(minOf(x + dot, size.width), y), stroke)
                    x += step
                }
            },
    )
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
            val pal = cardPalette(theme)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(Color.Transparent),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(theme.accentColor),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, pal.border, RoundedCornerShape(2.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    FloorHead(
                        head = "${block.lou}楼 · ${block.likes}赞 · ${block.username}(${block.userId}) · ${block.time} · pid:${block.pid}",
                        baseStyle = baseStyle,
                        accent = theme.accentColor,
                        muted = pal.head,
                    )
                    DottedDivider(
                        color = pal.border,
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
                    .background(cardPalette(theme).border),
            )
            Column(
                Modifier
                    .weight(1f)
                    .background(cardPalette(theme).quoteBg)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                if (block.title.isNotBlank()) {
                    Text(
                        block.title,
                        style = baseStyle.style(SpanStyle(
                            fontSize = (baseStyle.fontSize * 0.9f).sp,
                            color = cardPalette(theme).head,
                        )),
                    )
                }
                val quoteStyle = ReaderTextStyle(
                    span = baseStyle.span.copy(fontSize = (baseStyle.fontSize * 0.95f).sp),
                    para = baseStyle.para,
                )
                block.body.forEach { NativeBlock(it, theme, quoteStyle, scrollMode, highlights, highlightColors, imageBytes, onImageLongPress) }
            }
        }
        is ReaderBlock.Dice -> Text(
            block.text,
            style = baseStyle.style(SpanStyle(fontWeight = FontWeight.Bold, color = cardPalette(theme).dice)),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        is ReaderBlock.Comment -> Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, cardPalette(theme).border, RoundedCornerShape(2.dp))
                .background(cardPalette(theme).commentBg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (block.lou > 0 || block.username.isNotBlank()) {
                Text(
                    buildString {
                        if (block.lou > 0) append("${block.lou}楼")
                        if (block.username.isNotBlank()) {
                            if (block.lou > 0) append(' ')
                            append(block.username)
                        }
                    },
                    style = baseStyle.style(SpanStyle(
                        fontSize = (baseStyle.fontSize * 0.8f).sp,
                        color = cardPalette(theme).head,
                    )),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                block.spans.joinToString("") { it.text },
                style = baseStyle.style(SpanStyle(
                    fontSize = (baseStyle.fontSize * 0.92f).sp,
                    color = theme.fgColor,
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
    Text(text = ann, style = style, modifier = Modifier.padding(vertical = 4.dp))
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
                red = (hex[0].digitToIntOrNull(16)?.let { it * 17 } ?: 255) / 255f,
                green = (hex[1].digitToIntOrNull(16)?.let { it * 17 } ?: 255) / 255f,
                blue = (hex[2].digitToIntOrNull(16)?.let { it * 17 } ?: 255) / 255f,
            )
            6 -> runCatching {
                val v = hex.toLong(16)
                Color(
                    red = ((v shr 16) and 0xFF).toInt() / 255f,
                    green = ((v shr 8) and 0xFF).toInt() / 255f,
                    blue = (v and 0xFF).toInt() / 255f,
                )
            }.getOrNull()
            8 -> runCatching {
                val v = hex.toLong(16)
                Color(
                    red = ((v shr 16) and 0xFF).toInt() / 255f,
                    green = ((v shr 8) and 0xFF).toInt() / 255f,
                    blue = (v and 0xFF).toInt() / 255f,
                    alpha = ((v shr 24) and 0xFF).toInt() / 255f,
                )
            }.getOrNull()
            else -> null
        }
    }
    if (s.startsWith("rgb(")) {
        val parts = s.removePrefix("rgb(").removeSuffix(")")
            .split(",").map { it.trim().toIntOrNull() ?: return null }
        if (parts.size >= 3) {
            return Color(red = parts[0] / 255f, green = parts[1] / 255f, blue = parts[2] / 255f)
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
    NAMED_COLORS[name.lowercase()]?.let { v ->
        val c = v.toInt()
        Color(
            red = ((c shr 16) and 0xFF) / 255f,
            green = ((c shr 8) and 0xFF) / 255f,
            blue = (c and 0xFF) / 255f,
        )
    }
