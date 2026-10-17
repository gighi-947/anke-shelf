package io.github.gighi947.ankeshelf.ui.components

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.GululuBaseline
import io.github.gighi947.ankeshelf.data.GululuUpdate
import io.github.gighi947.ankeshelf.service.BookUpdateChecker
import io.github.gighi947.ankeshelf.service.GululuImportService
import java.io.File

/** 启动骨碌碌增量更新前台服务（书架与阅读器共用；非骨碌碌书直接不启动）。 */
internal fun launchGululuBookUpdate(context: Context, gululuLibraryDir: File, book: BookRecord) {
    val sourceId = BookUpdateChecker.gululuSourceIdFor(gululuLibraryDir, book)
    if (sourceId <= 0) return
    val baselineFile = GululuUpdate.baselineFile(gululuLibraryDir, sourceId)
    val baseline = GululuUpdate.loadBaseline(baselineFile, sourceId)
    val imageMode = (baseline as? GululuBaseline.Ok)?.imageMode ?: "online"
    val intent = Intent(context, GululuImportService::class.java).apply {
        action = GululuImportService.ACTION_START
        putExtra("action", "update")
        putExtra("sourceId", sourceId)
        putExtra("imageMode", imageMode)
    }
    ContextCompat.startForegroundService(context, intent)
}
