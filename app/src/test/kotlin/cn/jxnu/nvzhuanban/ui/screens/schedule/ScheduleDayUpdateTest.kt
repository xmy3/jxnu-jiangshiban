package cn.jxnu.nvzhuanban.ui.screens.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleDayUpdateTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private val sundayState = ScheduleScreenState(
        selectedWeek = 6,
        totalWeeks = 18,
        semester = "26-27第1学期",
        semesterStart = LocalDate.of(2026, 9, 1),
        currentWeek = 6,
        isOffline = true,
    )

    @Test
    fun `offline week rollover moves current week and today button together`() {
        val advanced = advanceOfflineScheduleDay(sundayState, 6, monday)
        assertEquals(7, advanced.state.currentWeek)
        assertEquals(7, advanced.state.selectedWeek)
        assertEquals(7, advanced.baselineWeek)
    }

    @Test
    fun `offline rollover preserves manually selected week while updating today target`() {
        val advanced = advanceOfflineScheduleDay(sundayState.copy(selectedWeek = 2), 6, monday)
        assertEquals(7, advanced.state.currentWeek)
        assertEquals(2, advanced.state.selectedWeek)
        assertEquals(7, advanced.baselineWeek)
    }
}
