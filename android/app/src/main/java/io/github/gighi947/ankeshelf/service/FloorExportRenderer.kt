package io.github.gighi947.ankeshelf.service

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.gighi947.ankeshelf.ui.reader.ReaderEgress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 离屏 WebView 渲染结果。 */
data class FloorRenderResult(
    val file: File,
    val width: Int,
    val height: Int,
    val imageFailed: Int,
    val imageTotal: Int,
)

/**
 * 楼层导出渲染器：把单楼层 HTML 渲染为 PNG/WebP。
 *
 * 教训（见 docs/LESSONS_LEARNED.md 第 10 节）：
 * - WebView.layout() 单位是物理像素，必须先按密度换算；
 * - draw() 只画可见区域，所以先把 view 布局成完整内容尺寸，再按纵向分片
 *   绘制到同一张大图，避免长楼层被截断；
 * - 导出页面内联 reader.css，不依赖 file:///android_asset 链接；
 * - 自定义字体经 shouldInterceptRequest 提供，并等待 document.fonts.ready。
 */
object FloorExportRenderer {

    private const val VIEWPORT_WIDTH = 828
    private const val MAX_SLICE_HEIGHT_PX = 12000

    private class Bridge {
        var pending = -1
        var failed = 0
        var total = 0
        var heightCss = 0
        var fontsReady = false
    }

    suspend fun render(
        context: Context,
        html: String,
        baseUrl: String,
        scale: Float,
        format: String,
        fontsDir: File? = null,
        assetResolver: ((String) -> ByteArray?)? = null,
        ngaImageFetcher: ((String) -> ByteArray?)? = null,
        userAgent: String? = null,
        viewportWidth: Int = VIEWPORT_WIDTH,
        timeoutMs: Long = 30000,
    ): FloorRenderResult = withContext(Dispatchers.Main) {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val vwCss = viewportWidth.coerceAtLeast(320)
        Log.w("AnkeShelf", "[floor_export] density=$density vwCss=$vwCss scale=$scale format=$format")
        // 先按 1x 密度渲染成底图，再按用户倍率缩放，避免 view 高度过大。
        val viewWidthPx = (vwCss * density).roundToInt().coerceAtLeast(1)
        val initialHeightPx = (1000 * density).roundToInt().coerceAtLeast(1)

        val web = WebView(context.applicationContext)
        val bridge = Bridge()
        web.settings.javaScriptEnabled = true
        if (!userAgent.isNullOrBlank()) web.settings.userAgentString = userAgent
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = false
        web.setBackgroundColor(0x00000000)

        val readerCss = runCatching {
            context.assets.open("reader/reader.css").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val builtinFontCss =
            "@font-face{font-family:\"LXGW WenKai\";" +
                "src:url(\"file:///android_asset/fonts/LXGWWenKai-Regular.woff2\") format(\"woff2\");" +
                "font-weight:400;font-display:swap;}"
        val preparedHtml = html
            .replace(
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>",
                "<meta name=\"viewport\" content=\"width=$vwCss,initial-scale=1\"/>",
            )
            .replace(
                "<link rel=\"stylesheet\" href=\"file:///android_asset/reader/reader.css\"/>",
                if (readerCss.isBlank()) "" else "<style>$readerCss</style>",
            )
            .replace(
                "</head>",
                "<style>$builtinFontCss</style></head>",
            )

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun ready(pending: Int, failed: Int, total: Int, height: Int, fontsReady: Boolean) {
                bridge.pending = pending
                bridge.failed = failed
                bridge.total = total
                bridge.heightCss = height
                bridge.fontsReady = fontsReady
            }
        }, "FloorExportBridge")

