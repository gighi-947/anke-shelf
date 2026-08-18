package io.github.gighi947.ankeshelf.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** SAF 文件显示名查询（书架导入与设置页共用同一实现，避免两处漂移）。 */
internal fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    logWarn("AnkeShelf", "查询 SAF 显示名失败：${e.message}")
    null
}
