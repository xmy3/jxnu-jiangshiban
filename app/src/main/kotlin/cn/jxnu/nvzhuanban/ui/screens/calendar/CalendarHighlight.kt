package cn.jxnu.nvzhuanban.ui.screens.calendar

import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import java.time.LocalDate

internal data class CalendarHighlight(
    val entry: CalendarEntry,
    val upcoming: Boolean,
)

/**
 * 学期中突出当前校历，寒暑假突出紧接着的下一学期；目标尚未发布时保持普通列表。
 *
 * 起止日期来自已核实的官方校历（见 docs/academic-calendar.md），包含考试期。
 * 未核实学期的名义开学日只用作假期突出状态的截止点，不推测其放假时间。
 */
internal fun highlightedCalendar(entries: List<CalendarEntry>, today: LocalDate): CalendarHighlight? {
    val current = verifiedSemesters
        .filter { !today.isBefore(it.startsOn) }
        .maxByOrNull { it.startsOn }
        ?: return null

    val upcoming = !today.isBefore(current.vacationStartsOn)
    val target = if (upcoming) current.semester.next() else current.semester
    if (upcoming) {
        val nextStart = verifiedSemesters.firstOrNull { it.semester == target }?.startsOn
            ?: SemesterPhase.weekOneMonday(target.nominalStart())
        // 到达已知开学日（缺失时为名义周一）后，不继续推测未核实学期的范围。
        if (!today.isBefore(nextStart)) return null
    }

    val candidates = entries.filter { !it.isPerpetual && semesterOf(it.title) == target }
    val entry = candidates.firstOrNull { it.isCorrection } ?: candidates.firstOrNull() ?: return null
    return CalendarHighlight(entry = entry, upcoming = upcoming)
}

private data class CalendarSemester(val academicYear: Int, val term: Int) {
    fun next(): CalendarSemester =
        if (term == 1) copy(term = 2) else CalendarSemester(academicYear + 1, 1)

    fun nominalStart(): LocalDate =
        if (term == 1) LocalDate.of(academicYear, 9, 1) else LocalDate.of(academicYear + 1, 3, 1)
}

private data class VerifiedSemester(
    val semester: CalendarSemester,
    val startsOn: LocalDate,
    val vacationStartsOn: LocalDate,
)

private val verifiedSemesters = listOf(
    VerifiedSemester(CalendarSemester(2025, 1), LocalDate.of(2025, 9, 1), LocalDate.of(2026, 1, 17)),
    VerifiedSemester(CalendarSemester(2025, 2), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 7, 13)),
    VerifiedSemester(CalendarSemester(2026, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2027, 1, 18)),
)

private val semesterTitle = Regex("""(?<!\d)(\d{4})\s*[-—–－]\s*(\d{4})\s*学年\s*第\s*([一二12])\s*学期""")

private fun semesterOf(title: String): CalendarSemester? {
    val match = semesterTitle.find(title) ?: return null
    val firstYear = match.groupValues[1].toIntOrNull() ?: return null
    val secondYear = match.groupValues[2].toIntOrNull() ?: return null
    if (secondYear != firstYear + 1) return null
    val term = if (match.groupValues[3] in listOf("一", "1")) 1 else 2
    return CalendarSemester(firstYear, term)
}
