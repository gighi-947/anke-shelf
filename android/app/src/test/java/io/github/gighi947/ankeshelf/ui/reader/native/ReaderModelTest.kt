package io.github.gighi947.ankeshelf.ui.reader.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gighi947.ankeshelf.ui.reader.extractReaderParts
import java.io.File

class ReaderModelTest {

    private val sample = """
        <div class="nga-floor" id="pid100">
          <div class="floor-head"><span class="lou">3楼</span> · 2赞 · 测试用户(42) · 2026-01-01 10:00:00</div>
          <div class="floor-body">
            <p>开头 <span style="color:#ff0000">红色</span> 与 <font color="blue">蓝色</font></p>
            <div class="nga-quote"><p>引用内容</p></div>
            <div class="nga-dice"><b>ROLL : 1d20</b>=<b>17</b></div>
            <img class="nga-img" src="https://img.nga.cn/a.jpg" alt="图"/>
            <table><tr><td>甲</td><td>乙</td></tr></table>
          </div>
        </div>
        <div class="nga-comment"><span class="comment-head">1楼 · 路人</span>追评文字</div>
    """.trimIndent()

    @Test
    fun parsesFloorQuoteDiceTableAndColors() {
        val doc = ReaderHtmlModel.parse(sample)
        assertTrue("楼层", doc.blocks.any { it is ReaderBlock.Floor })
        val floor = doc.blocks.filterIsInstance<ReaderBlock.Floor>().first()
        assertEquals(3, floor.lou)
        assertEquals(2, floor.likes)
        assertEquals("测试用户", floor.username)
        assertEquals(42L, floor.userId)
        assertTrue("引用", floor.body.any { it is ReaderBlock.Quote })
        assertTrue("骰子", floor.body.any { it is ReaderBlock.Dice })
        assertTrue("表格", floor.body.any { it is ReaderBlock.Table })
        assertTrue("图片", floor.body.any { it is ReaderBlock.Image })
        assertTrue("追评", doc.blocks.any { it is ReaderBlock.Comment })

        val paras = floor.body.filterIsInstance<ReaderBlock.Paragraph>()
        val colors = paras.flatMap { it.spans }.mapNotNull { it.color }
        assertTrue("红色 span 颜色", colors.contains("#ff0000"))
        assertTrue("font color 颜色", colors.contains("blue"))
        assertTrue("纯文本偏移", doc.plainText.isNotBlank())
        assertTrue("偏移表", doc.blockOffsets.size == doc.blocks.size + 1)
    }

    @Test
    fun classAttrWithExtraWhitespaceStillParsesFloor() {
        val doc = ReaderHtmlModel.parse(
            """<div class="nga-floor " id="pid1">
                 <div class=" floor-head "><span class="lou">5楼</span> · 0赞 · u(9) · t</div>
                 <div class=" floor-body "><p>正文</p></div>
               </div>""",
        )
        val floor = doc.blocks.filterIsInstance<ReaderBlock.Floor>().firstOrNull()
        assertTrue("带空格 class 的楼层", floor != null)
        assertEquals(5, floor!!.lou)
        assertEquals("u", floor.username)
    }

    @Test
    fun cssClassColorsApplyToSpans() {
        val doc = ReaderHtmlModel.parse(
            """<p>普通 <span class="red">红字</span> 与 <span class="gray">灰字</span></p>""",
            styles = ".red { color: #ff0000; } .gray{color:#888888}",
        )
        val colors = doc.blocks
            .filterIsInstance<ReaderBlock.Paragraph>()
            .flatMap { it.spans }
            .mapNotNull { it.color }
        assertTrue("CSS 类颜色 red", colors.contains("#ff0000"))
        assertTrue("CSS 类颜色 gray", colors.contains("#888888"))
    }

    @Test
    fun parsesRealExportedChapter() {
        val url = checkNotNull(javaClass.classLoader.getResource("native/real0000.xhtml"))
        val html = File(url.toURI()).readText(Charsets.UTF_8)
        val parts = extractReaderParts(html)
        val doc = ReaderHtmlModel.parse(parts.body, parts.headStyles)
        println("REAL blocks=${doc.blocks.size} plainLen=${doc.plainText.length}")
        println("REAL floors=${doc.blocks.count { it is ReaderBlock.Floor }}")
        println("REAL quotes=${doc.blocks.count { it is ReaderBlock.Quote }}")
        println("REAL images=${countImages(doc.blocks)}")
        println("REAL colors=${collectColors(doc.blocks).distinct().take(12)}")
        assertTrue("真实章节应有楼层", doc.blocks.any { it is ReaderBlock.Floor })
    }

    @Test
    fun parsesRealMultiFloorChapter() {
        val url = checkNotNull(javaClass.classLoader.getResource("native/real0001.xhtml"))
        val html = File(url.toURI()).readText(Charsets.UTF_8)
        val parts = extractReaderParts(html)
        val doc = ReaderHtmlModel.parse(parts.body, parts.headStyles)
        val floors = doc.blocks.filterIsInstance<ReaderBlock.Floor>()
        println("REAL1 floors=${floors.size} blocks=${doc.blocks.size} plainLen=${doc.plainText.length}")
        println("REAL1 floorParas=${floors.firstOrNull()?.body?.count { it is ReaderBlock.Paragraph }}")
        println("REAL1 quotesInFloors=${floors.sumOf { f -> f.body.count { it is ReaderBlock.Quote } }}")
        println("REAL1 colors=${collectColors(doc.blocks).distinct().take(12)}")
        assertTrue("真实章节应有多楼层", floors.size >= 10)
        assertTrue("真实章节应有引用", floors.any { f -> f.body.any { it is ReaderBlock.Quote } })
    }

