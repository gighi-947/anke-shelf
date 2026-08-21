package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipFile

/** 远端历史不是本地基线的 append-only 延续（对齐桌面 GululuUpdateConflict）。 */
class GululuUpdateConflict(message: String) : Exception(message)

/** 基线加载结果：显式区分缺失 / 损坏 / 可用（缺失可走旧书一次性迁移）。 */
sealed interface GululuBaseline {
    data object Missing : GululuBaseline
    data class Invalid(val error: String) : GululuBaseline
    data class Ok(
        val detail: JsonObject,
        val floorIndex: List<JsonObject>,
        val chapterIndex: List<JsonObject>,
        val floors: List<JsonObject>,
        val imageMode: String,
    ) : GululuBaseline
}

/** 增量计划：需要新拉的楼层 ID（有序）。 */
data class GululuIncrementalPlan(val newFloorIds: List<Int>)

/**
 * 骨碌碌增量基线与 append-only 合并（Kotlin 版 `app/gululu_update.py` 的纯逻辑部分）。
 *
 * `snapshot.json` 是**端私有 sidecar**（不进双端数据契约）：保存上次详情、楼层/章节索引、
 * 正文与图片模式。核心不变量：**旧楼层 ID 必须是远端序列的严格前缀**——
 * 删除、重排、替换旧楼一律显式失败并要求完整重导，绝不"猜"该怎么合并。
 */
object GululuUpdate {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun baselineFile(gululuLibraryDir: File, sourceId: Int): File =
        File(File(gululuLibraryDir, sourceId.toString()), "snapshot.json")

    fun loadBaseline(file: File, sourceId: Int): GululuBaseline {
        if (!file.isFile) return GululuBaseline.Missing
        val payload = try {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            return GululuBaseline.Invalid("增量基线 JSON 损坏：${e.message}")
        } ?: return GululuBaseline.Invalid("增量基线字段格式错误")

        if ((payload["version"] as? JsonPrimitive)?.intOrNull != 1) {
            return GululuBaseline.Invalid("增量基线版本无效")
        }
        if ((payload["source_id"] as? JsonPrimitive)?.intOrNull != sourceId) {
            return GululuBaseline.Invalid("增量基线来源与当前书籍不一致")
        }
        val imageMode = (payload["image_mode"] as? JsonPrimitive)?.content
        if (GululuImageModeNames.valid(imageMode) == null) {
            return GululuBaseline.Invalid("骨碌碌图片模式必须是 online、embedded 或 none")
        }
        val detail = payload["detail"] as? JsonObject
        val floorIndex = payload["floor_index"] as? JsonArray
        val chapterIndex = payload["chapter_index"] as? JsonArray
        val floors = payload["floors"] as? JsonArray
        if (detail == null || floorIndex == null || chapterIndex == null || floors == null) {
            return GululuBaseline.Invalid("增量基线字段格式错误")
        }
        val baseline = GululuBaseline.Ok(
            detail = detail,
            floorIndex = floorIndex.filterIsInstance<JsonObject>(),
            chapterIndex = chapterIndex.filterIsInstance<JsonObject>(),
            floors = floors.filterIsInstance<JsonObject>(),
            imageMode = imageMode!!,
        )
        snapshotError(baseline.detail, baseline.floorIndex, baseline.floors, sourceId)?.let {
            return GululuBaseline.Invalid(it)
        }
        return baseline
    }

    fun writeBaseline(
        file: File,
        sourceId: Int,
        detail: JsonObject,
        floorIndex: List<JsonObject>,
        chapterIndex: List<JsonObject>,
        floors: List<JsonObject>,
        imageMode: String,
    ) {
        val mode = GululuImageModeNames.valid(imageMode)
            ?: throw IllegalArgumentException("骨碌碌图片模式必须是 online、embedded 或 none")
        snapshotError(detail, floorIndex, floors, sourceId)?.let { throw IllegalArgumentException(it) }
        file.parentFile?.mkdirs()
        val payload = buildJsonObject {
            put("version", 1)
            put("source_id", sourceId)
            put("image_mode", mode)
            put("detail", detail)
            put("floor_index", JsonArray(floorIndex))
            put("chapter_index", JsonArray(chapterIndex))
            put("floors", JsonArray(floors))
        }
        atomicWriteJson(file, json.encodeToString(JsonObject.serializer(), payload))
    }

