package io.github.gighi947.ankeshelf.ui.reader

import java.net.URI

/**
 * 阅读器 WebView 网络出口规则（与桌面 `app/server.py` 的 `_is_nga_image_url`
 * 同构，双端同判据；边界由 `ReaderEgressTest` 锁定）。
 *
 * - NGA 图床：主机名精确后缀匹配（`host == s` 或 `host.endsWith(".$s")`）。
 *   子串匹配会被 `https://evil.com/img.nga.cn/x.gif` 借道命中，经
 *   ngaHeaders（携带 NGA uid/cid Cookie）把凭据发往任意主机——2026-08-22
 *   安全对齐发现的凭据外带通道，已修复。
 * - 明文 http：应用全 https（NGA API 仅 https），章节内容发起的明文子资源
 *   一律拒绝；https 外链图片保持放行，与桌面 CSP `img-src https:` 策略
 *   一致（骨碌碌在线图片可为任意 https 图床，不做主机枚举白名单）。
 */
object ReaderEgress {

    private val NGA_IMAGE_HOST_SUFFIXES = listOf("nga.178.com", "nga.cn", "ngabbs.com")

    /** 是否 NGA 图床 URL（http/https 且主机命中白名单后缀；解析失败按拒绝）。 */
    fun isNgaImageUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        return NGA_IMAGE_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    /** 是否明文 http 子资源（拒绝对象；https 与本地/data 方案不在此列）。 */
    fun isCleartext(url: String): Boolean = url.startsWith("http://", ignoreCase = true)
}
