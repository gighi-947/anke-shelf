package io.github.gighi947.ankeshelf.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 楼层导出渲染等待循环回归（2026-08-27 审查清理）。
 *
 * 修复前的行为：超时 deadline 走完后不报错，heightCss 可能为 0 →
 * coerceAtLeast(1000) → 静默导出一张 1000px 空图且状态仍是"成功"。
 * 修复后：等待结论必须显式（settled / timeout），超时由调用方转为
 * FloorRenderTimeoutException（调用方的 catch 已上浮失败消息）。
 * 循环抽取为注入式纯函数（poll/snapshot/timedOut/sleep 可替换），JVM 可测。
 */
class FloorExportWaitTest {

    @Test
    fun `就绪后返回 true 且轮询到就绪为止`() = runBlocking {
        var polls = 0
        val settled = FloorExportWait.awaitReady(
            poll = { polls++ },
            snapshot = { if (polls >= 3) 0 to true else 2 to false },
            timedOut = { false },
            sleep = { },
        )
        assertTrue("就绪必须返回 true", settled)
        assertEquals("轮询恰好在就绪那次停止", 3, polls)
    }

    @Test
    fun `超时返回 false 而不是无限等待`() = runBlocking {
        var slept = 0
        val settled = FloorExportWait.awaitReady(
            poll = { },
            snapshot = { 3 to false }, // 永不就绪（模拟挂死加载）
            timedOut = { slept >= 5 },
            sleep = { slept++ },
        )
        assertFalse("超时必须返回 false", settled)
        assertTrue("必须经 sleep 让步后才判超时", slept >= 5)
    }

    @Test
    fun `桥未上报时以短间隔继续轮询并最终就绪`() = runBlocking {
        var polls = 0
        val settled = FloorExportWait.awaitReady(
            poll = { polls++ },
            snapshot = { if (polls >= 2) 0 to true else -1 to false },
            timedOut = { false },
            sleep = { },
        )
        assertTrue(settled)
        assertEquals(2, polls)
    }
}
