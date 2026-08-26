package io.github.gighi947.ankeshelf.service

import android.content.Context
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import java.io.File

/**
 * 楼层导出编排（2026-08-27 审查清理）：把"映射 → HTML → 渲染 → 落盘"
 * 的完整管线收敛为单一函数，供批量面板与单楼分享共用——此前两份 UI 各自
 * 内联同一段逻辑（ngaFetcher / viewport 公式 / base URL / 落盘），并已在
 * 主题覆写策略上漂移（Panel 支持"按选中主题覆写"，shareFloor 直接用当前
 * 阅读设定）。
 */
data class FloorExportSpec(
    val format: String,
    val scale: Double,
    val themeColors: ReaderThemeColors,
    val settings: SettingsData,
)

internal object FloorExportPipeline {

    /**
     * 渲染单个楼层并写入 [outDir]，返回渲染结果（文件 + 图片失败计数）。
     * @param ngaSession 非空表示骨碌碌书（读章节/资源），NGA 书传 null。
     */
    suspend fun renderFloor(
        context: Context,
        container: AppContainer,
        record: BookRecord,
        session: BookSession?,
        floor: FloorExportFloor,
        spec: FloorExportSpec,
        outDir: File,
    ): FloorRenderResult {
        val isNga = record.nga_tid > 0
        val html = if (isNga) {
            FloorExportHtml.nga(record, floor, spec.themeColors, spec.settings)
        } else {
            checkNotNull(session) { "骨碌碌导出需要 session" }
            FloorExportHtml.gululu(session, floor, spec.themeColors, spec.settings)
        }
        val base = if (isNga) {
            "file:///android_asset/reader/"
        } else {
            checkNotNull(session) { "骨碌碌导出需要 session" }
            "file:///android_epub/${session.id}/${session.chapterBaseDir(floor.chapterIndex)}/"
        }
        val density = context.resources.displayMetrics.density
        val screenCss = (context.resources.displayMetrics.widthPixels / density).toInt()
        val fontPx = spec.settings.font_size
        val pageWidth = spec.settings.page_width.coerceIn(0.5, 1.5)
        val viewportWidth = minOf((46 * fontPx * pageWidth).toInt(), screenCss).coerceAtLeast(320)
        val ngaFetcher: (String) -> ByteArray? = { url ->
            try {
                val req = okhttp3.Request.Builder().url(url)
                    .ngaHeaders(container.ngaConfig.load())
                    .build()
                container.okHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
        }
        val rendered = FloorExportRenderer.render(
            context = context,
            html = html,
            baseUrl = base,
            scale = spec.scale.toFloat(),
            format = spec.format,
            fontsDir = container.appPaths.fontsDir,
            assetResolver = if (isNga) null else { rel ->
                checkNotNull(session).readAsset(floor.chapterIndex, rel)
            },
            ngaImageFetcher = ngaFetcher,
            userAgent = NgaConfig.DEFAULT_UA,
            viewportWidth = viewportWidth,
        )
        outDir.mkdirs()
        val outFile = File(outDir, "${safeExportName(record.title)}_第${floor.num}楼.${spec.format}")
        rendered.file.copyTo(outFile, overwrite = true)
        rendered.file.delete()
        return rendered.copy(file = outFile)
    }
}
