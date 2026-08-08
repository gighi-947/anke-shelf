package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NgaConfigData
import okhttp3.Request

/** NGA 接口/图床公共请求头（防盗链：Referer + 登录 Cookie + UA）。 */
const val NGA_REFERER = "https://bbs.nga.cn/"

fun Request.Builder.ngaHeaders(cfg: NgaConfigData): Request.Builder =
    ngaHeaders(cfg.uid, cfg.cid, cfg.ua)

fun Request.Builder.ngaHeaders(uid: String, cid: String, ua: String): Request.Builder {
    header("Referer", NGA_REFERER)
    header("User-Agent", ua.ifBlank { NgaConfig.DEFAULT_UA })
    if (uid.isNotBlank() && cid.isNotBlank()) {
        header("Cookie", "ngaPassportUid=$uid; ngaPassportCid=$cid")
    }
    return this
}
