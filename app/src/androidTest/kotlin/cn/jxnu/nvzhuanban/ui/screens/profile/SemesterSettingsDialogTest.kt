package cn.jxnu.nvzhuanban.ui.screens.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.jxnu.nvzhuanban.data.storage.SemesterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class SemesterSettingsDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingBackToSchoolWeeksDiscardsTheManualOverride() {
        val start = LocalDate.of(2026, 9, 1)
        val monday = LocalDate.of(2026, 9, 7)
        var savedWeeks: Int? = -1
        var savedMonday: LocalDate? = null
        compose.setContent {
            MaterialTheme {
                SemesterSettingsDialog(
                    "26-27第1学期", start, 18, SemesterSettings.Override(start, monday, 20),
                    onDismiss = {}, onReset = {},
                    onSave = { date, weeks -> savedMonday = date; savedWeeks = weeks },
                )
            }
        }
        compose.onNodeWithText("跟随教务").performScrollTo().performClick()
        compose.onNodeWithText("跟随教务（当前 18 周）").assertExists()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(monday, savedMonday)
            assertNull(savedWeeks)
        }
    }

    @Test
    fun nonMondayCannotBeSaved() {
        val start = LocalDate.of(2026, 9, 1)
        compose.setContent {
            MaterialTheme {
                SemesterSettingsDialog(
                    "26-27第1学期", start, 18, SemesterSettings.Override(start, start, null),
                    onDismiss = {}, onReset = {}, onSave = { _, _ -> error("不能保存非周一起点") },
                )
            }
        }
        compose.onNodeWithText("请选择周一作为第 1 周的起点").assertExists()
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }
}
