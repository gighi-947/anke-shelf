package io.github.gighi947.ankeshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme
import org.junit.Rule
import org.junit.Test

/** Root 外壳（root）：四 Tab 导航 + 空书架初始态（数据隔离容器）。 */
class RootNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val container by lazy {
        createTestContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun setRoot() {
        compose.setContent {
            AnkeShelfTheme(settings = container.settings.getAll()) { AnkeShelfRoot(container) }
        }
    }

    @Test
    fun defaultTabIsShelfWithEmptyState() {
        setRoot()
        compose.onNodeWithText("安科书架").assertIsDisplayed()
        compose.onNodeWithText("书架为空").assertIsDisplayed()
        compose.onNodeWithText("导入 EPUB").assertIsDisplayed()
        compose.onNodeWithText("从 NGA 下载").assertIsDisplayed()
    }

    @Test
    fun bottomTabsNavigateBetweenScreens() {
        setRoot()

        compose.onNodeWithText("下载").performClick()
        compose.onNodeWithText("NGA 下载").assertIsDisplayed()

        compose.onNodeWithText("搜索").performClick()
        compose.onNodeWithText("全文检索").assertIsDisplayed()

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("外观").assertIsDisplayed()

        compose.onNodeWithText("书架").performClick()
        compose.onNodeWithText("安科书架").assertIsDisplayed()
    }
}
