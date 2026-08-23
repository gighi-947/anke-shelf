package io.github.gighi947.ankeshelf.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * NGA 内嵌图片批量下载核心回归（2026-08-22 修复）。
 *
 * 修复前的行为：串行下载、零进度上报（前台通知停在楼层阶段，看起来
 * "无进度且卡住"）、imageHttp 无超时（慢速滴流可无限拖住任务）。
 * 修复后的核心语义由本测试锁定。
 */
class NgaImageDownloadsTest {

    private data class Progress(val done: Int, val total: Int, val ok: Int, val failed: Int)

    @Test
    fun `逐张完成即上报进度，单图失败不中断`() {
        val loads = AtomicInteger()
        val progressList = mutableListOf<Progress>()
        val ok = NgaImageDownloads.drain(
            urls = listOf("a", "b", "c"),
            isCached = { false },
            load = { loads.incrementAndGet(); if (it == "b") null else ByteArray(1) },
            persist = { _, _ -> },
            onProgress = { done, total, pOk, pFailed ->
                progressList.add(Progress(done, total, pOk, pFailed))
            },
            isCancelled = { false },
            perResultTimeoutSec = 10,
        )
        assertTrue("全部完成应返回 true", ok)
        assertEquals(3, loads.get())
        // 逐张上报：起点 0（阶段切换信号，通知文案立即变为图片阶段）
        // + 每张完成 1..3（这是"进度可见"的核心断言）
        assertEquals(listOf(0, 1, 2, 3), progressList.map { it.done })
        assertEquals(3, progressList.last().total)
        assertEquals(2, progressList.last().ok)
        assertEquals(1, progressList.last().failed)
    }

    @Test
    fun `已缓存图片跳过下载且计入进度起点`() {
        val loads = AtomicInteger()
        val progressList = mutableListOf<Progress>()
        NgaImageDownloads.drain(
            urls = listOf("a", "b", "c"),
            isCached = { it != "a" }, // b、c 已缓存
            load = { loads.incrementAndGet(); ByteArray(1) },
            persist = { _, _ -> },
            onProgress = { done, total, pOk, _ ->
                progressList.add(Progress(done, total, pOk, 0))
            },
            isCancelled = { false },
            perResultTimeoutSec = 10,
        )
        assertEquals("缓存图不得重复下载", 1, loads.get())
        // 缓存 2 张是已完成进度：首次上报 done=2（缓存数）+ 之后逐张
        assertEquals(listOf(2, 3), progressList.map { it.done })
        assertEquals(3, progressList.last().total)
        assertEquals("缓存计入成功数", 3, progressList.last().ok)
    }

    @Test
    fun `取消后立即停止且不再发起下载`() {
        val loads = AtomicInteger()
        val completed = NgaImageDownloads.drain(
            urls = listOf("a", "b"),
            isCached = { false },
            load = { loads.incrementAndGet(); ByteArray(1) },
            persist = { _, _ -> },
            onProgress = { _, _, _, _ -> },
            isCancelled = { true },
            perResultTimeoutSec = 10,
        )
        assertFalse("取消必须返回 false", completed)
        assertEquals("取消后不得继续派发下载", 0, loads.get())
    }

    @Test
    fun `结果迟迟不返回时按超时终止不无限等待`() {
        // load 永不返回（模拟慢速滴流/挂死连接）；perResultTimeoutSec 极小，
        // drain 必须在有限时间内终止（返回 false，剩余计失败）。
        val start = System.currentTimeMillis()
        val completed = NgaImageDownloads.drain(
            urls = listOf("a", "b"),
            isCached = { false },
            load = { Thread.sleep(60_000); ByteArray(1) },
            persist = { _, _ -> },
            onProgress = { _, _, _, _ -> },
            isCancelled = { false },
            perResultTimeoutSec = 1,
        )
        val elapsed = System.currentTimeMillis() - start
        assertFalse("超时必须终止并返回 false", completed)
        assertTrue("不得等满 60s（实测 ${elapsed}ms）", elapsed < 30_000)
    }
}
