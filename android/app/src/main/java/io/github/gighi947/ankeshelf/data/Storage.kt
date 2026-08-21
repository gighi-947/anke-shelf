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
private val CORRUPT_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'")

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
    } catch (e: Exception) {
        logWarn("AnkeShelf", "ATOMIC_MOVE 失败，回退普通替换：${e.message}")
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

/** 权威存储加载异常：由 AppContainer 汇总后经书架横幅展示给用户。 */
data class StoreLoadIssue(val fileName: String, val kind: Kind, val detail: String) {
    enum class Kind { Corrupt, IoError }

    /** 横幅文案：损坏已隔离（原字节保留在 .corrupt-*）；读取失败则暂停写入保护原文件。 */
    fun userMessage(): String = when (kind) {
        Kind.Corrupt -> "$fileName 损坏，原文件已备份为 .corrupt 文件，对应数据从空开始"
        Kind.IoError -> "$fileName 读取失败，已暂停该项写入以防覆盖原文件"
    }
}

/** 存储写保护：上次 load 遇 IoError（原文件仍在原位）时暂停写盘，恢复成功后解除。 */
class StoreWriteGuard {
    @Volatile
    private var blocked = false

    fun writeBlocked(): Boolean = blocked
    fun block() { blocked = true }
    fun unblock() { blocked = false }
}

/** 写被暂停时 save/flush 的显式失败原因（调用方可提示用户）。 */
class StoreWriteProtectedException(fileName: String) :
    Exception("$fileName 上次读取失败，写入已暂停以保护原文件")

/**
 * 权威存储统一加载入口：失败不再静默——
 * - Ok/Missing：正常返回并解除写保护；
 * - Corrupt：readJsonStore 已把原文件隔离为 .corrupt-*，空状态即新状态，
 *   允许继续写，但报告 issue（用户可见）；
 * - IoError：原文件未被移动，返回默认值的同时挂起写保护，防止下次 save
 *   用空数据覆盖可能完好的文件；同样报告 issue。
 */
inline fun <reified T> loadGuarded(
    file: File,
    guard: StoreWriteGuard,
    default: () -> T,
): Pair<T, StoreLoadIssue?> = when (val r: StoreLoadResult<T> = readJsonStore(file)) {
    is StoreLoadResult.Ok -> {
        guard.unblock()
        r.value to null
    }
    StoreLoadResult.Missing -> {
        guard.unblock()
        default() to null
    }
    is StoreLoadResult.Corrupt -> {
        logWarn("AnkeShelf", "${file.name} 损坏，回退默认：${r.detail}")
        guard.unblock()
        default() to StoreLoadIssue(file.name, StoreLoadIssue.Kind.Corrupt, r.detail)
    }
    is StoreLoadResult.IoError -> {
        logWarn("AnkeShelf", "${file.name} 读取失败，暂停写入保护原文件：${r.detail}")
        guard.block()
        default() to StoreLoadIssue(file.name, StoreLoadIssue.Kind.IoError, r.detail)
    }
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
        isolateCorrupt(file)
        StoreLoadResult.Corrupt(e.toString())
    }
}

/** 损坏 JSON 退出权威路径，保留原始字节供诊断或人工恢复。 */
@PublishedApi
internal fun isolateCorrupt(file: File): File? {
    if (!file.exists()) return null
    val stamp = CORRUPT_STAMP.format(OffsetDateTime.now(ZoneOffset.UTC))
    val target = File(file.parentFile, "${file.name}.corrupt-$stamp")
    return try {
        Files.move(file.toPath(), target.toPath())
        target
    } catch (e: Exception) {
        logWarn("AnkeShelf", "${file.name} 损坏文件隔离失败：$e")
        null
    }
}

/** 数据层警告日志：JVM 单测环境无 android.util.Log，失败静默（不影响回退行为）。 */
@PublishedApi
internal fun logWarn(tag: String, message: String) {
    runCatching { android.util.Log.w(tag, message) }
}

/** 单文件完整性检查结果（对齐桌面 `storage.verify_json_file` 的字段语义）。 */
data class JsonFileHealth(
    val name: String,
    val ok: Boolean,
    val error: String,
    val size: Long,
    val version: Int?,
)

private val VERSION_FIELD_RE = Regex("\"(?:version|settings_version)\"\\s*:\\s*(-?\\d+)")

/**
 * 完整性检查：只判断"能否解析 + 有无版本字段"，不读取任何内容值
 * （与桌面 `verify_json_file` 一致：缺失视为健康，损坏/IO 失败显式报错）。
 */
fun verifyJsonFile(file: File): JsonFileHealth {
    if (!file.exists()) {
        return JsonFileHealth(file.name, ok = true, error = "missing", size = 0, version = null)
    }
    val size = file.length()
    val text = try {
        file.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        return JsonFileHealth(file.name, ok = false, error = e.toString(), size = size, version = null)
    }
    return try {
        val element = Shelf.json.parseToJsonElement(text)
        val version = VERSION_FIELD_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()
        // 顶层必须是对象：数组/标量说明文件结构不对，属于损坏而不是"版本未知"。
        if (element !is kotlinx.serialization.json.JsonObject) {
            JsonFileHealth(file.name, ok = false, error = "顶层不是 JSON 对象", size = size, version = version)
        } else {
            JsonFileHealth(file.name, ok = true, error = "", size = size, version = version)
        }
    } catch (e: Exception) {
        JsonFileHealth(file.name, ok = false, error = e.toString(), size = size, version = null)
    }
}

/** 五个权威存储的完整性检查（书架/进度/设置/标注/统计），供设置页入口调用。 */
fun verifyDataIntegrity(paths: AppPaths): List<JsonFileHealth> = listOf(
    paths.shelfFile,
    paths.progressFile,
    paths.settingsFile,
    paths.annotationsFile,
    paths.statisticsFile,
).map { verifyJsonFile(it) }