    @Test
    fun decodesHtmlEntitiesInTextAndAttrs() {
        val doc = ReaderHtmlModel.parse(
            """<p>It&#39;s &amp; &lt;tag&gt; &#x4E2D;&#25991;&nbsp;end</p>
               <img src="https://img.nga.cn/a.jpg?x=1&amp;y=2" alt="图"/>""",
        )
        val para = doc.blocks.filterIsInstance<ReaderBlock.Paragraph>().first()
        val text = para.spans.joinToString("") { it.text }
        assertEquals("It's & <tag> 中文 end", text.trim())
        val img = doc.blocks.filterIsInstance<ReaderBlock.Image>().firstOrNull()
        assertEquals("https://img.nga.cn/a.jpg?x=1&y=2", img?.src)
    }

    @Test
    fun commentHeadNotDuplicatedInBodySpans() {
        val doc = ReaderHtmlModel.parse(sample)
        val comment = doc.blocks.filterIsInstance<ReaderBlock.Comment>().first()
        assertEquals(1, comment.lou)
        assertTrue("追评头带用户名", comment.username.contains("路人"))
        val body = comment.spans.joinToString("") { it.text }
        assertEquals("追评文字", body)
        assertTrue("正文不含头文本", !body.contains("1楼"))
    }

    @Test
    fun linksAndStrikeThroughArePreserved() {
        val doc = ReaderHtmlModel.parse(
            """<p>跳转 <a href="#pid123">#123</a> 与 <del> 旧内容 </del><s>另一个</s></p>""",
        )
        val spans = doc.blocks.filterIsInstance<ReaderBlock.Paragraph>().first().spans
        val link = spans.firstOrNull { it.link != null }
        assertTrue("链接应保留 href", link != null && link.link == "#pid123")
        val del = spans.filter { it.text.contains("旧内容") }.firstOrNull()
        assertTrue("del 应标记删除线", del != null && del.strike && del.muted)
        val s = spans.firstOrNull { it.text.contains("另一个") }
        assertTrue("s 应标记删除线", s != null && s.strike && s.muted)
    }

    @Test
    fun quoteInlineContentMergesAndKeepsStyles() {
        val doc = ReaderHtmlModel.parse(
            """<div class="nga-floor" id="pid1">
                 <div class="floor-head"><span class="lou">1楼</span> · 0赞 · u(9) · t</div>
                 <div class="floor-body">
                   <blockquote class="nga-quote">
                     <br/>[观前提醒]<br/>
                     1.这是以<b>动画《Bang Dream It's Mygo!!!!!》</b>登场角色
                     <span style="color:#7ec8e3"><b>丰川祥子</b></span> 为主角、以
                     <del> 燃烧天堂 </del>炽焰天穹
                     <br/>2.第二段文字<br/>
                   </blockquote>
                 </div>
               </div>""",
        )
        val floor = doc.blocks.filterIsInstance<ReaderBlock.Floor>().first()
        val quote = floor.body.filterIsInstance<ReaderBlock.Quote>().firstOrNull()
        assertTrue("引用块应存在", quote != null)
        val paras = quote!!.body.filterIsInstance<ReaderBlock.Paragraph>()
        assertEquals("连续内联内容应合并为一个段落", 1, paras.size)
        val spans = paras.first().spans
        assertTrue("加粗保留", spans.any { it.bold && it.text.contains("Bang Dream") })
        assertTrue("颜色保留", spans.any { it.color == "#7ec8e3" && it.text.contains("丰川祥子") })
        assertTrue("删除线保留", spans.any { it.strike && it.muted && it.text.contains("燃烧天堂") })
        assertTrue("换行保留", spans.any { it.text.contains("\n") })
    }

    private fun countImages(blocks: List<ReaderBlock>): Int = blocks.sumOf { countImages(it) }

    private fun countImages(b: ReaderBlock): Int = when (b) {
        is ReaderBlock.Image -> 1
        is ReaderBlock.Floor -> b.body.sumOf { countImages(it) }
        is ReaderBlock.Quote -> b.body.sumOf { countImages(it) }
        else -> 0
    }

    private fun collectColors(blocks: List<ReaderBlock>): List<String> = blocks.flatMap { collectColors(it) }

    private fun collectColors(b: ReaderBlock): List<String> = when (b) {
        is ReaderBlock.Paragraph -> b.spans.mapNotNull { it.color }
        is ReaderBlock.Floor -> b.body.flatMap { collectColors(it) }
        is ReaderBlock.Quote -> b.body.flatMap { collectColors(it) }
        is ReaderBlock.Comment -> b.spans.mapNotNull { it.color }
        else -> emptyList()
    }
}