        val finished = suspendCancellableCoroutine { cont ->
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("file:///android_fonts/") && fontsDir != null) {
                        val name = URLDecoder.decode(url.removePrefix("file:///android_fonts/"), "UTF-8")
                        val f = File(fontsDir, name)
                        if (f.isFile) {
                            val mime = when (f.extension.lowercase()) {
                                "woff2" -> "font/woff2"
                                "woff" -> "font/woff"
                                "ttf" -> "font/ttf"
                                "otf" -> "font/otf"
                                else -> "application/octet-stream"
                            }
                            Log.w("AnkeShelf", "[floor_export] font hit $name")
                            return WebResourceResponse(mime, null, ByteArrayInputStream(f.readBytes()))
                        }
                    }
                    if (url.startsWith("file:///android_asset/fonts/")) {
                        val rel = url.removePrefix("file:///android_asset/fonts/")
                        val bytes = runCatching { context.assets.open("fonts/$rel").readBytes() }.getOrNull()
                        if (bytes != null) {
                            val mime = when (rel.substringAfterLast('.', "").lowercase()) {
                                "woff2" -> "font/woff2"
                                "woff" -> "font/woff"
                                "ttf" -> "font/ttf"
                                "otf" -> "font/otf"
                                else -> "application/octet-stream"
                            }
                            Log.w("AnkeShelf", "[floor_export] asset-font hit $rel")
                            return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                        }
                    }
                    if (url.startsWith("file:///android_epub/") && assetResolver != null) {
                        val rel = URLDecoder.decode(
                            url.removePrefix("file:///android_epub/").substringAfter('/'),
                            "UTF-8",
                        )
                        val bytes = assetResolver(rel) ?: return null
                        val mime = when (rel.substringAfterLast('.', "").lowercase()) {
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            "svg" -> "image/svg+xml"
                            "css" -> "text/css"
                            else -> "application/octet-stream"
                        }
                        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                    }
                    if (ReaderEgress.isNgaImageUrl(url) && ngaImageFetcher != null) {
                        val bytes = ngaImageFetcher(url) ?: return null
                        val mime = when {
                            bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() -> "image/png"
                            bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "image/webp"
                            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                            bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
                            else -> "image/jpeg"
                        }
                        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            web.layout(0, 0, viewWidthPx, initialHeightPx)
            web.loadDataWithBaseURL(baseUrl, preparedHtml, "text/html", "utf-8", null)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            web.evaluateJavascript(JS_POLL, null)
            val pending = bridge.pending
            if (pending == 0 && bridge.fontsReady) break
            if (pending < 0) {
                delay(80)
                continue
            }
            delay(150)
        }

        val contentHeightCss = bridge.heightCss.coerceAtLeast(1000)
        val contentHeightPx = (contentHeightCss * density).roundToInt().coerceAtLeast(1)
        Log.w("AnkeShelf", "[floor_export] contentHeightCss=$contentHeightCss contentHeightPx=$contentHeightPx viewWidthPx=$viewWidthPx fontsReady=${bridge.fontsReady}")
        web.layout(0, 0, viewWidthPx, contentHeightPx)
        delay(80)

        val base = Bitmap.createBitmap(viewWidthPx, contentHeightPx, Bitmap.Config.ARGB_8888)
        val baseCanvas = Canvas(base)
        var y = 0
        while (y < contentHeightPx) {
            val sliceHeight = minOf(MAX_SLICE_HEIGHT_PX, contentHeightPx - y)
            val slice = Bitmap.createBitmap(viewWidthPx, sliceHeight, Bitmap.Config.ARGB_8888)
            val sliceCanvas = Canvas(slice)
            sliceCanvas.translate(0f, -y.toFloat())
            web.draw(sliceCanvas)
            baseCanvas.drawBitmap(slice, 0f, y.toFloat(), null)
            slice.recycle()
            y += sliceHeight
            if (y < contentHeightPx) delay(1)
        }

        web.removeJavascriptInterface("FloorExportBridge")
        web.destroy()

        val outW = (viewWidthPx * scale).roundToInt().coerceAtLeast(1)
        val outH = (contentHeightPx * scale).roundToInt().coerceAtLeast(1)
        val bitmap = if (outW == viewWidthPx && outH == contentHeightPx) base
        else Bitmap.createScaledBitmap(base, outW, outH, true).also { base.recycle() }

        val outFile = File.createTempFile("floor_export_", ".$format", context.cacheDir)
        val result = withContext(Dispatchers.Default) {
            FileOutputStream(outFile).use { out ->
                val ok = if (format == "webp") {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 82, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!ok) {
                    val fallback = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    check(fallback) { "图片编码失败" }
                }
            }
            bitmap.recycle()
            FloorRenderResult(
                file = outFile,
                width = outW,
                height = outH,
                imageFailed = bridge.failed,
                imageTotal = bridge.total,
            )
        }
        Log.w("AnkeShelf", "[floor_export] done out=${result.width}x${result.height} failed=${result.imageFailed}/${result.imageTotal}")
        result
    }

    private const val JS_POLL = """
(function(){
  var imgs = Array.from(document.images);
  var pending = 0, failed = 0, total = imgs.length;
  imgs.forEach(function(img){
    if (!img.complete) pending += 1;
    else if (!img.naturalWidth) failed += 1;
  });
  var fontsReady = true;
  try {
    if (document.fonts && document.fonts.load) {
      document.fonts.load('16px "LXGW WenKai"');
      document.fonts.load('16px "AnkeCustom"');
    }
    fontsReady = !document.fonts || document.fonts.status === 'loaded';
  } catch (e) { fontsReady = true; }
  var h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0);
  FloorExportBridge.ready(pending, failed, total, h, fontsReady);
  return '';
})();
"""
}
