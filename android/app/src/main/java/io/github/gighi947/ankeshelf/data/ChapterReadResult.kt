package io.github.gighi947.ankeshelf.data

/** 章节读取的显式结果：失败原因进入类型系统，不再用 null 折叠。 */
sealed interface ChapterReadResult {
    data class Success(val text: String) : ChapterReadResult

    /** 章节越界或条目/文件不存在（“资源可能不存在”语义）。 */
    data object NotFound : ChapterReadResult

    /** 内容字节存在但无法解码。 */
    data class Corrupt(val detail: String) : ChapterReadResult

    /** 读取 IO 失败（容器已关闭、文件权限等）。 */
    data class Io(val detail: String) : ChapterReadResult

    /** 只关心成功文本的调用点（索引、统计等缺省空串）。 */
    fun textOrEmpty(): String = when (this) {
        is Success -> text
        else -> ""
    }
}
