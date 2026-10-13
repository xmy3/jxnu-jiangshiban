package cn.jxnu.nvzhuanban.ui.widget

import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCourseBoundaryTest {
    private val morning = course("早课", 1, 2)
    private val afternoon = course("下午课", 6, 7)

    @Test
    fun `last course disappears from highlight at its scheduled end update`() {
        val courses = listOf(morning)
        val updateMinute = WidgetUpdateScheduler.nextSignificantMinute(9 * 60 + 29, courses)!!
        assertEquals(9 * 60 + 30, updateMinute)
        assertNull(nextWidgetCourse(courses, updateMinute))
        assertFalse(isWidgetCourseOngoing(morning, updateMinute))
        assertNull(WidgetUpdateScheduler.nextSignificantMinute(updateMinute, courses))
    }

    @Test
    fun `end update advances to later course before its start alarm`() {
        val courses = listOf(morning, afternoon)
        val updateMinute = WidgetUpdateScheduler.nextSignificantMinute(9 * 60 + 29, courses)!!
        assertEquals(afternoon, nextWidgetCourse(courses, updateMinute))
        assertFalse(isWidgetCourseOngoing(afternoon, updateMinute))
        assertEquals(14 * 60, WidgetUpdateScheduler.nextSignificantMinute(updateMinute, courses))
    }

    @Test
    fun `course stays ongoing before end and starts at inclusive start boundary`() {
        assertEquals(morning, nextWidgetCourse(listOf(morning), 9 * 60 + 29))
        assertTrue(isWidgetCourseOngoing(morning, 9 * 60 + 29))
        assertTrue(isWidgetCourseOngoing(morning, 8 * 60))
        assertFalse(isWidgetCourseOngoing(morning, 8 * 60 - 1))
    }

    private fun course(name: String, start: Int, end: Int) = SnapshotCourse(
        name = name, location = "N101", teacher = "", startSection = start, endSection = end,
        weekday = 1, weeks = listOf(6, 7),
    )
}
