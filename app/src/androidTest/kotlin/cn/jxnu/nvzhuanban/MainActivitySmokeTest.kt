package cn.jxnu.nvzhuanban

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToLoginOrMainShell() {
        compose.waitUntil(timeoutMillis = 15_000) {
            hasText("欢迎使用江师办") || hasText("课表") || hasText("我的")
        }
    }

    @Test
    fun privacyIsReachableWhenMainShellIsShown() {
        compose.waitUntil(timeoutMillis = 15_000) {
            hasText("欢迎使用江师办") || hasText("我的")
        }
        if (hasText("欢迎使用江师办")) return

        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("隐私说明").performClick()
        compose.onNodeWithText("本地保存").assertExists()
        compose.onNodeWithText("不会上传").assertExists()
    }

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
