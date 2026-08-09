package io.github.gighi947.ankeshelf.ui

import android.content.Context
import io.github.gighi947.ankeshelf.service.AppContainer
import java.io.File

/** UI 测试辅助：创建数据隔离的 AppContainer（cacheDir 下唯一临时目录），
 *  不触碰真机/应用的真实书架与设置数据。 */
fun createTestContainer(context: Context): AppContainer {
    val dir = File(context.cacheDir, "ui-test-${System.nanoTime()}")
    return AppContainer(context, dir)
}
