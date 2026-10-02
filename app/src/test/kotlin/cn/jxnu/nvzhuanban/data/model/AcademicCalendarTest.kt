package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AcademicCalendarTest {
    private val autumn = LocalDate.of(2026, 9, 1)

    private fun course(day: Int, weeks: List<Int> = (1..18).toList(), id: String = "course-$day") = Course(
        id = id,
        name = "课程$day",
        teacher = "教师",
        location = "W1203",
        weekday = day,
        startSection = 1,
        endSection = 2,
        weeks = weeks,
        credit = 2f,
    )

    @Test
    fun `national holiday removes October 1 through 7 and preserves adjacent dates`() {
        for (day in 1..7) {
            val date = LocalDate.of(2026, 10, day)
            assertNull(AcademicCalendar.teachingDate(autumn, date))
            assertEquals("国庆", AcademicCalendar.adjustmentOn(autumn, date)?.name)
        }
        for (date in listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 8))) {
            assertEquals(date, AcademicCalendar.teachingDate(autumn, date))
            assertNull(AcademicCalendar.adjustmentOn(autumn, date))
        }
    }

    @Test
    fun `mid autumn and new year holidays include weekends`() {
        for (day in 25..27) {
            assertNull(AcademicCalendar.teachingDate(autumn, LocalDate.of(2026, 9, day)))
        }
        for (day in 1..3) {
            assertNull(AcademicCalendar.teachingDate(autumn, LocalDate.of(2027, 1, day)))
        }
    }

    @Test
    fun `normal class day and tentative or cohort specific events are not inferred as holidays`() {
        for (date in listOf(
            LocalDate.of(2026, 9, 20),
            LocalDate.of(2026, 10, 22),
            LocalDate.of(2026, 10, 23),
            LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 3),
        )) {
            assertNull(AcademicCalendar.adjustmentOn(autumn, date))
            assertEquals(date, AcademicCalendar.teachingDate(autumn, date))
        }
    }

    @Test
    fun `rules do not leak into unknown or missing semesters`() {
        val date = LocalDate.of(2026, 10, 2)
        for (semester in listOf(null, LocalDate.of(2025, 9, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2027, 9, 1))) {
            assertNull(AcademicCalendar.adjustmentOn(semester, date))
            assertEquals(date, AcademicCalendar.teachingDate(semester, date))
            assertTrue(AcademicCalendar.adjustmentsForWeek(semester, 5).isEmpty())
            val courses = listOf(course(5, listOf(5)), course(5, listOf(6)))
            assertEquals(listOf(courses.first()), AcademicCalendar.coursesForWeek(courses, semester, 5))
        }
    }

    @Test
    fun `first holiday week retains only September classes`() {
        val courses = (1..7).map { course(it) }
        val result = AcademicCalendar.coursesForWeek(courses, autumn, 5)
        assertEquals(listOf(1, 2, 3), result.map { it.weekday })
        assertEquals(7, courses.size)
        result.forEach { assertSame(courses[it.weekday - 1], it) }
    }

    @Test
    fun `Saturday makeup uses Friday courses and selected teaching week instead of Saturday or odd week`() {
        val friday = course(5, listOf(6), "friday-even")
        val courses = listOf(
            course(1), course(4), friday,
            course(5, listOf(5), "friday-odd"),
            course(6), course(7),
        )
        val original = courses.toList()
        val result = AcademicCalendar.coursesForWeek(courses, autumn, 6)
        val saturday = result.filter { it.weekday == 6 }

        assertEquals(listOf(friday.copy(weekday = 6)), saturday)
        assertTrue(result.contains(friday))
        assertFalse(result.any { it.id == "friday-odd" || it.id == "course-6" || it.weekday == 1 })
        assertTrue(result.any { it.weekday == 4 })
        assertTrue(result.any { it.weekday == 7 })
        assertEquals(original, courses)
        assertSame(friday.weeks, saturday.single().weeks)
        assertEquals(LocalDate.of(2026, 10, 9), AcademicCalendar.teachingDate(autumn, LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `makeup source can itself be a holiday and cross the calendar year`() {
        val friday = course(5, listOf(18))
        val result = AcademicCalendar.coursesForWeek(listOf(friday, course(3), course(6)), autumn, 18)
        assertEquals(listOf(friday.copy(weekday = 3)), result)
        assertEquals(LocalDate.of(2027, 1, 1), AcademicCalendar.teachingDate(autumn, LocalDate.of(2026, 12, 30)))
        assertNull(AcademicCalendar.teachingDate(autumn, LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `evening study follows the same holiday and makeup rules`() {
        val studies = EveningStudy.synthesize(setOf(4, 5, 7), 18, emptyList(), "1203")
        assertTrue(AcademicCalendar.coursesForWeek(studies, autumn, 5).isEmpty())
        val result = AcademicCalendar.coursesForWeek(studies, autumn, 6)
        assertEquals(listOf(4, 5, 6, 7), result.map { it.weekday })
        val makeup = result.single { it.weekday == 6 }
        assertTrue(makeup.isEveningStudy)
        assertEquals("evening-study-5", makeup.id)
        assertEquals("W1203", makeup.location)
    }

    @Test
    fun `week adjustments are ordered by actual date with descriptive labels`() {
        val changes = AcademicCalendar.adjustmentsForWeek(autumn, 6)
        assertEquals(listOf(5, 6, 7, 10), changes.map { it.date.dayOfMonth })
        assertEquals("国庆", changes.first().shortLabel)
        assertEquals("国庆放假", changes.first().description)
        assertEquals("补周五", changes.last().shortLabel)
        assertEquals("国庆调课，补周五的课", changes.last().description)
    }

    @Test
    fun `empty courses and invalid weeks are empty and ordinary weeks preserve course identity`() {
        assertTrue(AcademicCalendar.coursesForWeek(emptyList(), autumn, 6).isEmpty())
        assertTrue(AcademicCalendar.coursesForWeek(listOf(course(1)), autumn, 0).isEmpty())
        assertTrue(AcademicCalendar.adjustmentsForWeek(autumn, 0).isEmpty())
        val course = course(2, listOf(2))
        assertSame(course, AcademicCalendar.coursesForWeek(listOf(course), autumn, 2).single())
    }
}
