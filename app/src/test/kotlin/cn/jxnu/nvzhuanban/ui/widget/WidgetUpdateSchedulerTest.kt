package cn.jxnu.nvzhuanban.ui.widget

import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetUpdateSchedulerTest {

    @Test
    fun returnsNextCourseStartOrEndMinute() {
        val course = course(startSection = 3, endSection = 4)
        val beforeStart = SectionTimetable.startMinutes(3) - 1
        val afterStart = SectionTimetable.startMinutes(3)

        assertEquals(
            SectionTimetable.startMinutes(3),
            WidgetUpdateScheduler.nextSignificantMinute(beforeStart, listOf(course)),
        )
        assertEquals(
            SectionTimetable.endMinutes(4),
            WidgetUpdateScheduler.nextSignificantMinute(afterStart, listOf(course)),
        )
    }

    @Test
    fun returnsNullWhenThereIsNoLaterSignificantMinute() {
        val course = course(startSection = 1, endSection = 2)
        val afterEnd = SectionTimetable.endMinutes(2)

        assertNull(WidgetUpdateScheduler.nextSignificantMinute(afterEnd, listOf(course)))
        assertNull(WidgetUpdateScheduler.nextSignificantMinute(8 * 60, emptyList()))
    }

    private fun course(startSection: Int, endSection: Int) = SnapshotCourse(
        name = "课程",
        location = "N101",
        teacher = "Teacher",
        startSection = startSection,
        endSection = endSection,
        weekday = 1,
        weeks = listOf(1),
    )
}