    /** append-only 校验：旧楼层 ID 必须是远端严格前缀，否则显式冲突。 */
    fun planIncrementalUpdate(
        baselineFloorIndex: List<JsonObject>,
        remoteFloorIndex: List<JsonObject>,
    ): GululuIncrementalPlan {
        val oldIds = floorIndexIds(baselineFloorIndex)
        val remoteIds = floorIndexIds(remoteFloorIndex)
        if (remoteIds.size < oldIds.size || remoteIds.subList(0, oldIds.size) != oldIds) {
            throw GululuUpdateConflict("远端旧楼层已删除、重排或替换，请完整重新导入后再建立增量基线")
        }
        return GululuIncrementalPlan(remoteIds.subList(oldIds.size, remoteIds.size))
    }

    /** 合并：旧正文 + 新正文按远端索引顺序重排；缺任何一楼都显式失败。 */
    fun mergeIncrementalFloors(
        baselineFloors: List<JsonObject>,
        remoteFloorIndex: List<JsonObject>,
        newFloors: List<JsonObject>,
        plan: GululuIncrementalPlan,
    ): List<JsonObject> {
        val newById = newFloors.mapNotNull { floor -> floorId(floor)?.let { it to floor } }.toMap()
        if (newById.keys.sorted() != plan.newFloorIds.sorted()) {
            throw GululuUpdateConflict("新增楼层正文与远端索引不一致，请稍后重试")
        }
        val merged = baselineFloors.mapNotNull { floor -> floorId(floor)?.let { it to floor } }
            .toMap()
            .toMutableMap()
        merged.putAll(newById)
        val remoteIds = floorIndexIds(remoteFloorIndex)
        val missing = remoteIds.firstOrNull { it !in merged }
        if (missing != null) throw GululuUpdateConflict("本地增量基线缺少楼层正文：$missing")
        return remoteIds.map { merged.getValue(it) }
    }

    /**
     * 旧书一次性迁移：从已有 EPUB 的 `floor-<id>` 锚点读回楼层序列
     * （对齐桌面 `read_epub_floor_ids`）。读不出来即显式失败，要求完整重导。
     */
    fun readEpubFloorIds(file: File): List<Int> {
        val anchor = Regex("id=\"floor-(\\d+)\"")
        return try {
            ZipFile(file).use { zip ->
                val names = zip.entries().toList()
                    .map { it.name }
                    .filter { it.startsWith("EPUB/chapters/") && it.endsWith(".xhtml") }
                    .sorted()
                val ids = mutableListOf<Int>()
                for (name in names) {
                    val text = zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
                    anchor.findAll(text).forEach { m ->
                        m.groupValues[1].toIntOrNull()?.let { ids.add(it) }
                    }
                }
                ids
            }
        } catch (e: Exception) {
            throw GululuUpdateConflict("无法读取现有 EPUB 楼层基线：${e.message}")
        }
    }

    private fun floorIndexIds(floorIndex: List<JsonObject>): List<Int> {
        val ids = floorIndex.map { item ->
            (item["floorId"] as? JsonPrimitive)?.intOrNull
                ?: throw GululuUpdateConflict("楼层索引格式错误")
        }
        if (ids.size != ids.toSet().size) throw GululuUpdateConflict("楼层索引包含重复 ID")
        return ids
    }

    private fun floorId(floor: JsonObject): Int? = (floor["id"] as? JsonPrimitive)?.intOrNull

    private fun snapshotError(
        detail: JsonObject,
        floorIndex: List<JsonObject>,
        floors: List<JsonObject>,
        sourceId: Int,
    ): String? {
        if ((detail["bookId"] as? JsonPrimitive)?.intOrNull != sourceId) {
            return "增量基线书籍 ID 不一致"
        }
        val ids = try {
            floorIndexIds(floorIndex)
        } catch (e: GululuUpdateConflict) {
            return e.message
        }
        val bodyIds = floors.mapNotNull { floorId(it) }
        if (bodyIds != ids) return "增量基线楼层索引与正文顺序不一致"
        return null
    }
}

/** 图片模式名校验（data 层不依赖 service 层的枚举）。 */
internal object GululuImageModeNames {
    private val MODES = setOf("online", "embedded", "none")

    fun valid(value: String?): String? {
        val mode = value?.trim()?.lowercase().orEmpty().ifEmpty { "online" }
        return if (mode in MODES) mode else null
    }
}
