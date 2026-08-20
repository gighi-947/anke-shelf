package io.github.gighi947.ankeshelf.data

/**
 * 骨碌碌公开书籍 URL / EPUB 来源标识解析（Kotlin 版 `app/gululu_source.py`）。
 *
 * 失败显式化：桌面抛 ValueError，这里返回 [GululuIdResult.Err] 并携带同样的用户可读文案，
 * 调用方（下载面板 / 导入服务）直接展示，不做静默回退。
 */
sealed interface GululuIdResult {
    data class Ok(val bookId: Int) : GululuIdResult
    data class Err(val message: String) : GululuIdResult
}

object GululuSource {

    private val BOOK_PATH = Regex("^/book/(\\d+)/?$")
    private val GULULU_IDENTIFIER = Regex("^gululu-([1-9]\\d*)$")
    // 搜索模式：从任意文本中提取骨碌碌链接（非锚定）
    private val URL_SEARCH = Regex(
        "https?://(?:www\\.)?gululu\\.world/book/(\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val HOSTS = setOf("gululu.world", "www.gululu.world")

    private const val HINT = "请输入骨碌碌书籍 ID 或 https://www.gululu.world/book/<id> 链接"

    /** 接受正整数 bookId 或 gululu.world/book/<id> 公共链接。 */
    fun parseBookId(value: String): GululuIdResult {
        val raw = value.trim()
        val asNumber = raw.toIntOrNull()
        if (asNumber != null) {
            return if (asNumber > 0) {
                GululuIdResult.Ok(asNumber)
            } else {
                GululuIdResult.Err("骨碌碌书籍 ID 必须为正整数")
            }
        }
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
            ?: return GululuIdResult.Err(HINT)
        if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() !in HOSTS) {
            return GululuIdResult.Err(HINT)
        }
        val match = BOOK_PATH.matchEntire(uri.path.orEmpty())
            ?: return GululuIdResult.Err("无法从链接中识别骨碌碌书籍 ID")
        val id = match.groupValues[1].toIntOrNull()
        return if (id != null && id > 0) GululuIdResult.Ok(id) else GululuIdResult.Err(HINT)
    }

    /**
     * 从任意文本中提取首个骨碌碌书籍 ID 或链接（容忍"点击链接阅读：…"这类前后缀）。
     * 多个链接命中时报错，要求用户明确选择；零命中也报错。
     */
    fun extractBookId(text: String): GululuIdResult {
        val raw = text.trim()
        if (raw.isNotEmpty() && raw.all { it.isDigit() }) return parseBookId(raw)
        val urls = URL_SEARCH.findAll(raw).toList()
        if (urls.size > 1) return GululuIdResult.Err("文本中包含多个骨碌碌链接，请只保留一个")
        if (urls.size == 1) {
            val id = urls[0].groupValues[1].toIntOrNull()
            return if (id != null && id > 0) GululuIdResult.Ok(id) else GululuIdResult.Err(HINT)
        }
        return GululuIdResult.Err(HINT)
    }

    /** 从 EPUB 的 dc:identifier 识别骨碌碌公开书籍；非骨碌碌来源返回 null。 */
    fun parseGululuIdentifier(value: String?): Int? =
        GULULU_IDENTIFIER.matchEntire(value?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
}
