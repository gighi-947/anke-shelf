package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData
import org.junit.Assert.assertEquals
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
    fun buildWrapperContainsThemeAndBody() {
        val parts = extractReaderParts("<body><p>你好</p></body>")
        val html = buildReaderHtml(parts, readerTheme("dark"), SettingsData(font_size = 20, line_height = 1.9))
        assertTrue(html.contains("background:#171412"))
        assertTrue(html.contains("font-size:20px"))
        assertTrue(html.contains("<p>你好</p>"))
    }
}
