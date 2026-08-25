package io.github.gighi947.ankeshelf.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
 * 离屏 WebView 渲染器：把单楼层 HTML 渲染为 PNG/WebP。
 * 页面不加载 reader-lite.js，只依赖 CSS 与图片加载；JS 仅用于轮询
 * 图片/字体完成状态与测量页面高度。
 */
object FloorExportRenderer {

    private const val VIEWPORT_WIDTH = 828 // 46em @ 18px；与 reader.css 的 #paged-scroll 对齐

    private class Bridge {
        var pending = -1
        var failed = 0
        var total = 0
        var height = 0
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
        viewportWidth: Int = VIEWPORT_WIDTH,
        timeoutMs: Long = 30000,
    ): FloorRenderResult = withContext(Dispatchers.Main) {
        val vw = viewportWidth.coerceAtLeast(320)
        val web = WebView(context.applicationContext)
        val bridge = Bridge()
        web.settings.javaScriptEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = false
        web.setBackgroundColor(0x00000000)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun ready(pending: Int, failed: Int, total: Int, height: Int, fontsReady: Boolean) {
                bridge.pending = pending
                bridge.failed = failed
                bridge.total = total
                bridge.height = height
                bridge.fontsReady = fontsReady
            }
        }, "FloorExportBridge")
        val finished = suspendCancellableCoroutine { cont ->
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    // 单张图片错误不会走到这里；页面级错误由 JS 轮询超时兜底。
                }
            }
            web.layout(0, 0, vw, 1000)
            web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            web.evaluateJavascript(JS_POLL, null)
            val pending = bridge.pending
            if (pending == 0 && bridge.fontsReady) break
            if (pending < 0) { // 页面尚未就绪
                delay(80)
                continue
            }
            last = "pending=$pending failed=${bridge.failed} total=${bridge.total} height=${bridge.height} fonts=${bridge.fontsReady}"
            delay(150)
        }
        val contentHeight = if (bridge.height > 0) bridge.height else 1000
        web.layout(0, 0, vw, contentHeight)
        delay(50)
        val bitmap = Bitmap.createBitmap(
            (vw * scale).toInt().coerceAtLeast(1),
            (contentHeight * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        web.draw(canvas)
        web.removeJavascriptInterface("FloorExportBridge")
        web.destroy()

        val outFile = File.createTempFile("floor_export_", ".$format", context.cacheDir)
        FileOutputStream(outFile).use { out ->
            val ok = if (format == "webp") {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 82, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            check(ok) { "图片编码失败" }
        }
        bitmap.recycle()
        FloorRenderResult(
            file = outFile,
            width = (vw * scale).toInt(),
            height = (contentHeight * scale).toInt(),
            imageFailed = bridge.failed,
            imageTotal = bridge.total,
        )
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
  try { fontsReady = !document.fonts || document.fonts.status === 'loaded'; } catch (e) { fontsReady = true; }
  var h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0);
  FloorExportBridge.ready(pending, failed, total, h, fontsReady);
  return '';
})();
"""
}
