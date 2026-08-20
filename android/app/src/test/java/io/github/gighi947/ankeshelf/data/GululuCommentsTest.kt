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
 * 骨碌碌评论的**双端 golden 对照**：公开字段裁剪与 EPUB 评论块渲染
 * （夹具 `contracts/fixtures/gululu/ast-cases.json` 的 comment_cases，
 * Windows 侧见 `tests/test_contracts.py::GululuAstFixtureTest.test_comment_cases_match_fixture`）。
 */
class GululuCommentsTest {

    private val repoRoot: File = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, ".git").exists() && d.parentFile != null) d = d.parentFile
        d
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `评论渲染与公开字段与双端夹具一致`() {
        val fixture = File(repoRoot, "contracts/fixtures/gululu/ast-cases.json")
        val cases = json.parseToJsonElement(fixture.readText()).jsonObject["comment_cases"]!!.jsonArray
        assertTrue("评论用例应覆盖子回复/转义/作品评论/空块", cases.size >= 4)

        for (element in cases) {
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val comments = case["comments"]!!.jsonArray.map { it.jsonObject }
            val label = case["label"]!!.jsonPrimitive.content
            val opus = case["opus"]!!.jsonPrimitive.content == "true"

            assertEquals(
                "用例 $id 的评论块 HTML",
                case["expected_html"]!!.jsonPrimitive.content,
                GululuComments.renderCommentBlock(comments, label, opus),
            )

            val expectedPublic = case["expected_public"]!!.jsonArray.map { it.jsonObject }
            val gotPublic = comments.map { GululuComments.commentToPublic(it) }
            assertEquals("用例 $id 的公开字段条数", expectedPublic.size, gotPublic.size)
            expectedPublic.forEachIndexed { i, want ->
                assertEquals("用例 $id 第 $i 条公开字段", want.toString(), gotPublic[i].toString())
            }
        }
    }

    @Test
    fun `缺少 id 或正文的评论显式失败`() {
        val noId = json.parseToJsonElement("""{"content":"x"}""").jsonObject
        val noContent = json.parseToJsonElement("""{"id":1}""").jsonObject
        assertTrue(
            runCatching { GululuComments.commentToPublic(noId) }.exceptionOrNull()
                is GululuCommentFormatException,
        )
        assertTrue(
            runCatching { GululuComments.commentToPublic(noContent) }.exceptionOrNull()
                is GululuCommentFormatException,
        )
        assertTrue(
            runCatching {
                GululuComments.renderCommentBlock(listOf(noContent), "评论")
            }.exceptionOrNull() is GululuCommentFormatException,
        )
    }

    @Test
    fun `公开字段不包含原始用户对象`() {
        val raw = json.parseToJsonElement(
            """
            {"id":9,"content":"c","fromUser":{"nickName":"甲","uid":123,"avatar":"https://x/y.png"},
             "createTime":"2026-08-01","likeNum":2}
            """.trimIndent(),
        ).jsonObject
        val public = GululuComments.commentToPublic(raw).toString()
        assertTrue("必须保留昵称", public.contains("甲"))
        assertTrue("不得写入 uid", !public.contains("123"))
        assertTrue("不得写入头像地址", !public.contains("avatar"))
    }
}
