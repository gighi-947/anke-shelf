package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NGA 正文渲染：图片 URL 规范化与 imgSrc 挂钩（在线模式语义）。 */
class NgaFormatHtmlTest {

    @Test
    fun `img protocol relative becomes https`() {
        val out = NgaFormatHtml.renderContentHtml(
            "[img]//img.nga.178.com/attachments/mon_1.jpg[/img]",
        )
        assertTrue(out.contains("https://img.nga.178.com/attachments/mon_1.jpg"))
        assertFalse(out.contains("\"//img.nga.178.com"))
    }

    @Test
    fun `img dot slash becomes attachments absolute`() {
        val out = NgaFormatHtml.renderContentHtml("[img]./mon_2.png[/img]")
        assertTrue(out.contains("https://img.nga.178.com/attachments/mon_2.png"))
    }

    @Test
    fun `img absolute preserved and thumb suffix stripped`() {
        val out = NgaFormatHtml.renderContentHtml(
            "[img]https://img.nga.cn/attachments/mon_3.jpg.thumb.jpg[/img]",
        )
        assertTrue(out.contains("https://img.nga.cn/attachments/mon_3.jpg"))
        assertFalse(out.contains(".thumb.jpg"))
    }

    @Test
    fun `imgSrc callback receives final url`() {
        var received: String? = null
        NgaFormatHtml.renderContentHtml(
            "[img]//img.nga.178.com/mon_4.webp[/img]",
            imgSrc = { received = it; it },
        )
        assertEquals("https://img.nga.178.com/mon_4.webp", received)
    }

    @Test
    fun `normalizeImageUrl strips thumb and resolves protocol-relative`() {
        assertEquals(
            "https://img.nga.178.com/attachments/mon_1.jpg",
            NgaFormatHtml.normalizeImageUrl("//img.nga.178.com/attachments/mon_1.jpg.thumb.jpg"),
        )
    }

    @Test
    fun `normalizeImageUrl resolves dot slash`() {
        assertEquals(
            "https://img.nga.178.com/attachments/mon_2.png",
            NgaFormatHtml.normalizeImageUrl("./mon_2.png"),
        )
    }
}
