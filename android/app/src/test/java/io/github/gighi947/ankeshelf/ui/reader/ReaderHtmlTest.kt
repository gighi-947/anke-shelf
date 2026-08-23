package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlTest {

    @Test
    fun extractBodyAndStyles() {
        val html = "<?xml version='1.0'?><html><head><title>t</title>" +
            "<style>.x{color:red}</style></head>" +
            "<body><p>正文</p></body></html>"
        val parts = extractReaderParts(html)
        assertEquals("<p>正文</p>", parts.body)
        assertTrue(parts.headStyles.contains(".x{color:red}"))
    }

    @Test
    fun extractWithoutBodyFallsBack() {
        val html = "<?xml version='1.0'?><!DOCTYPE html><div>plain</div>"
        val parts = extractReaderParts(html)
        assertEquals("<div>plain</div>", parts.body)
    }

    @Test
    fun buildWrapperContainsAssetsAndThemeVars() {
        val parts = extractReaderParts("<body><p>你好</p></body>")
        val html = buildReaderHtml(
            parts,
            readerTheme(SettingsData(theme = "dark", font_size = 20, line_height = 1.9)),
            SettingsData(font_size = 20, line_height = 1.9),
        )
        assertTrue(html.contains("--reader-bg:#222222"))
        // 华为内核 color-mix 求值失败修复：rgb 分量变量必须与 hex 变量成对注入
        assertTrue(html.contains("--reader-fg-rgb:"))
        assertTrue(html.contains("--reader-font-size:20px"))
        assertTrue(html.contains("--reader-line-height:1.9"))
        assertTrue(html.contains("href=\"file:///android_asset/reader/reader.css\""))
        assertTrue(html.contains("src=\"file:///android_asset/reader/reader-lite.js\""))
        assertTrue(html.contains("<div id=\"paged-scroll\">"))
        assertTrue(html.contains("<p>你好</p>"))
    }

    @Test
    fun buildWrapperInjectsCustomFont() {
        val parts = extractReaderParts("<body><p>你好</p></body>")
        val html = buildReaderHtml(
            parts,
            readerTheme(SettingsData(theme = "light")),
            SettingsData(theme = "light", custom_font = "my font.ttf"),
        )
        assertTrue(html.contains("@font-face"))
        assertTrue(html.contains("'AnkeCustom'"))
        assertTrue(html.contains("file:///android_fonts/my%20font.ttf"))
    }

    @Test
    fun buildWrapperSystemFontUsesSystemStack() {
        val parts = extractReaderParts("<body><p>你好</p></body>")
        val html = buildReaderHtml(
            parts,
            readerTheme(SettingsData(theme = "light")),
            SettingsData(theme = "light", custom_font = "system"),
        )
        assertTrue(html.contains("system-ui"))
        assertFalse(html.contains("@font-face"))
    }

    @Test
    fun sanitizeRemovesScriptsAndEventAttrs() {
        val html = "<p onclick=\"x()\" style=\"color:red\">你好<script>alert(1)</script></p>" +
            "<a href=\"javascript:alert(2)\">x</a>" +
            "<iframe src=\"https://evil.example\"></iframe>"
        val clean = sanitizeReaderBody(html)
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("javascript:"))
        assertFalse(clean.contains("iframe"))
        assertTrue(clean.contains("style=\"color:red\""))
        assertTrue(clean.contains("你好"))
        assertTrue(clean.contains("<a>x</a>"))
    }

    @Test
    fun sanitizeKeepsContentAfterSelfClosingScript() {
        val html = "<p>前文</p><script src=\"x\"/><p>正文</p>"
        val clean = sanitizeReaderBody(html)
        assertFalse(clean.contains("<script"))
        assertTrue(clean.contains("前文"))
        assertTrue(clean.contains("正文"))
    }

    @Test
    fun sanitizeDropsEntityEncodedJavascriptUrl() {
        val html = "<a href=\"java&#x73;cript:alert(1)\">x</a><p>正文</p>"
        val clean = sanitizeReaderBody(html)
        assertFalse(clean.contains("javascript"))
        assertFalse(clean.contains("&#x73;cript"))
        assertTrue(clean.contains("正文"))
    }

    @Test
    fun sanitizeRemovesFormControlsAndMeta() {
        val html = "<form><input name=\"a\"><button>go</button></form>" +
            "<meta http-equiv=\"refresh\" content=\"0;url=evil\"><p>正文</p>"
        val clean = sanitizeReaderBody(html)
        assertFalse(clean.contains("input"))
        assertFalse(clean.contains("button"))
        assertFalse(clean.contains("form"))
        assertFalse(clean.contains("meta"))
        assertTrue(clean.contains("正文"))
    }

    @Test
    fun sanitizePreservesNgaMarkup() {
        val html = "<div class=\"nga-floor\" style=\"border-left:4px solid #60A8D8\">" +
            "<blockquote class=\"nga-quote\"><span class=\"red\">彩色</span>" +
            "<table><tr><td colspan=\"2\">格</td></tr></table>" +
            "<img src=\"file:///android_images/b/1.jpg\" alt=\"图\"></blockquote></div>"
        val clean = sanitizeReaderBody(html)
        assertTrue(clean.contains("nga-floor"))
        assertTrue(clean.contains("nga-quote"))
        assertTrue(clean.contains("class=\"red\""))
        assertTrue(clean.contains("colspan=\"2\""))
        assertTrue(clean.contains("src=\"file:///android_images/b/1.jpg\""))
        assertTrue(clean.contains("彩色"))
    }

    @Test
    fun deferImagesAddsLazyAndAsyncDecode() {
        val out = deferContentImages("<p><img src=\"a.jpg\"/><img src=\"b.png\"></p>")
        assertTrue(out.contains("<img src=\"a.jpg\" loading=\"lazy\" decoding=\"async\">"))
        assertTrue(out.contains("<img src=\"b.png\" loading=\"lazy\" decoding=\"async\">"))
    }

    @Test
    fun deferImagesKeepsExistingLoadingAttr() {
        val out = deferContentImages("<img loading=\"eager\" src=\"a.jpg\">")
        assertTrue(out.contains("loading=\"eager\""))
        assertFalse(out.contains("loading=\"lazy\""))
    }

    @Test
    fun sanitizePreservesGululuFloorSectionAndHeader() {
        val html = "<section class=\"gululu-floor\" id=\"floor-123\"><header class=\"floor-head\">" +
            "<span class=\"floor-number\">第 1 楼</span></header></section>"
        val clean = sanitizeReaderBody(html)
        assertTrue(clean.contains("gululu-floor"))
        assertTrue(clean.contains("floor-head"))
        assertTrue(clean.contains("floor-number"))
        assertTrue(clean.contains("id=\"floor-123\""))
    }

    @Test
    fun extractLinkedStyleHrefs() {
        val html = "<html><head><link rel=\"stylesheet\" href=\"../style/main.css\"/></head>" +
            "<body><p>正文</p></body></html>"
        val parts = extractReaderParts(html)
        assertEquals(listOf("../style/main.css"), parts.styleHrefs)
    }

    @Test
    fun extractReaderPartsAppliesImageDefer() {
        val parts = extractReaderParts("<body><img src='x.jpg'></body>")
        assertTrue(parts.body.contains("loading=\"lazy\" decoding=\"async\""))
    }

    @Test
    fun hexToRgbComponentsParsesFormats() {
        // #RRGGBB / #RGB / 非法输入（返回 null，调用方跳过注入）
        assertEquals("34,34,34", hexToRgbComponents("#222222"))
        assertEquals("255,0,0", hexToRgbComponents("#f00"))
        assertEquals("91,163,217", hexToRgbComponents("#5BA3D9"))
        assertEquals(null, hexToRgbComponents("not-a-color"))
        assertEquals(null, hexToRgbComponents("#12345"))
        assertEquals(null, hexToRgbComponents(""))
    }
}
