package cn.jxnu.nvzhuanban.data.model

import cn.jxnu.nvzhuanban.data.network.pages.ExamPage
import org.junit.Assert.*
import org.junit.Test

class ParsedExamAlertTest {
    private fun exams(time: String, code: String = "CS101") = ExamPage.parse(
        """<table id="_ctl1_dgContent"><tr><td>$code</td><td>高数</td><td>2026000000</td>
            <td>$time</td><td>W1203</td><td>12</td><td></td></tr></table>""",
    )

    @Test
    fun `time only change in parsed HTML does not create a notification`() {
        val old = exams("2026-10-24 00:00:00")
        val current = exams("2026-10-24 14:30:00")
        assertTrue(AlertDiff.exams(AlertDiff.examFingerprints(old), current).isEmpty)
    }

    @Test
    fun `rescheduled parsed exam is changed rather than added`() {
        val old = exams("2026-10-24 00:00:00")
        val change = AlertDiff.exams(AlertDiff.examFingerprints(old), exams("2026-10-25 00:00:00"))
        assertEquals(0, change.added.size)
        assertEquals(1, change.changed.size)
    }

    @Test
    fun `legacy persisted baseline is matched after time changes`() {
        val old = exams("2026-10-24 00:00:00")
        val legacy = old.associate { it.id to AlertDiff.examFingerprint(it) }
        assertTrue(AlertDiff.exams(legacy, exams("2026-10-24 14:30:00")).isEmpty)
        val moved = AlertDiff.exams(legacy, exams("2026-10-25 00:00:00"))
        assertEquals(0, moved.added.size)
        assertEquals(1, moved.changed.size)
    }

    @Test
    fun `different course with same name and date is still new`() {
        val old = exams("2026-10-24 00:00:00")
        val current = old + exams("2026-10-24 00:00:00", "CS102")
        val change = AlertDiff.exams(AlertDiff.examFingerprints(old), current)
        assertEquals(listOf("CS102"), change.added.map { it.courseCode })
        assertTrue(change.changed.isEmpty())
    }
}
