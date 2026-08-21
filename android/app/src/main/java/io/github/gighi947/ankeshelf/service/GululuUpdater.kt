package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.GululuBaseline
import io.github.gighi947.ankeshelf.data.GululuUpdate
import io.github.gighi947.ankeshelf.data.GululuUpdateConflict
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 更新结果：显式区分"已是最新 / 已更新 N 楼 / 取消 / 失败"。 */
sealed interface GululuUpdateResult {
    data class UpToDate(val baselineInitialized: Boolean) : GululuUpdateResult
    data class Updated(
        val bookId: String,
        val newCount: Int,
        val baselineInitialized: Boolean,
        val imageFailed: Int,
    ) : GululuUpdateResult

    data object Cancelled : GululuUpdateResult
    data class Err(val message: String) : GululuUpdateResult
}

/**
 * 骨碌碌热更新编排（Kotlin 版 `app/gululu_update.py` 的 execute/prepare 路径）。
 *
 * 与桌面同决策链：
 * 1. 基线损坏 → 显式失败要求完整重导；
 * 2. 基线缺失（旧书）→ 拉全量快照，用现有 EPUB 的 `floor-<id>` 锚点做**一次性迁移校验**
 *    （远端必须是本地严格前缀），随后建立基线；
 * 3. 基线可用 → 只拉远端索引，`planIncrementalUpdate` 校验 append-only 后**只取新增正文**；
 * 4. 无新增且图片模式未变 → 不重建 EPUB（只刷新基线），避免无意义替换与进度扰动。
 */
