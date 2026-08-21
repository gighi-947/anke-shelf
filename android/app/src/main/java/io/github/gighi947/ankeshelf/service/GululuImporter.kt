package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.GululuCancelledBuild
import io.github.gighi947.ankeshelf.data.GululuEpub
import io.github.gighi947.ankeshelf.data.GululuEpubImage
import io.github.gighi947.ankeshelf.data.AppPaths
import java.io.File

/** 导入结果：显式区分成功 / 取消 / 失败（调用方据此设定状态，不靠异常穿透 UI）。 */
sealed interface GululuImportResult {
    data class Ok(
        val bookId: String,
        val sourceId: Int,
        val imageTotal: Int,
        val imageEmbedded: Int,
        val imageFailed: Int,
    ) : GululuImportResult

    data object Cancelled : GululuImportResult
    data class Err(val message: String) : GululuImportResult
}

/**
 * 骨碌碌导入编排（Kotlin 版 `app/gululu_service.py` 的 import 路径）。
 *
 * 流程与桌面一致：拉快照 → （内嵌模式）准备图片 → 生成 EPUB 到 `post.epub.part`
 * → **原子替换** `post.epub` → 注册书架。
 * 红线：取消或失败都要删掉 `.part`，绝不把半成品留在书库；
 * 替换后若书籍 ID 变化（路径派生）立即报错，避免丢进度/标注关联。
 */
