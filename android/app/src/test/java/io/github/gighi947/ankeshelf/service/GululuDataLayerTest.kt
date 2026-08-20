package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.GululuIdResult
import io.github.gighi947.ankeshelf.data.GululuSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨碌碌数据层（批 4）：来源识别、接口信封校验、楼层合并、图片三态。
 * 业务不变量优先：**缺失楼层必须显式失败**、**只接受 HTTPS 且按签名判类型**、
 * 资源命名双端一致（EPUB 结构互通的前提）。
 */
class GululuDataLayerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------- 来源识别（对齐 app/gululu_source.py） ----------

    @Test
    fun `书籍 ID 与公开链接都能识别`() {
        assertEquals(GululuIdResult.Ok(48856), GululuSource.parseBookId("48856"))
        assertEquals(GululuIdResult.Ok(48856), GululuSource.parseBookId(" https://www.gululu.world/book/48856 "))
        assertEquals(GululuIdResult.Ok(48856), GululuSource.parseBookId("https://gululu.world/book/48856/"))
    }

    @Test
    fun `非法来源给出可读错误而不是静默失败`() {
        assertTrue(GululuSource.parseBookId("0") is GululuIdResult.Err)
        assertTrue(GululuSource.parseBookId("-3") is GululuIdResult.Err)
        assertTrue(GululuSource.parseBookId("http://www.gululu.world/book/1") is GululuIdResult.Err)
        assertTrue(GululuSource.parseBookId("https://evil.example/book/1") is GululuIdResult.Err)
        assertTrue(GululuSource.parseBookId("https://www.gululu.world/chat/1") is GululuIdResult.Err)
    }

    @Test
    fun `从整段文本提取链接_多个链接要求用户取舍`() {
        assertEquals(
            GululuIdResult.Ok(48856),
            GululuSource.extractBookId("点击链接阅读：https://www.gululu.world/book/48856 谢谢"),
        )
        val multi = GululuSource.extractBookId(
            "https://www.gululu.world/book/1 和 https://www.gululu.world/book/2",
        )
        assertTrue(multi is GululuIdResult.Err)
        assertTrue((multi as GululuIdResult.Err).message.contains("多个"))
        assertTrue(GululuSource.extractBookId("没有链接") is GululuIdResult.Err)
    }

    @Test
    fun `EPUB dc identifier 识别骨碌碌来源`() {
        assertEquals(48856, GululuSource.parseGululuIdentifier("gululu-48856"))
        assertNull(GululuSource.parseGululuIdentifier("gululu-0"))
        assertNull(GululuSource.parseGululuIdentifier("nga-48856"))
        assertNull(GululuSource.parseGululuIdentifier(null))
    }

    // ---------- 接口信封与楼层合并（对齐 app/gululu_client.py） ----------

    @Test
    fun `信封校验区分格式错误_业务失败_缺 data`() {
        val client = GululuClient()
        assertEquals(
            200,
            (client.parseDataPayload("/x", """{"code":200,"data":{"ok":1}}""") as JsonObject)
                .let { 200 },
        )
        val cases = listOf(
            "not json" to "响应格式错误",
            """{"code":500,"msg":"服务器繁忙"}""" to "服务器繁忙",
            """{"code":200}""" to "缺少 data",
        )
        for ((body, hint) in cases) {
            val error = runCatching { client.parseDataPayload("/x", body) }.exceptionOrNull()
            assertTrue("应显式失败：$body", error is GululuApiException)
            assertTrue("错误文案应含「$hint」：${error?.message}", error!!.message!!.contains(hint))
        }
    }

    @Test
    fun `索引校验_章节目录允许缺省_楼层目录条目必须带 floorId`() {
        val client = GululuClient()
        val detail = json.parseToJsonElement("""{"bookId":1,"name":"书"}""")
        val floorIndex = json.parseToJsonElement("""[{"floorId":11,"floorNum":1},{"floorId":12,"floorNum":2}]""")

        val withChapters = client.parseIndexPayloads(
            detail,
            floorIndex,
            json.parseToJsonElement("""{"chapterIndex":[{"floor":1,"title":"第一章"}]}"""),
        )
        assertEquals(2, withChapters.floorIndex.size)
        assertEquals(1, withChapters.chapterIndex.size)

        // 作者未分章：chapterIndex 为 null / 缺省都合法
        assertTrue(client.parseIndexPayloads(detail, floorIndex, json.parseToJsonElement("{}")).chapterIndex.isEmpty())
        assertTrue(client.parseIndexPayloads(detail, floorIndex, null).chapterIndex.isEmpty())

        val bad = runCatching {
            client.parseIndexPayloads(detail, json.parseToJsonElement("""[{"floorNum":1}]"""), null)
        }.exceptionOrNull()
        assertTrue(bad is GululuApiException)
    }

    @Test
    fun `楼层合并按入参顺序_缺失楼层显式失败`() {
        val client = GululuClient()
        val batch1 = json.parseToJsonElement("""[{"id":12,"paragraphContents":[]},{"id":11,"paragraphContents":[]}]""")
        val merged = client.mergeFloorBatches(listOf(11, 12), listOf(batch1))
        assertEquals(
            listOf(11, 12),
            merged.map { it["id"]!!.jsonPrimitive.content.toInt() },
        )

        val error = runCatching {
            client.mergeFloorBatches(listOf(11, 12, 13), listOf(batch1))
        }.exceptionOrNull()
        assertTrue(error is GululuApiException)
        assertTrue(error!!.message!!.contains("13"))
    }

    // ---------- 图片三态（对齐 app/gululu_images.py） ----------

    @Test
    fun `图片模式解析与非法取值`() {
        assertEquals(GululuImageMode.ONLINE, GululuImageMode.fromWire(null))
        assertEquals(GululuImageMode.ONLINE, GululuImageMode.fromWire(" ONLINE "))
        assertEquals(GululuImageMode.EMBEDDED, GululuImageMode.fromWire("embedded"))
        assertEquals(GululuImageMode.NONE, GululuImageMode.fromWire("none"))
        assertNull(GululuImageMode.fromWire("inline"))
    }

    @Test
    fun `收集正文图片_仅 HTTPS_去重且保序`() {
        val floors = listOf(
            json.parseToJsonElement(
                """
                {"id":1,"paragraphContents":[
                  {"type":"paragraph","content":[
                    {"type":"image","attrs":{"src":"https://img.example/a.png"}},
                    {"type":"image","attrs":{"src":"http://img.example/insecure.png"}}
                  ]},
                  {"type":"image","attrs":{"src":"https://img.example/b.png"}}
                ]}
                """.trimIndent(),
            ).jsonObject,
            json.parseToJsonElement(
                """{"id":2,"paragraphContents":[{"type":"image","attrs":{"src":"https://img.example/a.png"}}]}""",
            ).jsonObject,
        )
        assertEquals(
            listOf("https://img.example/a.png", "https://img.example/b.png"),
            GululuImages.collectImageUrls(floors),
        )
    }

    @Test
    fun `按文件签名判类型_不信 HTTP 头`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0)
        val gif = "GIF89a".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        val html = "<html>not an image".toByteArray()
        assertEquals("image/png" to "png", GululuImages.detectImage(png))
        assertEquals("image/jpeg" to "jpg", GululuImages.detectImage(jpg))
        assertEquals("image/gif" to "gif", GululuImages.detectImage(gif))
        assertEquals("image/webp" to "webp", GululuImages.detectImage(webp))
        assertNull("伪装成图片的 HTML 必须拒绝", GululuImages.detectImage(html))
    }

    @Test
    fun `内嵌资源命名与桌面一致`() {
        // sha256("https://img.example/a.png") 前 16 位十六进制（双端同规则，EPUB 结构互通的前提）
        val name = GululuImages.embeddedFileName("https://img.example/a.png", "png")
        assertTrue(name.startsWith("images/"))
        assertTrue(name.endsWith(".png"))
        assertEquals("images/".length + 16 + ".png".length, name.length)
        assertEquals(name, GululuImages.embeddedFileName("https://img.example/a.png", "png"))
        assertTrue(name != GululuImages.embeddedFileName("https://img.example/b.png", "png"))
    }

    @Test
    fun `内嵌准备_成功与逐张失败并存`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val batch = GululuImages.prepareEmbeddedImages(
            urls = listOf(
                "https://img.example/ok.png",
                "https://img.example/bad.png",
                "https://img.example/boom.png",
            ),
            fetcher = { url ->
                when {
                    url.endsWith("ok.png") -> png to "image/png"
                    url.endsWith("bad.png") -> "<html>".toByteArray() to "image/png"
                    else -> throw IllegalStateException("连接超时")
                }
            },
        )
        assertEquals(1, batch.resources.size)
        assertEquals(2, batch.failures.size)
        assertTrue(batch.failures.any { it.error == GululuImages.UNSUPPORTED_MESSAGE })
        assertTrue(batch.failures.any { it.error == "连接超时" })
        assertEquals(
            mapOf("https://img.example/ok.png" to "../${batch.resources[0].fileName}"),
            GululuImages.embeddedSources(batch),
        )
    }

    @Test
    fun `内嵌准备可取消`() {
        val error = runCatching {
            GululuImages.prepareEmbeddedImages(
                urls = listOf("https://img.example/a.png"),
                fetcher = { _ -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) to "" },
                cancel = { true },
            )
        }.exceptionOrNull()
        assertTrue(error is GululuImageCancelled)
    }

    @Test
    fun `三态 resolver 与桌面三分支一致`() {
        val embedded = mapOf("https://img.example/a.png" to "../images/x.png")
        assertEquals(
            "https://img.example/a.png",
            GululuImages.resolverFor(GululuImageMode.ONLINE, embedded)("https://img.example/a.png"),
        )
        assertEquals("", GululuImages.resolverFor(GululuImageMode.NONE, embedded)("https://img.example/a.png"))
        assertEquals(
            "../images/x.png",
            GululuImages.resolverFor(GululuImageMode.EMBEDDED, embedded)("https://img.example/a.png"),
        )
        assertEquals(
            "",
            GululuImages.resolverFor(GululuImageMode.EMBEDDED, embedded)("https://img.example/missing.png"),
        )
    }
}
