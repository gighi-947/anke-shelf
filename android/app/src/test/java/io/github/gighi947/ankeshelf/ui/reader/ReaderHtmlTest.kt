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
        assertTrue(html.contains("--reader-font-size:20px"))
        assertTrue(html.contains("--reader-line-height:1.9"))
        assertTrue(html.contains("href=\"file:///android_asset/reader/reader.css\""))
        assertTrue(html.contains("src=\"file:///android_asset/reader/reader.js\""))
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
        println("CLEAN=[" + clean + "]")
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("javascript:"))
        assertFalse(clean.contains("iframe"))
        assertTrue(clean.contains("style=\"color:red\""))
        assertTrue(clean.contains("你好"))
        assertTrue(clean.contains("<a >x</a>"))
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
    fun extractReaderPartsAppliesImageDefer() {
        val parts = extractReaderParts("<body><img src='x.jpg'></body>")
        assertTrue(parts.body.contains("loading=\"lazy\" decoding=\"async\""))
    }

}
