package io.github.gighi947.ankeshelf.ui.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gighi947.ankeshelf.data.TextExtractor
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JS（assets/reader/reader.js）与 Kotlin（PagedLayout / TextExtractor）跨端对照：
 * 在同一 WebView 中执行 PagedMath 与 TextPos，逐字段比对，防止双实现漂移。
 */
@RunWith(AndroidJUnit4::class)
class ReaderPagedCrossTest {

    private fun runOnUi(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun newReaderWebView(html: String): WebView {
        val ref = AtomicReference<WebView>()
        val loaded = CountDownLatch(1)
        runOnUi {
            val wv = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
            wv.settings.javaScriptEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    loaded.countDown()
                }
            }
            wv.loadDataWithBaseURL(
                "file:///android_asset/reader/",
                html,
                "text/html",
                "utf-8",
                null,
            )
            ref.set(wv)
        }
        assertTrue("reader page did not load", loaded.await(15, TimeUnit.SECONDS))
        return ref.get()!!
    }

    private fun evaluateJs(web: WebView, js: String): String? {
        val ref = AtomicReference<String?>()
        val done = CountDownLatch(1)
        runOnUi {
            web.evaluateJavascript(js) { value ->
                ref.set(value)
                done.countDown()
            }
        }
        assertTrue("evaluateJavascript timeout", done.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    @Test
    fun pagedMathGeometryMatchesKotlin() {
        val web = newReaderWebView(
            "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<script src='reader.js'></script></head><body></body></html>",
        )
        val cases = listOf(
            Triple(2400, 1080, """{paged:true,dualPage:false,autoDual:true,margin:40,gap:28,pageWidth:1.0,fontSize:18}"""),
            Triple(1280, 720, """{paged:true,dualPage:false,autoDual:true,margin:40,gap:28,pageWidth:1.0,fontSize:18}"""),
            Triple(1080, 2400, """{paged:true,dualPage:false,autoDual:true,margin:40,gap:28,pageWidth:1.0,fontSize:18}"""),
            Triple(2400, 600, """{paged:true,dualPage:false,autoDual:true,margin:40,gap:28,pageWidth:1.0,fontSize:18}"""),
            Triple(2400, 1080, """{paged:true,dualPage:false,autoDual:true,margin:40,gap:28,pageWidth:0.5,fontSize:18}"""),
            Triple(900, 900, """{paged:true,dualPage:true,autoDual:true,margin:40,gap:28,pageWidth:1.0,fontSize:18}"""),
        )
        for ((fw, fh, s) in cases) {
            val json = evaluateJs(web, "PagedMath.geometry($fw,$fh,$s);")
            val j = JSONObject(json ?: "{}")
            val input = parseInput(s)
            val expect = PagedLayout.geometry(
                fw = fw, fh = fh, paged = input.paged, dualPage = input.dualPage,
                autoDual = input.autoDual, margin = input.margin, gap = input.gap,
                pageWidth = input.pageWidth, fontSize = input.fontSize,
            )
            assertEquals("dual mismatch at $fw x $fh", expect.dual, j.getBoolean("dual"))
            assertEquals("colW mismatch at $fw x $fh", expect.colW, j.getDouble("colW"), 0.001)
            assertEquals("advance mismatch at $fw x $fh", expect.advance, j.getDouble("advance"), 0.001)
            assertEquals("contentWidth mismatch at $fw x $fh", expect.contentWidth.toDouble(), j.getDouble("contentWidth"), 0.001)
            assertEquals("margin mismatch", expect.margin, j.getInt("margin"))
            assertEquals("paddingRight mismatch", expect.paddingRight, j.getInt("paddingRight"))
            assertEquals("gap mismatch", expect.gap, j.getInt("gap"))
        }
    }

    @Test
    fun shouldAutoDualMatchesKotlin() {
        val web = newReaderWebView(
            "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<script src='reader.js'></script></head><body></body></html>",
        )
        for ((fw, fh) in listOf(2400 to 1080, 1280 to 720, 1024 to 768, 800 to 700, 900 to 900, 2400 to 600, 700 to 600)) {
            val jsVal = evaluateJs(web, "PagedMath.shouldAutoDual($fw,$fh);")
            assertEquals(
                "shouldAutoDual mismatch at $fw x $fh",
                PagedLayout.shouldAutoDual(fw, fh),
                jsVal == "true",
            )
        }
    }

    @Test
    fun textPosMatchesKotlinTextExtractor() {
        val body = "<div><p>Hello <b>world</b></p><p>第二段　测试</p><script>skip()</script><style>.x{}</style></div>"
        val web = newReaderWebView(
            "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<script src='reader.js'></script></head><body>$body</body></html>",
        )
        val jsText = evaluateJs(web, "TextPos.build(document).text;")
        val decoded = JSONTokener(jsText ?: "null").nextValue() as String
        assertEquals(TextExtractor.extractDomText(body), decoded)
    }

    private fun parseInput(s: String): InputSpec {
        val dualPage = s.contains("dualPage:true")
        val autoDual = !s.contains("autoDual:false")
        val pageWidth = if (s.contains("pageWidth:0.5")) 0.5 else 1.0
        return InputSpec(
            paged = true,
            dualPage = dualPage,
            autoDual = autoDual,
            margin = 40,
            gap = 28,
            pageWidth = pageWidth,
            fontSize = 18,
        )
    }

    private data class InputSpec(
        val paged: Boolean,
        val dualPage: Boolean,
        val autoDual: Boolean,
        val margin: Int,
        val gap: Int,
        val pageWidth: Double,
        val fontSize: Int,
    )
}
