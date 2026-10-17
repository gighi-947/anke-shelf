package io.github.gighi947.ankeshelf.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NGA 轻量更新检查的纯逻辑（NgaDownloader.estimateNewFloors）：
 * 断点页 lou 与远端总页数的组合情形 + 占位楼/负页数差/无基线（maxFloor<0）边界。
 */
class NgaUpdateCheckTest {

    @Test
    fun `无新增时为 0`() {
        // 同页没有更大的 lou，也没有后续页
        assertEquals(0, NgaDownloader.estimateNewFloors(5, 100, 5, listOf(1, 50, 100)))
    }

    @Test
    fun `仅断点页新增 n 楼`() {
        assertEquals(2, NgaDownloader.estimateNewFloors(5, 100, 5, listOf(100, 101, 102)))
    }

    @Test
    fun `仅新增 m 个整页`() {
        // 远端多 2 页 → 2 × 20
        assertEquals(40, NgaDownloader.estimateNewFloors(5, 100, 7, listOf(100)))
    }

    @Test
    fun `断点页新增与整页叠加`() {
        // 断点页 2 楼新（101、102）+ 后续 2 整页 40 = 42
        assertEquals(42, NgaDownloader.estimateNewFloors(5, 100, 7, listOf(100, 101, 102)))
    }

    @Test
    fun `lou=-1 占位楼不计`() {
        assertEquals(1, NgaDownloader.estimateNewFloors(5, 100, 5, listOf(-1, 101)))
        // maxFloor=-1 时占位楼同样被过滤
        assertEquals(0, NgaDownloader.estimateNewFloors(5, -1, 5, listOf(-1, -1)))
    }

    @Test
    fun `remoteTotalPage 小于 maxPage 时夹为 0`() {
        assertEquals(0, NgaDownloader.estimateNewFloors(5, 100, 3, listOf(100)))
    }

    @Test
    fun `maxFloor=-1 时断点页非负 lou 全计`() {
        assertEquals(3, NgaDownloader.estimateNewFloors(3, -1, 3, listOf(0, 5, 7)))
    }
}
