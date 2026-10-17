package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `attachment audio becomes music cue`() {
        val raw = "<span class=\"audio\" onclick=\"audioClick(event)\"> <audio " +
            "src=\"https://img.nga.cn/attachments/mon_202410/09/lsQtoqh-1i29Xu.mp3" +
            "?filename=01%2e%20a.mp3\" onended=\"audioEnd(event)\" " +
            "onerror=\"audioError(event)\" ></audio></span>"
        val out = NgaFormatHtml.renderContentHtml("前文" + raw + "后文")
        assertTrue(out.contains("gululu-music-cue"))
        assertTrue(out.contains("gululu-music-cue-attach"))
        assertTrue(out.contains("data-gululu-music-url="))
        assertFalse("不要显示附件音频字样", out.contains("附件音频"))
        // 标题只显示上传文件名：解码 filename 查询参数并去掉扩展名
        assertTrue(out.contains("gululu-music-title\">01. a</span>"))
        assertFalse(out.contains("<span class=\"audio\""))
    }

    @Test
    fun `attachment audio title falls back to path name`() {
        val raw = "<span class=\"audio\" onclick=\"audioClick(event)\"> <audio " +
            "src=\"https://img.nga.cn/attachments/mon_1/abc.mp3\"></audio></span>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue(out.contains("data-gululu-music-url=\"https://img.nga.cn/attachments/mon_1/abc.mp3\""))
        assertTrue(out.contains("<span class=\"gululu-music-title\">abc</span>"))
    }

    @Test
    fun `attachment audio cue text enters coordinates`() {
        val raw = "<span class=\"audio\" onclick=\"audioClick(event)\"> <audio " +
            "src=\"https://img.nga.cn/attachments/x.mp3\"></audio></span>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertFalse(out.contains("data-textpos-exclude"))
    }

    @Test
    fun `attachment audio self closing becomes music cue`() {
        val raw = "<span class=\"audio\" onclick=\"audioClick(event)\">" +
            "<audio src=\"https://img.nga.cn/x.mp3\" /></span>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue(out.contains("gululu-music-cue"))
        assertTrue(out.contains("data-gululu-music-url=\"https://img.nga.cn/x.mp3\""))
    }

    @Test
    fun `cleartext http attachment audio kept as plain text`() {
        val raw = "<span class=\"audio\" onclick=\"audioClick(event)\"> <audio " +
            "src=\"http://img.nga.cn/x.mp3\"></audio></span>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue(out.contains(raw))
        assertFalse(out.contains("gululu-music-cue"))
    }

    @Test
    fun `dice folds detail into details summary`() {
        val raw = "<div class='dice'><b>ROLL : d2</b>=d2(2)=<b>2</b></div>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue(out.contains("<details class=\"nga-dice\""))
        assertTrue(out.contains("<summary"))
        assertTrue(out.contains("ROLL : d2= <b>2</b>"))
        assertTrue("详细骰点进折叠内容", out.contains("d2(2)"))
        assertFalse("原始 dice div 不残留", out.contains("class='dice'"))
    }

    @Test
    fun `multi dice folds detail`() {
        val raw = "<div class='dice'><b>ROLL : 4d10</b>=d10(9)+d10(10)+d10(3)+d10(4)=<b>26</b></div>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertTrue(out.contains("ROLL : 4d10= <b>26</b>"))
        assertTrue(out.contains("d10(9)+d10(10)+d10(3)+d10(4)"))
    }

    @Test
    fun `dice detail enters coordinates`() {
        // 详细骰点进坐标（与 NGA 折叠一致，不加 data-textpos-exclude）
        val raw = "<div class='dice'><b>ROLL : d2</b>=d2(2)=<b>2</b></div>"
        val out = NgaFormatHtml.renderContentHtml(raw)
        assertFalse(out.contains("data-textpos-exclude"))
    }

    @Test
    fun `smile uses live host`() {
        // img4.nga.178.com 已下线（NXDOMAIN），表情图源归一到 img4.nga.cn。
        val out = NgaFormatHtml.renderContentHtml("[s:ac:blink]")
        assertTrue(out.contains("https://img4.nga.cn/ngabbs/post/smile/ac0.png"))
        assertFalse(out.contains("nga.178.com"))
    }

    @Test
    fun `collapse conversion matches shared fixture`() {
        // 折叠块转换契约（双端共享夹具）：内容含 </span></div> 紧邻
        // （[size]×[/align] 的 NGA 渲染）不得截断；Windows 侧由
        // tests/test_nga_collapse.py 消费同一夹具。
        val repoRoot = run {
            var dir: java.io.File? = java.io.File("").absoluteFile
            while (dir != null && !java.io.File(dir, "contracts").isDirectory) dir = dir.parentFile
            dir ?: error("contracts/ 未找到")
        }
        val fixture = java.io.File(repoRoot, "contracts/fixtures/nga-format/collapse-cases.json")
        assertTrue("夹具缺失：$fixture", fixture.isFile)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(fixture.readText(Charsets.UTF_8)).jsonObject
        val cases = root["cases"]!!.jsonArray
        assertTrue("夹具用例数应覆盖核心边界", cases.size >= 5)
        for (element in cases) {
            val c = element.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val input = c["input"]!!.jsonPrimitive.content
            val expected = c["expected"]!!.jsonPrimitive.content
            assertEquals("折叠转换不符：$name", expected, NgaFormatHtml.renderContentHtml(input))
        }
    }
}
