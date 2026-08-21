package io.github.gighi947.ankeshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gighi947.ankeshelf.ui.download.DownloadScreen
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme
import org.junit.Rule
import org.junit.Test

/** 安科下载页（screen + drawer/二级详情）。 */
class DownloadScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val container by lazy {
        createTestContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun setScreen() {
        compose.setContent {
            AnkeShelfTheme(settings = container.settings.getAll()) {
                DownloadScreen(container = container, onChanged = {}, onBack = {})
            }
        }
    }

    @Test
    fun groupListShowsThreeEntries() {
        setScreen()
        compose.onNodeWithText("安科下载").assertIsDisplayed()
        compose.onNodeWithText("登录配置").assertIsDisplayed()
        compose.onNodeWithText("下载").assertIsDisplayed()
        compose.onNodeWithText("已下载").assertIsDisplayed()
    }

    @Test
    fun configEntryOpensDetailAndBackReturns() {
        setScreen()
        compose.onNodeWithText("登录配置").performClick()
        compose.onNodeWithText("ngaPassportUid").assertIsDisplayed()
        compose.onNodeWithText("ngaPassportCid").assertIsDisplayed()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("登录配置").assertIsDisplayed()
    }
}
