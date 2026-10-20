package cn.jxnu.nvzhuanban.ui.widget

import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 20)

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

    @Test
    fun planTargetsTheNextBoundaryPlusBackupSlot() {
        val plan = WidgetUpdateScheduler.planNext(
            zone = zone,
            today = today,
            nowMins = SectionTimetable.startMinutes(2),
            todayCourses = listOf(course(startSection = 3, endSection = 4)),
            backupDelayMillis = 4 * 60 * 1000L,
        )

        val primary = today.atTime(9, 40).atZone(zone).toInstant().toEpochMilli()
        assertEquals(primary, plan.primaryAtMillis)
        // 兜底槽晚于主槽，但仍在 Doze 下一次唤醒窗口之内 —— 主槽迟到时由它纠正高亮。
        assertEquals(primary + 4 * 60 * 1000L, plan.backupAtMillis)
    }

    @Test
    fun planRollsToTomorrowWhenTodaysCoursesAreOver() {
        val plan = WidgetUpdateScheduler.planNext(
            zone = zone,
            today = today,
            nowMins = SectionTimetable.endMinutes(2),
            todayCourses = listOf(course(startSection = 1, endSection = 2)),
            backupDelayMillis = 1_000L,
        )

        val expected = today.plusDays(1).atTime(0, 1).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, plan.primaryAtMillis)
        assertEquals(expected + 1_000L, plan.backupAtMillis)
    }

    @Test
    fun backupDelayStaysInsideOneDozeWakeupWindow() {
        // Doze 下同一个 App 大约每 9 分钟才允许一次 allow-while-idle 唤醒；
        // 兜底槽必须落在这个窗口内，否则等于没兜。
        assertTrue(WidgetUpdateScheduler.BACKUP_DELAY_MILLIS in 1..(9 * 60 * 1000L))
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
