package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 骨碌碌 AST → XHTML 的**双端 golden 对照**：夹具与期望值在
 * `contracts/fixtures/gululu/ast-cases.json`，Windows 侧同一份夹具由
 * `tests/test_contracts.py::GululuAstFixtureTest` 消费。
 *
 * 两端产物必须逐字符一致：同一本骨碌碌书在两端生成同构 EPUB，章节 XHTML 决定
 * `text_offset` 坐标，任何标签/空白差异都会让进度与标注错位。
 */
class GululuAstTest {

    private val repoRoot: File = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, ".git").exists() && d.parentFile != null) d = d.parentFile
        d
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AST 渲染与双端夹具逐字符一致`() {
        val fixture = File(repoRoot, "contracts/fixtures/gululu/ast-cases.json")
        val cases = json.parseToJsonElement(fixture.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue("夹具用例数应覆盖核心节点", cases.size >= 15)

        for (element in cases) {
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val mode = case["image_mode"]?.jsonPrimitive?.content ?: "online"
            val map = case["image_map"]?.jsonObject?.entries
                ?.associate { (k, v) -> k to v.jsonPrimitive.content }
                ?: emptyMap()
            val resolver: (String) -> String = when (mode) {
                "none" -> { _ -> "" }
                "embedded" -> { url -> map[url] ?: "" }
                else -> { url -> url }
            }
            val got = GululuAst.render(
                nodes = case["nodes"]!!.jsonArray.toList(),
                imageResolver = resolver,
            )
            assertEquals("用例 $id", case["expected"]!!.jsonPrimitive.content, got)
        }
    }

    @Test
    fun `完整楼层管线与双端夹具一致`() {
        // 沉浸指令 → 助手协议 → 骰点/迷雾 → 渲染：与桌面 _floor_html 同一条链路。
        val fixture = File(repoRoot, "contracts/fixtures/gululu/ast-cases.json")
        val root = json.parseToJsonElement(fixture.readText()).jsonObject
        val cases = root["floor_cases"]!!.jsonArray
        assertTrue("楼层用例数应覆盖助手与沉浸协议", cases.size >= 12)

        for (element in cases) {
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val floorId = case["floor_id"]!!.jsonPrimitive.content.toInt()
            val sourceBookId = case["source_book_id"]!!.jsonPrimitive.content.toInt()
            val jumpMap = case["jump_map"]?.jsonObject
                ?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content }
                ?: emptyMap()

            val immersive = GululuImmersive.prepareImmersiveFloor(case["nodes"])
            val html = GululuAst.render(
                nodes = GululuAssistant.prepareReaderExperienceNodes(immersive.nodes, floorId),
                imageResolver = { url -> url },
                jumpFloorResolver = { floor -> jumpMap[floor.toString()].orEmpty() },
                sourceBookId = sourceBookId,
                extensions = listOf(GululuAssistant.renderer(), GululuImmersive.renderer()),
                imageBackgroundAttr = { attrs -> GululuImmersive.backgroundAttribute(attrs) },
            )
            assertEquals("用例 $id", case["expected"]!!.jsonPrimitive.content, html)
            assertEquals(
                "用例 $id 的视效",
                case["expected_vfx"]?.jsonPrimitive?.content.orEmpty(),
                immersive.vfx,
            )
            if (case.containsKey("expected_background")) {
                assertEquals(
                    "用例 $id 的背景更新",
                    case["expected_background"]!!.jsonPrimitive.content,
                    immersive.backgroundUpdate,
                )
            } else {
                assertEquals("用例 $id 不应产生背景更新", null, immersive.backgroundUpdate)
            }
        }
    }

    @Test
    fun `秘密解密与桌面同算法`() {
        // CryptoJS.AES.encrypt("安科测试", "open123").toString() 的真实输出（OpenSSL salted）
        val cipher = "U2FsdGVkX1+8bK0Y1kSMR0z6cQGmVJXG3l0kQ0nQ2Yc="
        // 只验证"错误密码/损坏数据必须显式失败"，正确解密由 round-trip 用例覆盖
        val wrong = runCatching { GululuAssistant.decryptCryptoJsSecret(cipher, "bad") }.exceptionOrNull()
        assertTrue(wrong is GululuSecretException)
        val malformed = runCatching { GululuAssistant.decryptCryptoJsSecret("not-base64!", "x") }.exceptionOrNull()
        assertTrue(malformed is GululuSecretException)
        val empty = runCatching { GululuAssistant.decryptCryptoJsSecret("", "x") }.exceptionOrNull()
        assertTrue(empty is GululuSecretException)
        val noPassword = runCatching { GululuAssistant.decryptCryptoJsSecret(cipher, "") }.exceptionOrNull()
        assertTrue(noPassword is GululuSecretException)
    }

    @Test
    fun `strict 模式对未知节点显式失败`() {        val nodes = listOf(
            json.parseToJsonElement("""{"type":"videoBlock"}"""),
        )
        val relaxed = GululuAst.render(nodes)
        assertTrue(relaxed.contains("unsupported-node"))
        val error = runCatching { GululuAst.render(nodes, strict = true) }.exceptionOrNull()
        assertTrue("strict 模式必须抛 GululuFormatException", error is GululuFormatException)
    }

    @Test
    fun `扩展点先于核心渲染接管节点`() {
        // 批 5 的助手/沉浸协议就按这条路径接入：核心渲染不变，扩展返回非 null 即接管。
        val renderer = GululuNodeRenderer { type, attrs, children, _, _ ->
            if (type == "gululuSecret") "<secret title=\"${attrs["title"]!!.jsonPrimitive.content}\">${children()}</secret>" else null
        }
        val nodes = listOf(
            json.parseToJsonElement(
                """{"type":"gululuSecret","attrs":{"title":"谜"},"content":[{"type":"text","text":"内"}]}""",
            ),
            json.parseToJsonElement("""{"type":"paragraph","content":[{"type":"text","text":"正文"}]}"""),
        )
        assertEquals(
            "<secret title=\"谜\">内</secret><p>正文</p>",
            GululuAst.render(nodes, extensions = listOf(renderer)),
        )
    }

    @Test
    fun `安全颜色只接受 hex 与范围内 rgb`() {
        assertEquals("#abc", GululuAst.safeColor("#abc"))
        assertEquals("#AABBCC", GululuAst.safeColor(" #AABBCC "))
        assertEquals("rgb(0, 128, 255)", GululuAst.safeColor("rgb(0,128,255)"))
        assertEquals("", GululuAst.safeColor("rgb(0,128,256)"))
        assertEquals("", GululuAst.safeColor("red"))
        assertEquals("", GululuAst.safeColor("url(javascript:alert(1))"))
        assertEquals("", GululuAst.safeColor(null))
    }
}