class GululuImporter(
    private val appPaths: AppPaths,
    private val repository: BookRepository,
    /** 注入点：默认走真实客户端；测试传入假快照（不需要网络，也不必打开 client 类）。 */
    private val fetchSnapshot: (
        sourceId: Int,
        progress: ProgressReporter?,
        cancel: () -> Boolean,
    ) -> GululuSnapshot = { sourceId, progress, cancel ->
        GululuClient().fetchSnapshot(sourceId, progress, cancel)
    },
    /** 注入点：图片抓取（内嵌模式与封面共用），默认走网络。 */
    private val imageFetcher: GululuImages.ImageFetcher? = null,
) {
    @Volatile
    private var cancelled = false

    var taskId: String = ""

    private var listener: ((stage: String, current: Int, total: Int, detail: String) -> Unit)? = null

    fun setListener(block: (stage: String, current: Int, total: Int, detail: String) -> Unit) {
        listener = block
    }

    fun cancel() {
        cancelled = true
    }

    private fun progress(stage: String, current: Int, total: Int, detail: String) {
        listener?.invoke(stage, current, total, detail)
    }

    fun import(sourceId: Int, imageMode: GululuImageMode): GululuImportResult {
        cancelled = false
        val folder = File(appPaths.gululuLibraryDir, sourceId.toString())
        val target = File(folder, "post.epub")
        val partial = File(folder, "post.epub.part")
        folder.mkdirs()
        partial.delete()
        return try {
            val snapshot = fetchSnapshot(
                sourceId,
                { stage, current, total, detail -> progress(stage, current, total, detail) },
                { cancelled },
            )
            checkCancelled()

            val imageUrls = GululuImages.collectImageUrls(snapshot.floors)
            var batch = ImageBatch()
            if (imageMode == GululuImageMode.EMBEDDED && imageUrls.isNotEmpty()) {
                batch = GululuImages.prepareEmbeddedImages(
                    urls = imageUrls,
                    fetcher = imageFetcher,
                    progress = { current, total, ok, failed ->
                        progress(
                            "images",
                            current,
                            total,
                            "正在内嵌图片 $current/$total（成功 $ok，失败 $failed）",
                        )
                    },
                    cancel = { cancelled },
                )
            }
            checkCancelled()

            progress("epub", 0, 0, "正在生成 EPUB")
            val cover = fetchCover(snapshot.detail)
            val bytes = GululuEpub.build(
                detail = snapshot.detail,
                floorIndex = snapshot.floorIndex,
                chapterIndex = snapshot.chapterIndex,
                floors = snapshot.floors,
                imageResolver = GululuImages.resolverFor(imageMode, GululuImages.embeddedSources(batch)),
                images = batch.resources.map {
                    GululuEpubImage(it.fileName, it.mediaType, it.content)
                },
                cover = cover,
                onChapter = { current, total ->
                    progress("epub", current, total, "正在生成章节 $current/$total")
                },
                cancelled = { cancelled },
            )
            checkCancelled()
            partial.writeBytes(bytes)

            progress("register", 0, 0, "正在加入书架")
            val bookId = replaceAndRegister(target, partial)
            LogEvents.event(
                "gululu",
                "import_done",
                "task_id" to taskId,
                "source_id" to sourceId,
                "book_id_hash" to LogEvents.bookIdHash(bookId),
                "image_total" to imageUrls.size,
                "image_embedded" to batch.resources.size,
                "image_failed" to batch.failures.size,
            )
            if (batch.failures.isNotEmpty()) {
                LogEvents.event(
                    "gululu",
                    "image_embed_failed",
                    "task_id" to taskId,
                    "source_id" to sourceId,
                    "failed" to batch.failures.size,
                    "first" to batch.failures.first().error,
                )
            }
            GululuImportResult.Ok(
                bookId = bookId,
                sourceId = sourceId,
                imageTotal = imageUrls.size,
                imageEmbedded = batch.resources.size,
                imageFailed = batch.failures.size,
            )
        } catch (e: GululuCancelledException) {
            partial.delete()
            GululuImportResult.Cancelled
        } catch (e: GululuImageCancelled) {
            partial.delete()
            GululuImportResult.Cancelled
        } catch (e: GululuCancelledBuild) {
            partial.delete()
            GululuImportResult.Cancelled
        } catch (e: Exception) {
            partial.delete()
            LogEvents.event(
                "gululu",
                "import_failed",
                "task_id" to taskId,
                "source_id" to sourceId,
                "error" to (e.message ?: e.javaClass.simpleName),
            )
            GululuImportResult.Err(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw GululuCancelledException("骨碌碌导入已取消")
    }

    /** 封面获取失败不阻断导入（与桌面一致：只记日志）。 */
    private fun fetchCover(detail: kotlinx.serialization.json.JsonObject): GululuEpubImage? {
        val cover = detail["cover"] as? kotlinx.serialization.json.JsonObject ?: return null
        val url = (cover["picUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        if (!url.startsWith("https://")) return null
        val batch = runCatching {
            GululuImages.prepareEmbeddedImages(listOf(url), fetcher = imageFetcher, cancel = { cancelled })
        }.getOrNull() ?: return null
        batch.failures.firstOrNull()?.let {
            LogEvents.event("gululu", "cover_failed", "task_id" to taskId, "error" to it.error)
        }
        return batch.resources.firstOrNull()?.let {
            GululuEpubImage(it.fileName, it.mediaType, it.content)
        }
    }

    /**
     * `.part` → `post.epub` 原子替换并登记书架。
     * 失败时恢复旧 EPUB（若有备份），并保持路径派生的 book_id 不变。
     */
    private fun replaceAndRegister(target: File, partial: File): String {
        val backup = File(target.parentFile, "${target.name}.backup-$taskId")
        backup.delete()
        val hadTarget = target.isFile
        if (hadTarget) target.copyTo(backup, overwrite = true)
        return try {
            if (!partial.renameTo(target)) {
                // 跨卷或占用时退回复制 + 删除，语义仍是"整体替换"
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            when (val registered = repository.registerEpubFile(target)) {
                is RepoResult.Ok -> registered.value.id
                is RepoResult.Err -> throw IllegalStateException("书籍登记失败：${registered.error.message}")
            }
        } catch (e: Exception) {
            if (backup.isFile) {
                backup.copyTo(target, overwrite = true)
                runCatching { repository.registerEpubFile(target) }
            }
            throw e
        } finally {
            backup.delete()
            partial.delete()
        }
    }
}
