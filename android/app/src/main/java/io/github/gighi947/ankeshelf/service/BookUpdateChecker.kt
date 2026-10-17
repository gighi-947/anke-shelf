package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.GululuBaseline
import io.github.gighi947.ankeshelf.data.GululuUpdate
import io.github.gighi947.ankeshelf.data.GululuUpdateConflict
import java.io.File

/** 进书更新检查结果：三态 + 是否估算值。 */
sealed interface BookUpdateCheck {
    /** approximate=true 表示 NGA 的页数估算；骨碌碌为精确值。 */
    data class Available(val newFloors: Int, val approximate: Boolean) : BookUpdateCheck
    data object None : BookUpdateCheck
    /** 无法检查（无凭据/无基线/网络失败/非对应书型）：调用方静默不提示。 */
    data object Unavailable : BookUpdateCheck
}

/**
 * 进书更新统一检查入口（服务/数据层，UI 下一轮接入）。
 *
 * NGA 原生书走 [NgaDownloader.checkUpdate] 的轻量断点页检查（页数估算）；
 * 骨碌碌走 [GululuUpdate] 增量基线的 append-only 前缀比对（精确值）。
 *
 * checkGululu / gululuSourceIdFor 只依赖 gululuLibraryDir 而非整个
 * [AppContainer]（后者构造需要 android Context），便于 JVM 单测注入临时目录。
 */
object BookUpdateChecker {

    /** NGA 原生书轻量检查；nga_tid<=0（非 NGA 书）直接 Unavailable。 */
    fun checkNga(container: AppContainer, record: BookRecord): BookUpdateCheck {
        if (record.nga_tid <= 0) return BookUpdateCheck.Unavailable
        val estimated = NgaDownloader(container.appPaths, container.repository, container.ngaConfig)
            .checkUpdate(record.id)
        return when (estimated) {
            null -> BookUpdateCheck.Unavailable
            0 -> BookUpdateCheck.None
            else -> BookUpdateCheck.Available(estimated, approximate = true)
        }
    }

    /** 从 record.path 解析骨碌碌 sourceId；非骨碌碌书返回 0。 */
    fun gululuSourceIdFor(gululuLibraryDir: File, record: BookRecord): Int {
        if (record.nga_tid > 0) return 0
        if (!record.path.startsWith(gululuLibraryDir.absolutePath)) return 0
        return File(record.path).parentFile?.name?.toIntOrNull() ?: 0
    }

    /**
     * 骨碌碌书精确检查：基线缺失/损坏（旧书走手动更新的一次性迁移）与
     * 远端历史冲突（完整重导是手动流程）均静默 Unavailable，不做提示。
     */
    fun checkGululu(
        gululuLibraryDir: File,
        record: BookRecord,
        fetchIndex: (Int) -> GululuIndex = { GululuClient().fetchIndex(it, null) { false } },
    ): BookUpdateCheck {
        val sourceId = gululuSourceIdFor(gululuLibraryDir, record)
        if (sourceId <= 0) return BookUpdateCheck.Unavailable
        val baseline = GululuUpdate.loadBaseline(
            GululuUpdate.baselineFile(gululuLibraryDir, sourceId),
            sourceId,
        )
        if (baseline !is GululuBaseline.Ok) return BookUpdateCheck.Unavailable
        val remote = try {
            fetchIndex(sourceId)
        } catch (_: Exception) {
            return BookUpdateCheck.Unavailable
        }
        val plan = try {
            GululuUpdate.planIncrementalUpdate(baseline.floorIndex, remote.floorIndex)
        } catch (_: GululuUpdateConflict) {
            return BookUpdateCheck.Unavailable
        }
        return if (plan.newFloorIds.isEmpty()) {
            BookUpdateCheck.None
        } else {
            BookUpdateCheck.Available(plan.newFloorIds.size, approximate = false)
        }
    }
}
