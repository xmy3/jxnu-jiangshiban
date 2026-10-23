package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AlertDiff
import cn.jxnu.nvzhuanban.data.model.ExamKind
import cn.jxnu.nvzhuanban.data.model.ExamReminderPlan
import cn.jxnu.nvzhuanban.data.model.toDatedExam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ExamAlertRepositoryTest {
    private val date = LocalDate.of(2026, 10, 25)
    private val regularHtml = """<table id="_ctl1_dgContent"><tr><td>CS101</td><td>高数</td>
        <td>2026000000</td><td>2026-10-25 00:00:00</td><td>W1203</td><td>12</td><td></td></tr></table>"""

    private fun makeupHtml(
        time: String = "2026-10-25 00:00:00",
        room: String = "W1204",
        method: String = "闭卷",
        kind: String = "补考",
    ): String = "<table id='_ctl6_dgContent'><tr>" +
        listOf("瑶湖", "学院", "班级", "学生", "2026000000", "CS101", "高数", "普通", "学院", room, time, method, kind)
            .joinToString("") { "<td>$it</td>" } + "</tr></table>"

    @Test
    fun `makeup and regular exams of the same course both reach reminders`() = runTest {
        val repo = ExamAlertRepository(ExamRepository { regularHtml }, MakeupExamRepository { makeupHtml() })
        val exams = repo.refresh()
        assertEquals(setOf(ExamKind.REGULAR, ExamKind.MAKEUP), exams.map { it.kind }.toSet())
        assertEquals(2, AlertDiff.examFingerprints(exams).size)
        val day = ExamReminderPlan.plan(exams, date.minusDays(1).atTime(19, 0)).single()
        assertEquals(listOf("高数", "高数（补考）"), day.items.map { it.courseName })
        assertEquals("W1204", day.items.last().location)
    }

    @Test
    fun `rescheduled makeup is a change and updates reminder date and room`() = runTest {
        var html = makeupHtml()
        val repo = ExamAlertRepository(ExamRepository { regularHtml }, MakeupExamRepository { html })
        val known = AlertDiff.examFingerprints(repo.refresh())
        html = makeupHtml(time = "2026-10-26", room = "W2204")
        val exams = repo.refresh()
        val change = AlertDiff.exams(known, exams)
        assertTrue(change.added.isEmpty())
        assertEquals(ExamKind.MAKEUP, change.changed.single().kind)
        val days = ExamReminderPlan.plan(exams, date.minusDays(1).atTime(19, 0))
        assertEquals(listOf(date, date.plusDays(1)), days.map { it.date })
        assertEquals("W2204", days.last().items.single().location)
    }

    @Test
    fun `only explicit unambiguous valid dates are scheduled`() = runTest {
        var html = makeupHtml(time = "", method = "请于3月10日下午参加考试")
        val repo = ExamAlertRepository(ExamRepository { regularHtml }, MakeupExamRepository { html })
        assertEquals(1, repo.refresh().size)
        html = makeupHtml(time = "", method = "请于2026年10月25日参加考试", kind = "缓考")
        assertEquals(ExamKind.DEFERRED, repo.refresh().last().kind)
        html = makeupHtml(time = "2026-02-30")
        assertEquals(1, repo.refresh().size)
        html = makeupHtml(time = "2026-10-25", method = "2026-10-26参加考试")
        assertEquals(1, repo.refresh().size)
        html = makeupHtml(time = "2026年10月25日至26日")
        assertEquals(1, repo.refresh().size)
        html = makeupHtml(time = "", method = "报名截止2026年10月25日，考试另行通知")
        assertEquals(1, repo.refresh().size)
    }

    @Test
    fun `regular makeup and deferred exams have separate identities`() = runTest {
        val regular = ExamRepository { regularHtml }.refreshForAlerts().single()
        val makeup = MakeupExamRepository { makeupHtml() }.refreshForAlerts().single()
        val deferred = makeup.copy(remark = "缓考")
        val exams = listOf(regular, makeup.toDatedExam()!!, deferred.toDatedExam()!!)
        assertEquals(3, AlertDiff.examFingerprints(exams).size)
        assertTrue(AlertDiff.exams(AlertDiff.examFingerprints(exams), exams.reversed()).isEmpty)
    }

    @Test
    fun `partial makeup response cannot supply a destructive reminder replacement`() = runTest {
        var html = makeupHtml()
        val makeup = MakeupExamRepository { html }
        val repo = ExamAlertRepository(ExamRepository { regularHtml }, makeup)
        repo.refresh()
        val previous = makeup.fetchAll()
        html = html.replace("<td>W1204</td>", "")
        assertTrue(runCatching { repo.refresh() }.isFailure)
        assertEquals(previous, makeup.fetchAll())
        html = "<table id='_ctl6_dgContent'></table>"
        assertEquals(listOf(ExamKind.REGULAR), repo.refresh().map { it.kind })
    }

    @Test
    fun `clearing during the second source rejects the combined response`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = ExamAlertRepository(ExamRepository { regularHtml }, MakeupExamRepository {
            started.complete(Unit)
            release.await()
            makeupHtml()
        })
        val old = async { repo.refresh() }
        started.await()
        repo.clearCache()
        release.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
    }
}