class GululuUpdater(
    private val appPaths: AppPaths,
    private val repository: BookRepository,
    private val importer: GululuImporter,
    private val fetchIndex: (Int, ProgressReporter?, () -> Boolean) -> GululuIndex = { id, p, c ->
        GululuClient().fetchIndex(id, p, c)
    },
    private val fetchFloors: (Int, List<Int>, ProgressReporter?, () -> Boolean) -> List<JsonObject> =
        { id, ids, p, c -> GululuClient().fetchFloors(id, ids, p, c) },
    private val fetchSnapshot: (Int, ProgressReporter?, () -> Boolean) -> GululuSnapshot = { id, p, c ->
        GululuClient().fetchSnapshot(id, p, c)
    },
) {
    @Volatile
    private var cancelled = false

    var taskId: String = ""

    private var listener: ((String, Int, Int, String) -> Unit)? = null

    fun setListener(block: (stage: String, current: Int, total: Int, detail: String) -> Unit) {
        listener = block
    }

    fun cancel() {
        cancelled = true
    }

    private fun progress(stage: String, current: Int, total: Int, detail: String) {
        listener?.invoke(stage, current, total, detail)
    }

    fun update(sourceId: Int, imageMode: GululuImageMode): GululuUpdateResult {
        cancelled = false
        val folder = File(appPaths.gululuLibraryDir, sourceId.toString())
        val target = File(folder, "post.epub")
        val baselineFile = GululuUpdate.baselineFile(appPaths.gululuLibraryDir, sourceId)
        if (!target.isFile) {
            return GululuUpdateResult.Err("本机没有可更新的骨碌碌 EPUB，请先完成导入")
        }
        return try {
            progress("update", 0, 0, "正在检查更新")
            when (val baseline = GululuUpdate.loadBaseline(baselineFile, sourceId)) {
                is GululuBaseline.Invalid ->
                    GululuUpdateResult.Err(baseline.error + "；请完整重新导入")
                GululuBaseline.Missing -> migrateLegacy(sourceId, imageMode, target, baselineFile)
                is GululuBaseline.Ok -> incremental(sourceId, imageMode, baseline, baselineFile)
            }
        } catch (e: GululuCancelledException) {
            GululuUpdateResult.Cancelled
        } catch (e: GululuUpdateConflict) {
            LogEvents.event(
                "gululu",
                "update_conflict",
                "task_id" to taskId,
                "source_id" to sourceId,
                "error" to (e.message ?: ""),
            )
            GululuUpdateResult.Err(e.message ?: "远端历史与本地基线不一致")
        } catch (e: Exception) {
            LogEvents.event(
                "gululu",
                "update_failed",
                "task_id" to taskId,
                "source_id" to sourceId,
                "error" to (e.message ?: e.javaClass.simpleName),
            )
            GululuUpdateResult.Err(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    /** 旧书（无基线）：全量快照 + 现有 EPUB 锚点前缀校验 → 建立基线。 */
    private fun migrateLegacy(
        sourceId: Int,
        imageMode: GululuImageMode,
        target: File,
        baselineFile: File,
    ): GululuUpdateResult {
        val snapshot = fetchSnapshot(sourceId, ::report, { cancelled })
        checkCancelled()
        val localIds = GululuUpdate.readEpubFloorIds(target)
        if (localIds.isEmpty()) {
            return GululuUpdateResult.Err("现有 EPUB 未包含可识别楼层，请完整重新导入")
        }
        val remoteIds = snapshot.floorIndex.mapNotNull {
            (it["floorId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }
        if (remoteIds.size < localIds.size || remoteIds.subList(0, localIds.size) != localIds) {
            return GululuUpdateResult.Err("现有 EPUB 与远端楼层历史不一致，请完整重新导入后建立增量基线")
        }
        val newCount = remoteIds.size - localIds.size
        return if (newCount > 0) {
            rebuild(sourceId, imageMode, snapshot, baselineFile, newCount, baselineInitialized = true)
        } else {
            writeBaseline(baselineFile, sourceId, snapshot, imageMode)
            GululuUpdateResult.UpToDate(baselineInitialized = true)
        }
    }

    /** 有基线：只拉索引 + 新增正文，合并后按需重建。 */
    private fun incremental(
        sourceId: Int,
        imageMode: GululuImageMode,
        baseline: GululuBaseline.Ok,
        baselineFile: File,
    ): GululuUpdateResult {
        val remote = fetchIndex(sourceId, ::report, { cancelled })
        checkCancelled()
        val plan = GululuUpdate.planIncrementalUpdate(baseline.floorIndex, remote.floorIndex)
        val newFloors = if (plan.newFloorIds.isNotEmpty()) {
            fetchFloors(sourceId, plan.newFloorIds, ::report, { cancelled })
        } else {
            emptyList()
        }
        checkCancelled()
        val mergedFloors = GululuUpdate.mergeIncrementalFloors(
            baselineFloors = baseline.floors,
            remoteFloorIndex = remote.floorIndex,
            newFloors = newFloors,
            plan = plan,
        )
        val snapshot = GululuSnapshot(
            detail = remote.detail,
            floorIndex = remote.floorIndex,
            chapterIndex = remote.chapterIndex,
            floors = mergedFloors,
        )
        val modeChanged = imageMode.wire != baseline.imageMode
        return if (plan.newFloorIds.isEmpty() && !modeChanged) {
            // 无新增且图片模式未变：只刷新基线，不重建 EPUB（不扰动进度与标注）
            writeBaseline(baselineFile, sourceId, snapshot, imageMode)
            GululuUpdateResult.UpToDate(baselineInitialized = false)
        } else {
            rebuild(
                sourceId,
                imageMode,
                snapshot,
                baselineFile,
                plan.newFloorIds.size,
                baselineInitialized = false,
            )
        }
    }

    /** 复用导入器的「生成 → .part → 原子替换 → 入架」，成功后刷新基线。 */
    private fun rebuild(
        sourceId: Int,
        imageMode: GululuImageMode,
        snapshot: GululuSnapshot,
        baselineFile: File,
        newCount: Int,
        baselineInitialized: Boolean,
    ): GululuUpdateResult {
        importer.taskId = taskId
        importer.setListener { stage, current, total, detail -> progress(stage, current, total, detail) }
        return when (val result = importer.rebuildFromSnapshot(sourceId, imageMode, snapshot)) {
            is GululuImportResult.Ok -> {
                writeBaseline(baselineFile, sourceId, snapshot, imageMode)
                LogEvents.event(
                    "gululu",
                    "update_done",
                    "task_id" to taskId,
                    "source_id" to sourceId,
                    "new_count" to newCount,
                )
                GululuUpdateResult.Updated(
                    bookId = result.bookId,
                    newCount = newCount,
                    baselineInitialized = baselineInitialized,
                    imageFailed = result.imageFailed,
                )
            }
            GululuImportResult.Cancelled -> GululuUpdateResult.Cancelled
            is GululuImportResult.Err -> GululuUpdateResult.Err(result.message)
        }
    }

    private fun writeBaseline(
        file: File,
        sourceId: Int,
        snapshot: GululuSnapshot,
        imageMode: GululuImageMode,
    ) {
        GululuUpdate.writeBaseline(
            file = file,
            sourceId = sourceId,
            detail = snapshot.detail,
            floorIndex = snapshot.floorIndex,
            chapterIndex = snapshot.chapterIndex,
            floors = snapshot.floors,
            imageMode = imageMode.wire,
        )
    }

    private fun report(stage: String, current: Int, total: Int, detail: String) {
        progress(stage, current, total, detail)
    }

    private fun checkCancelled() {
        if (cancelled) throw GululuCancelledException("骨碌碌更新已取消")
    }
}
