package io.github.gighi947.ankeshelf.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

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

/** JSON 文件加载结果：显式区分“缺失 / 损坏 / IO 失败”，不再全吞成 null。 */
sealed interface StoreLoadResult<out T> {
    data class Ok<T>(val value: T) : StoreLoadResult<T>
    data object Missing : StoreLoadResult<Nothing>
    data class Corrupt(val detail: String) : StoreLoadResult<Nothing>
    data class IoError(val detail: String) : StoreLoadResult<Nothing>
}

/** 读取并反序列化 JSON 文件；失败原因可区分（调用方决定回退与日志）。 */
inline fun <reified T> readJsonStore(file: File, json: Json = Shelf.json): StoreLoadResult<T> {
    if (!file.exists()) return StoreLoadResult.Missing
    val text = try {
        file.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        return StoreLoadResult.IoError(e.toString())
    }
    return try {
        StoreLoadResult.Ok(json.decodeFromString<T>(text))
    } catch (e: Exception) {
        StoreLoadResult.Corrupt(e.toString())
    }
}

/** 数据层警告日志：JVM 单测环境无 android.util.Log，失败静默（不影响回退行为）。 */
internal fun logWarn(tag: String, message: String) {
    runCatching { android.util.Log.w(tag, message) }
}
