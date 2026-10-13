package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleDateInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun returningAfterWeekRolloverImmediatelySynchronizesRetainedWeek() {
        val visible = mutableStateOf(true)
        var deviceDate = LocalDate.of(2026, 10, 11)
        var retainedDate = deviceDate
        compose.setContent {
            if (visible.value) {
                val today = rememberScheduleDate(
                    onDayChanged = { retainedDate = it },
                    todayProvider = { deviceDate },
                )
                Text(today.toString())
            }
        }
        compose.onNodeWithText("2026-10-11").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            deviceDate = LocalDate.of(2026, 10, 12)
            visible.value = true
        }
        compose.onNodeWithText("2026-10-12").assertExists()
        compose.runOnIdle { assertEquals(deviceDate, retainedDate) }
    }

    @Test
    fun resumingAfterWeekRolloverSynchronizesDateAndRetainedWeek() {
        val deviceDate = AtomicReference(LocalDate.of(2026, 10, 11))
        var retainedDate = deviceDate.get()
        compose.setContent {
            val today = rememberScheduleDate(
                onDayChanged = { retainedDate = it },
                todayProvider = deviceDate::get,
            )
            Text(today.toString())
        }
        compose.onNodeWithText("2026-10-11").assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        deviceDate.set(LocalDate.of(2026, 10, 12))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("2026-10-12").assertExists()
        compose.runOnIdle { assertEquals(deviceDate.get(), retainedDate) }
    }
}
