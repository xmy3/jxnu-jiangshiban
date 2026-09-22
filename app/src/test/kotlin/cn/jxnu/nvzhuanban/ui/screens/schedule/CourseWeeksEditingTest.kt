package cn.jxnu.nvzhuanban.ui.screens.schedule

import cn.jxnu.nvzhuanban.ui.components.UiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourseWeeksEditingTest {
    @Test
    fun `offline and unknown semester cannot edit while loaded online semester can`() {
        val loaded = ScheduleScreenState(
            selectedWeek = 1,
            totalWeeks = 18,
            semester = "2026 spring",
            semesterStart = LocalDate.of(2026, 3, 1),
            data = UiState.Success(emptyList()),
        )
        assertTrue(canEditCourseWeeks(loaded))
        assertFalse(canEditCourseWeeks(loaded.copy(isOffline = true)))
        assertFalse(canEditCourseWeeks(loaded.copy(semesterStart = null)))
        assertFalse(canEditCourseWeeks(loaded.copy(data = UiState.Loading)))
    }
}
