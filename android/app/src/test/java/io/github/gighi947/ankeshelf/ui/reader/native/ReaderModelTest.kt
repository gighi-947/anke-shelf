package io.github.gighi947.ankeshelf.ui.reader.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
