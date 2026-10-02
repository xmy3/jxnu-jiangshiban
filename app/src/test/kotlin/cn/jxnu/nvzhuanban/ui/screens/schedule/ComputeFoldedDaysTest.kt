package cn.jxnu.nvzhuanban.ui.screens.schedule

import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.AcademicCalendar
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 周末列收起规则：周六整学期无课就收起周六；周日仅在周六也收起时跟着一起收，
 * 绝不单独收起周日。
 */
class ComputeFoldedDaysTest {

    private fun course(weekday: Int) = Course(
        id = "c$weekday",
        name = "课程",
        teacher = "张三",
        location = "教学楼101",
        weekday = weekday,
        startSection = 1,
        endSection = 2,
        weeks = (1..18).toList(),
        credit = 0f,
    )

    @Test
    fun `周六周日都无课时一起收起`() {
        val courses = (1..5).map(::course)
        assertEquals(setOf(6, 7), computeFoldedDays(courses))
    }

    @Test
    fun `仅周日无课时不收起`() {
        val courses = (1..6).map(::course)
        assertEquals(emptySet<Int>(), computeFoldedDays(courses))
    }

    @Test
    fun `仅周六无课时单独收起周六`() {
        val courses = (1..5).map(::course) + course(7)
        assertEquals(setOf(6), computeFoldedDays(courses))
    }

    @Test
    fun `周末都有课时不收起`() {
        val courses = (1..7).map(::course)
        assertEquals(emptySet<Int>(), computeFoldedDays(courses))
    }

    @Test
    fun `空课表不收起`() {
        assertEquals(emptySet<Int>(), computeFoldedDays(emptyList()))
    }

    @Test
    fun `国庆补课周展开周六且普通周恢复原折叠`() {
        val courses = (1..5).map(::course)
        val start = LocalDate.of(2026, 9, 1)
        val makeupDays = AcademicCalendar.adjustmentsForWeek(start, 6)
            .map { it.date.dayOfWeek.value }.toSet()
        assertEquals(emptySet<Int>(), computeFoldedDays(courses, makeupDays))
        val normalDays = AcademicCalendar.adjustmentsForWeek(start, 7)
            .map { it.date.dayOfWeek.value }.toSet()
        assertEquals(setOf(6, 7), computeFoldedDays(courses, normalDays))
    }

    @Test
    fun `整周假期合并连续日期且补课提示不猜源日期`() {
        val start = LocalDate.of(2026, 9, 1)
        val holidays = calendarWeekSummary(AcademicCalendar.adjustmentsForWeek(start, 5))
        assertEquals("10/1–10/4 国庆放假", holidays)
        val makeupWeek = calendarWeekSummary(AcademicCalendar.adjustmentsForWeek(start, 6))
        org.junit.Assert.assertTrue(makeupWeek.contains("10/5–10/7 国庆放假"))
        org.junit.Assert.assertTrue(makeupWeek.contains("10/10"))
        org.junit.Assert.assertTrue(makeupWeek.contains("补周五"))
        org.junit.Assert.assertFalse(makeupWeek.contains("10/2"))
    }

    @Test
    fun `周日只有晚自习合成卡时周日列保留`() {
        // 晚自习经仓库层注入后就是一张普通 Course，折叠判定应把它当"周日有课"：
        // 周六无课 → 收周六，但周日因晚自习卡保留（不收 {6,7}）
        val eveningStudy = cn.jxnu.nvzhuanban.data.model.EveningStudy
            .synthesize(setOf(7), totalWeeks = 18, courses = emptyList())
        val courses = (1..5).map(::course) + eveningStudy
        assertEquals(setOf(6), computeFoldedDays(courses))
    }
}
