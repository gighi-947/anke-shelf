package io.github.gighi947.ankeshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gighi947.ankeshelf.ui.settings.SettingsScreen
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme
import org.junit.Rule
import org.junit.Test

/** 设置页（screen + drawer/二级详情）：一级六项、二级面板与返回。 */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val container by lazy {
        createTestContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun setScreen() {
        compose.setContent {
            AnkeShelfTheme(settings = container.settings.getAll()) {
                SettingsScreen(
                    settings = container.settings,
                    refreshKey = 0,
                    books = emptyList(),
                    annotations = container.annotations,
                    statsGlobal = container.stats.getGlobal(),
                    appPaths = container.appPaths,
                    onOpenStats = {},
                    onOpenGuide = {},
                    onBack = {},
                    onChanged = {},
                    onClearAllData = {},
                )
            }
        }
    }

    @Test
    fun groupListShowsSixEntries() {
        setScreen()
        for (label in listOf("外观", "阅读", "操作", "统计", "数据", "帮助")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun appearanceOpensDetailWithThemeAndBackReturns() {
        setScreen()
        compose.onNodeWithText("外观").performClick()
        compose.onNodeWithText("主题").assertIsDisplayed()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("外观").assertIsDisplayed()
    }

    @Test
    fun readingOpensDetailWithFontSection() {
        setScreen()
        compose.onNodeWithText("阅读").performClick()
        compose.onNodeWithText("正文字体").assertIsDisplayed()
    }
}
