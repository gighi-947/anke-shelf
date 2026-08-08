package io.github.gighi947.ankeshelf.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

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
 * - saveImage / loadImage：图片保存与放大预览兜底；
 * - onSelection / onHighlightTap：标注交互；
 * - log(message)：调试日志。
 */
class ReaderBridge(
    private val onProgressValue: (Int, Double, Boolean) -> Unit,
    private val onPageChanged: (Int, Int, Int) -> Unit,
    private val onRequestChapter: (Int) -> Unit,
    private val onImageLightbox: (Boolean) -> Unit,
    private val onSaveImageCb: (String) -> Unit,
    private val onLoadImageCb: (String) -> Unit,
    private val onSelectionCb: (Int, Int, Int, String) -> Unit,
    private val onHighlightTapCb: (String) -> Unit,
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
    fun setImageLightbox(open: Boolean) {
        main.post { onImageLightbox(open) }
    }

    @JavascriptInterface
    fun saveImage(src: String) {
        main.post { onSaveImageCb(src) }
    }

    @JavascriptInterface
    fun loadImage(src: String) {
        main.post { onLoadImageCb(src) }
    }

    @JavascriptInterface
    fun onSelection(chapterIndex: Int, start: Int, end: Int, text: String) {
        main.post { onSelectionCb(chapterIndex, start, end, text) }
    }

    @JavascriptInterface
    fun onHighlightTap(id: String) {
        main.post { onHighlightTapCb(id) }
    }

    @JavascriptInterface
    fun log(message: String) {
        onLog(message)
    }
}
