package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    private fun extract(html: String): String = TextExtractor.extractDomText(html)

    @Test
    fun `plain text`() = assertEquals("hello", extract("hello"))

    @Test
    fun `tags become spaces`() = assertEquals("你好 世界", extract("<p>你好</p><p>世界</p>"))

    @Test
    fun `removes script and style`() {
        val html = "<p>正文</p><script>var x=1;</script><style>p{color:red}</style><p>结尾</p>"
        assertEquals("正文 结尾", extract(html))
    }

    @Test
    fun `unescape entities`() = assertEquals("a&b <c>", extract("<p>a&amp;b &lt;c&gt;</p>"))

    @Test
    fun `collapse whitespace`() = assertEquals("a b", extract("<p>a</p>\n\n  <p>b</p>"))

    @Test
    fun `nested inline no extra space`() = assertEquals("a bc d", extract("<p>a<b>bc</b>d</p>"))

    @Test
    fun `void elements`() {
        assertEquals("a b", extract("<p>a<br/>b</p>"))
        assertEquals("a b", extract("<p>a<br>b</p>"))
    }

    @Test
    fun `img no text`() = assertEquals("a b", extract("<p>a<img src='x'/>b</p>"))

    @Test
    fun `empty`() {
        assertEquals("", extract(""))
        assertEquals("", extract("<div></div>"))
    }

    @Test
    fun `leading trailing trim`() = assertEquals("x", extract("  \n  <p>x</p>\n  "))

    @Test
    fun `sample chapter stable`() {
        val book = EpubBook(SampleEpubs.copy("nav3")).open()
        try {
            val text = extract((book.chapterText(2) as ChapterReadResult.Success).text)
            assertTrue(text.contains("黎明前的第 1 个观测窗口"))
            assertTrue(text.contains("def detect(signal, noise)"))
            assertTrue(text.contains("信噪比"))
        } finally {
            book.close()
        }
    }
}
