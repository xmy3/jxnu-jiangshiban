package cn.jxnu.nvzhuanban.ui.screens.schedule

import cn.jxnu.nvzhuanban.data.model.Course
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
