package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 评论缓存与离线回退：本链路的核心不变量是**离线可读**——
 * 网络失败时有缓存必须回退为 stale 而不是报错，完全没缓存才算硬失败。
 */
class GululuCommentServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun paths(): AppPaths = AppPaths(tmp.root).also { it.ensure() }

    private fun rawComment(id: Int, content: String): JsonObject =
        json.parseToJsonElement(
            """{"id":$id,"content":"$content","fromUser":{"nickName":"甲"},"createTime":"2026-08-01"}""",
        ) as JsonObject

    /** 假抓取器：记录调用次数，可指定失败。 */
    private class FakeFetch(
        private val response: Map<Int, List<JsonObject>>,
        private val failure: Exception? = null,
    ) {
        var calls = 0
            private set

        fun asFunction(): (Int, List<Int>) -> Map<Int, List<JsonObject>> = { _, floorIds ->
            calls++
            failure?.let { throw it }
            response.filterKeys { it in floorIds }
        }
    }

    @Test
    fun `首次读取写缓存_二次读取命中缓存不再请求网络`() {
        val fake = FakeFetch(mapOf(0 to listOf(rawComment(1, "作品评论"))))
        val service = GululuCommentService(paths(), fake.asFunction())

        val first = service.getComments(48856, listOf(0))
        assertTrue(first.ok)
        assertEquals(1, first.floors.single().comments.size)
        assertFalse("首次是网络结果，不算命中缓存", first.floors.single().cached)
        assertEquals(1, fake.calls)

        val second = service.getComments(48856, listOf(0))
        assertTrue(second.ok)
        assertTrue("5 分钟内应命中缓存", second.floors.single().cached)
        assertEquals("不应再请求网络", 1, fake.calls)
        assertEquals(
            "作品评论",
            second.floors.single().comments.single()["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `缓存过期后刷新_失败则回退 stale 且保留旧评论`() {
        val appPaths = paths()
        var now = System.currentTimeMillis()
        val ok = FakeFetch(mapOf(7 to listOf(rawComment(11, "旧评论"))))
        assertTrue(GululuCommentService(appPaths, ok.asFunction()) { now }.getComments(48856, listOf(7)).ok)

        now += 10 * 60 * 1000
        val failing = FakeFetch(emptyMap(), IllegalStateException("网络不可达"))
        val result = GululuCommentService(appPaths, failing.asFunction()) { now }
            .getComments(48856, listOf(7))

        assertTrue("有缓存就不能算硬失败", result.ok)
        val scope = result.floors.single()
        assertTrue("必须标记为过期数据", scope.stale)
        assertTrue(scope.cached)
        assertEquals("网络不可达", scope.error)
        assertEquals("旧评论", scope.comments.single()["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `无缓存且网络失败才算硬失败`() {
        val failing = FakeFetch(emptyMap(), IllegalStateException("连接超时"))
        val result = GululuCommentService(paths(), failing.asFunction()).getComments(48856, listOf(5))
        assertFalse(result.ok)
        val scope = result.floors.single()
        assertFalse(scope.cached)
        assertFalse(scope.stale)
        assertEquals("连接超时", scope.error)
        assertTrue(result.error.contains("5"))
    }

    @Test
    fun `作用域数量与取值必须合法`() {
        val service = GululuCommentService(paths(), FakeFetch(emptyMap()).asFunction())
        assertFalse("空列表非法", service.getComments(1, emptyList()).ok)
        assertFalse("超过 64 个作用域非法", service.getComments(1, (0..64).toList()).ok)
        assertFalse("负数楼层非法", service.getComments(1, listOf(-1)).ok)
    }

    @Test
    fun `强制刷新绕过新鲜缓存`() {
        val fake = FakeFetch(mapOf(3 to listOf(rawComment(31, "评论"))))
        val service = GululuCommentService(paths(), fake.asFunction())
        service.getComments(48856, listOf(3))
        service.getComments(48856, listOf(3), refresh = true)
        assertEquals(2, fake.calls)
    }
}
