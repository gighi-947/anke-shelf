package io.github.gighi947.ankeshelf.data

import java.io.File

/**
 * 安卓数据目录布局（等价桌面 %APPDATA%\AnkeShelf）：
 * filesDir/AnkeShelf/{shelf,progress,settings,annotations,statistics}.json
 * filesDir/AnkeShelf/covers/、filesDir/AnkeShelf/nga_library/、
 * filesDir/AnkeShelf/gululu_library/（骨碌碌 EPUB 与端私有 sidecar：
 * snapshot.json 增量基线、comments/<floorId>.json 评论缓存）
 */
class AppPaths(val root: File) {

    val shelfFile: File get() = File(root, "shelf.json")
    val progressFile: File get() = File(root, "progress.json")
    val settingsFile: File get() = File(root, "settings.json")
    val annotationsFile: File get() = File(root, "annotations.json")
    val statisticsFile: File get() = File(root, "statistics.json")
    val searchHistoryFile: File get() = File(root, "search_history.json")
    val coversDir: File get() = File(root, "covers")
    val fontsDir: File get() = File(root, "fonts")
    val logsDir: File get() = File(root, "logs")
    val ngaLibraryDir: File get() = File(root, "nga_library")
    val gululuLibraryDir: File get() = File(root, "gululu_library")
    val ngaConfigFile: File get() = File(root, "nga_config.ini")
    /** 骨碌碌阅读解锁状态（端私有，不入双端契约）。 */
    val gululuUnlocksFile: File get() = File(root, "gululu_unlocks.json")
    val gululuCluesFile: File get() = File(root, "gululu_clues.json")

    fun ensure() {
        root.mkdirs()
        coversDir.mkdirs()
        fontsDir.mkdirs()
        logsDir.mkdirs()
        ngaLibraryDir.mkdirs()
        gululuLibraryDir.mkdirs()
    }

    companion object {
        const val APP_DIR_NAME = "AnkeShelf"
    }
}
