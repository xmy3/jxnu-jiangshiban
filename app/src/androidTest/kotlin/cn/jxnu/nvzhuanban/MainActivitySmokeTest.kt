package cn.jxnu.nvzhuanban

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
            textExists("欢迎使用江师办") || textExists("课表") || textExists("我的")
        }
    }

    @Test
    fun privacyIsReachableWhenMainShellIsShown() {
        compose.waitUntil(timeoutMillis = 15_000) {
            textExists("欢迎使用江师办") || textExists("我的")
        }
        if (textExists("欢迎使用江师办")) return

        compose.onNodeWithText("我的").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("关于"))
        compose.onNodeWithText("关于").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { textExists("查看完整隐私说明") }
        compose.onNodeWithText("查看完整隐私说明").performClick()
        compose.onNodeWithText("本地保存").assertExists()
        compose.onNodeWithText("加密凭据").assertExists()
        compose.onNodeWithText("头像请求").assertExists()
        compose.onNodeWithText("App 不上传、不收集任何用户数据。", substring = true).assertExists()
    }

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
