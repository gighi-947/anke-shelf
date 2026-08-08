package io.github.gighi947.ankeshelf.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NgaClientTest {

    @Test
    fun parsePage_goldenFixture() {
        val loader = checkNotNull(javaClass.classLoader)
        val url = checkNotNull(loader.getResource("reference/nga/page1.json"))
        val body = File(url.toURI()).readText(Charsets.UTF_8)
        val page = NgaClient().parsePageFull(tid = 41989465, body = body)
        assertEquals(0, page.code)
        assertEquals("安科书架测试帖", page.title)
        assertEquals(3, page.totalPage)
        assertEquals(21, page.vrows)
        assertEquals(2, page.floors.size)
        val main = page.floors[0]
        assertEquals(0L, main.pid)
        assertEquals(1, main.lou)
        assertEquals("楼主", main.username)
        assertTrue(main.raw_content.contains("这是主楼"))
        val second = page.floors[1]
        assertEquals(2, second.lou)
        assertEquals(1, second.comments.size)
    }

    /** 真实连通性 spike：默认跳过，设置环境变量 NGA_SPIKE=1 时执行。 */
    @Test
    fun realConnectivitySpike() {
        assumeTrue("1" == System.getenv("NGA_SPIKE"))
        val client = NgaClient(
            cookieUid = System.getenv("NGA_UID").orEmpty(),
            cookieCid = System.getenv("NGA_CID").orEmpty(),
            userAgent = System.getenv("NGA_UA").ifBlank { NgaClient.DEFAULT_NGA_UA },
        )
        val page = client.fetchPageFull(tid = 41989465, page = 1)
        println(
            "NGA_SPIKE code=${page.code} msg=${page.msg} title=${page.title} author=${page.author} " +
                "totalPage=${page.totalPage} vrows=${page.vrows} floors=${page.floors.size}",
        )
        // code=0 正常；code=46 为未带登录 Cookie 被 NGA 要求登录（桌面端同样需 uid/cid）。
        assertTrue(
            "NGA 返回异常 code=${page.code} msg=${page.msg}（uid/cid 是否有效？）",
            page.code == 0 || page.code == 46,
        )
    }
}
