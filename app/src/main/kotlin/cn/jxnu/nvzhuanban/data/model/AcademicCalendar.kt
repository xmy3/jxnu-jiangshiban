package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 某个实际日期的校历安排；[sourceDate] 为空表示停课，否则按该教学日的课表上课。 */
data class CalendarAdjustment(
    val date: LocalDate,
    val name: String,
    val sourceDate: LocalDate? = null,
) {
    val shortLabel: String
        get() = sourceDate?.let { "补${weekdayLabel(it)}" } ?: name

    val description: String
        get() = sourceDate?.let { "$name，补${weekdayLabel(it)}的课" } ?: "${name}放假"

    private fun weekdayLabel(date: LocalDate): String =
        "周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
}

/**
 * 将整学期课表投影到实际日期，原始课程和用户编辑的周次保持不变。
 *
 * 规则仅用于已核实的学期，不按月份或国家节假日推断其他学期的学校安排。
 * 2026 秋校历：https://jwc.jxnu.edu.cn/Jxzl_20260901.pdf
 * 校历的「补周五的课」未注明原日期，按补课当周的周五及教学周取课。
 * 暂定校运会、仅适用于本科生的停课安排不在通用规则中处理。
 */
object AcademicCalendar {
    private val adjustmentsBySemester: Map<LocalDate, Map<LocalDate, CalendarAdjustment>> = mapOf(
        // 教务的学期标识是名义 9/1；周坐标统一由 SemesterPhase 对齐到 8/31。
        LocalDate.of(2026, 9, 1) to buildList {
            addHolidays("中秋", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27))
            addHolidays("国庆", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
            add(CalendarAdjustment(LocalDate.of(2026, 10, 10), "国庆调课", LocalDate.of(2026, 10, 9)))
            add(CalendarAdjustment(LocalDate.of(2026, 12, 30), "元旦调课", LocalDate.of(2027, 1, 1)))
            addHolidays("元旦", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3))
        }.associateBy { it.date },
    )

    fun adjustmentOn(semesterStart: LocalDate?, date: LocalDate): CalendarAdjustment? =
        adjustmentsBySemester[semesterStart]?.get(date)

    /** 普通日返回当天，停课日返回 null，补课日返回教学源日期；不递归处理源日期的假期。 */
    fun teachingDate(semesterStart: LocalDate?, date: LocalDate): LocalDate? {
        val adjustment = adjustmentOn(semesterStart, date) ?: return date
        return adjustment.sourceDate
    }

    fun adjustmentsForWeek(semesterStart: LocalDate?, week: Int): List<CalendarAdjustment> {
        if (semesterStart == null || week < 1) return emptyList()
        val adjustments = adjustmentsBySemester[semesterStart] ?: return emptyList()
        val monday = SemesterPhase.weekOneMonday(semesterStart).plusWeeks(week.toLong() - 1)
        return (0L..6L).mapNotNull { adjustments[monday.plusDays(it)] }
    }

    /**
     * 结果已经按源教学周过滤，调用方无需再用显示周过滤 [Course.weeks]。
     * 补课只改显示列 [Course.weekday]，保留 id、weeks 等原值供详情和周次编辑使用。
     */
    fun coursesForWeek(courses: List<Course>, semesterStart: LocalDate?, week: Int): List<Course> {
        if (week < 1 || courses.isEmpty()) return emptyList()
        if (semesterStart == null || adjustmentsForWeek(semesterStart, week).isEmpty()) {
            return courses.filter { it.isInWeek(week) }
        }
        val firstMonday = SemesterPhase.weekOneMonday(semesterStart)
        val monday = firstMonday.plusWeeks(week.toLong() - 1)
        return (0L..6L).flatMap { offset ->
            val actualDate = monday.plusDays(offset)
            val sourceDate = teachingDate(semesterStart, actualDate) ?: return@flatMap emptyList()
            val sourceWeek = (ChronoUnit.DAYS.between(firstMonday, sourceDate) / 7).toInt() + 1
            courses.filter { it.weekday == sourceDate.dayOfWeek.value && it.isInWeek(sourceWeek) }
                .map { course ->
                    if (course.weekday == actualDate.dayOfWeek.value) course
                    else course.copy(weekday = actualDate.dayOfWeek.value)
                }
        }
    }

    private fun MutableList<CalendarAdjustment>.addHolidays(name: String, start: LocalDate, end: LocalDate) {
        for (offset in 0L..ChronoUnit.DAYS.between(start, end)) {
            add(CalendarAdjustment(start.plusDays(offset), name))
        }
    }
}
