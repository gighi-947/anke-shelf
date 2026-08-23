package io.github.gighi947.ankeshelf.service

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * NGA 内嵌图片批量下载核心（2026-08-22 修复）。
 *
 * 修复前的行为：串行逐张下载、零进度上报（前台通知停在楼层阶段，表现为
 * "看不到图片进度、像卡住"）、imageHttp 无超时（慢速滴流连接可把任务无限
 * 拖住——OkHttp readTimeout 只约束单次 socket read，不约束整图耗时）。
 *
 * 语义（NgaImageDownloadsTest 锁定）：
 * - 6 路并发（对齐 GululuImages），按**完成序**上报进度——哪张先完成先报，
 *   不被最慢的一张阻塞显示；
 * - 已缓存图片（isCached）跳过下载，但计入 done/ok 起点，进度对用户仍是
 *   "x/总数"；
 * - 单图失败（load 返回 null / 抛异常）不中断整批，计入 failed；
 * - perResultTimeoutSec：等待下一张完成的上限；超时视为整体卡死，终止并
 *   返回 false（剩余未完成项不再等待）；
 * - isCancelled：收结果循环每次检查，取消立即停止派发中的等待并返回 false。
 *
 * @return true = 全部处理完成；false = 超时或取消提前终止。
 */
internal object NgaImageDownloads {

    const val WORKERS = 6

    fun drain(
        urls: List<String>,
        isCached: (String) -> Boolean,
        load: (String) -> ByteArray?,
        persist: (String, ByteArray) -> Unit,
        onProgress: (done: Int, total: Int, ok: Int, failed: Int) -> Unit,
        isCancelled: () -> Boolean,
        perResultTimeoutSec: Long = 90,
        workers: Int = WORKERS,
    ): Boolean {
        if (urls.isEmpty()) return true
        val total = urls.size
        val pending = urls.filterNot(isCached)
        var done = total - pending.size
        var ok = done // 缓存图按成功计
        var failed = 0
        if (pending.isEmpty()) {
            onProgress(done, total, ok, failed)
            return true
        }

        val pool = Executors.newFixedThreadPool(workers.coerceAtLeast(1)) { r ->
            Thread(r, "nga-image").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<Boolean>(pool)
        try {
            // 先报缓存起点（如 "2/3"），用户立刻看到已跳过的缓存图，而非
            // 停在 0 直到第一张下载完成。
            onProgress(done, total, ok, failed)
            val futures: MutableList<Future<Boolean>> = ArrayList(pending.size)
            pending.forEach { url ->
                if (isCancelled()) {
                    pool.shutdownNow()
                    return false
                }
                futures.add(
                    completion.submit(
                        Callable {
                            try {
                                val bytes = load(url) ?: return@Callable false
                                persist(url, bytes)
                                true
                            } catch (_: InterruptedException) {
                                false
                            } catch (_: Exception) {
                                false
                            }
                        },
                    ),
                )
            }
            repeat(pending.size) {
                if (isCancelled()) {
                    pool.shutdownNow()
                    return false
                }
                val future = try {
                    completion.poll(perResultTimeoutSec, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    pool.shutdownNow()
                    return false
                }
                if (future == null) {
                    // 超时窗口内无任何一张完成：判定整体卡死（如慢速滴流），
                    // 终止等待；已提交任务由 shutdownNow 打断。
                    pool.shutdownNow()
                    return false
                }
                done++
                if (future.get()) ok++ else failed++
                onProgress(done, total, ok, failed)
            }
            return true
        } finally {
            pool.shutdownNow()
        }
    }
}
