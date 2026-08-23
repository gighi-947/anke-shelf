package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NGA 正文渲染：图片 URL 规范化与 imgSrc 挂钩（在线模式语义）。 */
class NgaFormatHtmlTest {

    @Test
    fun `audio becomes music cue`() {
        // 方案 A（2026-08-23）：[audio] 外链音乐 → 骨碌碌同款 cue，复用宿主播放器。
        val out = NgaFormatHtml.renderContentHtml(
            "前文[audio]https://music.example.com/song.mp3[/audio]后文",
        )
        assertTrue(out.contains("gululu-music-cue"))
        assertTrue(out.contains("data-gululu-music-url="))
        assertTrue(out.contains("https://music.example.com/song.mp3"))
        assertFalse("原始 BBCode 不得残留", out.contains("[audio]"))
    }

    @Test
    fun `audio cue text enters coordinates`() {
        // cue 文本进坐标（骨碌碌同款：提取器与 JS TextPos 同源，搜索索引不漂移）
        val out = NgaFormatHtml.renderContentHtml(
            "[audio]https://music.example.com/song.mp3[/audio]",
        )
        assertFalse(out.contains("data-textpos-exclude"))
    }

    @Test
    fun `cleartext http audio kept as plain text`() {
        val raw = "[audio]http://music.example.com/song.mp3[/audio]"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue("明文外链保留原文（播放桥只收 https）", out.contains(raw))
        assertFalse(out.contains("gululu-music-cue"))
    }

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
