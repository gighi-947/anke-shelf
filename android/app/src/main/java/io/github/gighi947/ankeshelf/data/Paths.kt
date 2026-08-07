package io.github.gighi947.ankeshelf.data

import java.io.File

/**
 * 安卓数据目录布局（等价桌面 %APPDATA%\AnkeShelf）：
 * filesDir/AnkeShelf/{shelf,progress,settings,annotations,statistics}.json
 * filesDir/AnkeShelf/covers/、filesDir/AnkeShelf/nga_library/
 */
class AppPaths(val root: File) {

    val shelfFile: File get() = File(root, "shelf.json")
    val progressFile: File get() = File(root, "progress.json")
    val settingsFile: File get() = File(root, "settings.json")
    val annotationsFile: File get() = File(root, "annotations.json")
    val statisticsFile: File get() = File(root, "statistics.json")
    val coversDir: File get() = File(root, "covers")
    val ngaLibraryDir: File get() = File(root, "nga_library")

    fun ensure() {
        root.mkdirs()
        coversDir.mkdirs()
        ngaLibraryDir.mkdirs()
    }

    companion object {
        const val APP_DIR_NAME = "AnkeShelf"
    }
}
