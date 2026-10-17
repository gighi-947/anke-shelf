package io.github.gighi947.ankeshelf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器网络出口规则回归（2026-08-22 安全对齐）。
 *
 * 修复的核心缺陷：旧实现用 url.contains("img.nga.cn") 子串匹配图床白名单，
 * 恶意 EPUB 可用 https://evil.com/img.nga.cn/x.gif 命中匹配并经
 * ngaHeaders（携带 NGA uid/cid Cookie）把凭据发往任意主机。改为与桌面
 * server._is_nga_image_url 同构的主机名精确后缀匹配。
 */
class ReaderEgressTest {

    // ---- NGA 图床：主机名精确匹配 ----
    @Test
    fun `NGA 图床域名放行`() {
        assertTrue(ReaderEgress.isNgaImageUrl("https://img.nga.cn/mon_202208/1.jpg"))
        assertTrue(ReaderEgress.isNgaImageUrl("http://img.nga.178.com/attachments/x.png"))
        assertTrue(ReaderEgress.isNgaImageUrl("https://img.ngabbs.com/attach/y.gif"))
        assertTrue(ReaderEgress.isNgaImageUrl("https://sub.img.nga.cn/a.webp"))
    }

    @Test
    fun `子串伪造与后缀仿冒全部拒绝`() {
        // 本次修复的核心：路径/查询里带图床子串不能命中（凭据外带通道）
        assertFalse(ReaderEgress.isNgaImageUrl("https://evil.com/img.nga.cn/x.gif"))
        assertFalse(ReaderEgress.isNgaImageUrl("https://evil.com/?u=img.nga.cn"))
        assertFalse(ReaderEgress.isNgaImageUrl("https://evil.com/ngabbs.com"))
        // 后缀仿冒域名（无边界点）拒绝
        assertFalse(ReaderEgress.isNgaImageUrl("https://evnga.cn/x"))
        assertFalse(ReaderEgress.isNgaImageUrl("https://nga.cn.evil.com/x"))
        assertFalse(ReaderEgress.isNgaImageUrl("https://img.nga.cn.evil.com/x"))
    }

    @Test
    fun `非 http 方案与畸形 URL 拒绝`() {
        assertFalse(ReaderEgress.isNgaImageUrl("javascript:alert(1)"))
        assertFalse(ReaderEgress.isNgaImageUrl("file:///android_asset/reader/reader-lite.js"))
        assertFalse(ReaderEgress.isNgaImageUrl("ftp://img.nga.cn/x"))
        assertFalse(ReaderEgress.isNgaImageUrl("::not a url::"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(ReaderEgress.isNgaImageUrl("HTTPS://IMG.NGA.CN/x.jpg"))
        assertFalse(ReaderEgress.isNgaImageUrl("https://EVIL.com/IMG.NGA.CN"))
    }

    // ---- 明文 http 门禁：应用全 https，明文子资源一律拒绝 ----
    @Test
    fun `明文 http 检测`() {
        assertTrue(ReaderEgress.isCleartext("http://tracker.example/pixel.gif"))
        assertTrue(ReaderEgress.isCleartext("HTTP://example.com/x"))
        assertFalse(ReaderEgress.isCleartext("https://cdn.example/img.webp"))
        assertFalse(ReaderEgress.isCleartext("file:///android_images/x.jpg"))
        assertFalse(ReaderEgress.isCleartext("data:image/gif;base64,R0lGOD"))
    }

    // ---- 表情宿主归一：img4.nga.178.com 已下线，存量书死域 URL 复活 ----
    @Test
    fun `死域表情归一到现役宿主`() {
        assertEquals(
            "https://img4.nga.cn/ngabbs/post/smile/ac0.png",
            ReaderEgress.normalizeLegacyNgaImageUrl("https://img4.nga.178.com/ngabbs/post/smile/ac0.png"),
        )
        assertEquals(
            "https://img4.nga.cn/ngabbs/post/smile/a2_10.png",
            ReaderEgress.normalizeLegacyNgaImageUrl("http://img4.nga.cn/ngabbs/post/smile/a2_10.png"),
        )
    }

    @Test
    fun `非表情路径与原生命名空间不受影响`() {
        val attach = "https://img.nga.178.com/attachments/mon_1.jpg"
        assertEquals(attach, ReaderEgress.normalizeLegacyNgaImageUrl(attach))
        assertEquals(
            "file:///android_images/b/ac0.png",
            ReaderEgress.normalizeLegacyNgaImageUrl("file:///android_images/b/ac0.png"),
        )
        assertEquals("not a url", ReaderEgress.normalizeLegacyNgaImageUrl("not a url"))
    }
}
