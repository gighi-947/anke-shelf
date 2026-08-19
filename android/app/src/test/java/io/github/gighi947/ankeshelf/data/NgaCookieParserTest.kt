package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NgaCookieParserTest {

    @Test
    fun `parses full cookie header`() {
        val parsed = parseNgaCookieText("ngaPassportUid=12345; ngaPassportCid=abcdef; other=x")
        assertEquals("12345", parsed.uid)
        assertEquals("abcdef", parsed.cid)
    }

    @Test
    fun `parses cookie names inside arbitrary text`() {
        val parsed = parseNgaCookieText(
            "点击链接阅读：https://bbs.nga.cn/read.php?tid=1 " +
                "ngaPassportUid=9876 ngaPassportCid=xyz",
        )
        assertEquals("9876", parsed.uid)
        assertEquals("xyz", parsed.cid)
    }

    @Test
    fun `is case insensitive`() {
        val parsed = parseNgaCookieText("NGAPASSPORTUID=111; ngapassportcid=222")
        assertEquals("111", parsed.uid)
        assertEquals("222", parsed.cid)
    }

    @Test
    fun `handles quoted values`() {
        val parsed = parseNgaCookieText("""ngaPassportUid="333"; ngaPassportCid="444"""")
        assertEquals("333", parsed.uid)
        assertEquals("444", parsed.cid)
    }

    @Test
    fun `missing fields return empty strings`() {
        val parsed = parseNgaCookieText("")
        assertEquals("", parsed.uid)
        assertEquals("", parsed.cid)
    }
}
