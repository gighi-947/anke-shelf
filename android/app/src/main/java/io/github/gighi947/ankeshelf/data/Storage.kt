package io.github.gighi947.ankeshelf.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

private val BASE_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** UTC ISO 时间戳（秒级），与桌面 storage.now_iso 同格式（+00:00 偏移）。 */
fun nowIso(): String =
    BASE_SECONDS.format(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)) + "+00:00"

/** 原子写文本：临时文件 + 原子替换（Windows 同盘可用 ATOMIC_MOVE）。 */
fun atomicWriteText(path: File, text: String) {
    path.parentFile?.mkdirs()
    val tmp = File(path.parentFile, path.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    moveReplace(tmp, path)
}

/** 原子写 JSON：桌面 storage.atomic_write_json 的对应实现。 */
fun atomicWriteJson(path: File, text: String) = atomicWriteText(path, text)

private fun moveReplace(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: Exception) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
