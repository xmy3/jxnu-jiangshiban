package cn.jxnu.nvzhuanban.ui.screens.calendar

import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.model.CalendarFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarHighlightTest {
    private val autumn2025 = entry("2025-2026学年第一学期")
    private val spring2026 = entry("2025-2026学年第二学期")
    private val autumn2026 = entry("2026-2027学年第一学期")
    private val spring2027 = entry("2026-2027学年第二学期")
    private val entries = listOf(spring2027, autumn2026, spring2026, autumn2025)

    @Test
    fun `each verified semester highlights its own calendar on the first day`() {
        assertCurrent("2025-09-01", autumn2025)
        assertCurrent("2026-03-02", spring2026)
        assertCurrent("2026-08-31", autumn2026)
    }

    @Test
    fun `exam periods remain part of the current semester`() {
        assertCurrent("2026-01-10", autumn2025)
        assertCurrent("2026-07-10", spring2026)
        assertCurrent("2027-01-10", autumn2026)
    }

    @Test
    fun `winter vacation begins on the official date`() {
        assertCurrent("2026-01-16", autumn2025)
        assertUpcoming("2026-01-17", spring2026)
        assertUpcoming("2026-03-01", spring2026)
        assertCurrent("2026-03-02", spring2026)
    }

    @Test
    fun `summer vacation moves from second semester to next academic year`() {
        assertCurrent("2026-07-12", spring2026)
        assertUpcoming("2026-07-13", autumn2026)
        assertUpcoming("2026-08-30", autumn2026)
        assertCurrent("2026-08-31", autumn2026)
    }

    @Test
    fun `winter vacation crosses calendar years within the same academic year`() {
        assertCurrent("2026-12-31", autumn2026)
        assertCurrent("2027-01-17", autumn2026)
        assertUpcoming("2027-01-18", spring2027)
    }

    @Test
    fun `mid autumn national day and new year short holidays stay in current semester`() {
        assertCurrent("2026-09-25", autumn2026)
        assertCurrent("2026-10-01", autumn2026)
        assertCurrent("2026-10-07", autumn2026)
        assertCurrent("2027-01-01", autumn2026)
    }

    @Test
    fun `missing next calendar leaves all entries ordinary during winter vacation`() {
        assertNull(highlightedCalendar(listOf(autumn2025, autumn2026, spring2027), date("2026-01-17")))
    }

    @Test
    fun `summer vacation never skips an unpublished next semester for a further future one`() {
        assertNull(highlightedCalendar(listOf(autumn2025, spring2026, spring2027), date("2026-07-13")))
    }

    @Test
    fun `missing current calendar does not fall back to an older or newer semester`() {
        assertNull(highlightedCalendar(listOf(autumn2025, spring2026, spring2027), date("2026-10-11")))
    }

    @Test
    fun `next calendar can be highlighted during vacation before its dates are verified`() {
        assertUpcoming("2027-02-15", spring2027)
        assertUpcoming("2027-02-28", spring2027)
    }

    @Test
    fun `unverified semester stops being highlighted on its nominal aligned opening day`() {
        // 2027/3/1 为周一；从这天开始，未核实的春季学期不再冒充“即将开学”。
        assertNull(highlightedCalendar(entries, date("2027-03-01")))
        assertNull(highlightedCalendar(entries, date("2027-07-15")))
    }

    @Test
    fun `unknown dates before and after verified semesters conservatively have no highlight`() {
        assertNull(highlightedCalendar(entries, date("2025-08-31")))
        assertNull(highlightedCalendar(entries, date("2028-01-10")))
    }

    @Test
    fun `correction wins independent of list order`() {
        val correction = spring2026.copy(title = spring2026.title + "（调整）", isCorrection = true)
        for (candidates in listOf(listOf(spring2026, correction), listOf(correction, spring2026))) {
            assertEquals(correction, highlightedCalendar(candidates, date("2026-07-10"))?.entry)
            assertEquals(correction, highlightedCalendar(candidates, date("2026-02-10"))?.entry)
        }
    }

    @Test
    fun `perpetual and whole academic year calendars are never candidates`() {
        val perpetual = autumn2026.copy(isPerpetual = true)
        val wholeYear = entry("2026-2027学年校历")
        assertNull(highlightedCalendar(listOf(perpetual, wholeYear), date("2026-10-11")))
    }

    @Test
    fun `title matching tolerates whitespace and full width academic year separator`() {
        val formatted = entry("2026－2027学年  第一学期（调整）").copy(isCorrection = true)
        assertEquals(formatted, highlightedCalendar(listOf(formatted), date("2026-10-11"))?.entry)
    }

    @Test
    fun `title matching requires a valid full academic year and semester`() {
        val malformed = listOf(
            entry("26-27学年第一学期"),
            entry("2026-2028学年第一学期"),
            entry("2026-2027学年第三学期"),
            entry("2026-2027学年"),
        )
        assertNull(highlightedCalendar(malformed, date("2026-10-11")))
    }

    @Test
    fun `calendar semester comes from title rather than an old file name`() {
        val renamed = autumn2026.copy(url = "https://jwc.jxnu.edu.cn/Jxzl_20250901.pdf")
        assertEquals(renamed, highlightedCalendar(listOf(renamed), date("2026-10-11"))?.entry)
    }

    @Test
    fun `empty list has no highlighted entry`() {
        assertNull(highlightedCalendar(emptyList(), date("2026-10-11")))
    }

    private fun assertCurrent(today: String, expected: CalendarEntry) {
        val highlight = highlightedCalendar(entries, date(today))
        assertEquals(expected, highlight?.entry)
        assertFalse("$today 应为当前学期", highlight!!.upcoming)
    }

    private fun assertUpcoming(today: String, expected: CalendarEntry) {
        val highlight = highlightedCalendar(entries, date(today))
        assertEquals(expected, highlight?.entry)
        assertTrue("$today 应为寒暑假中的下一学期", highlight!!.upcoming)
    }

    private fun entry(title: String) = CalendarEntry(
        title = title,
        url = "https://jwc.jxnu.edu.cn/Jxzl_${title.hashCode()}.pdf",
        fileType = CalendarFileType.PDF,
        isPerpetual = false,
        isCorrection = false,
    )

    private fun date(value: String): LocalDate = LocalDate.parse(value)
}
