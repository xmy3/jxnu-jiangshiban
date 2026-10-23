package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AlertDiff
import cn.jxnu.nvzhuanban.data.model.ExamReminderPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AlertRefreshTest {
    private val examHtml = """<table id="_ctl1_dgContent"><tr><td>CS101</td><td>高数</td>
        <td>2026000000</td><td>2026-10-24 00:00:00</td><td>W1203</td><td>12</td><td></td></tr></table>"""
    private val gradeHtml = """<table id="_ctl6_dgContent"><tr><td>26-27第1学期</td><td>CS101</td>
        <td>高数</td><td>4</td><td>80</td><td></td><td>1.0</td><td></td></tr></table>"""

    @Test
    fun `partial exam response keeps pending reminders and baseline after 20 oclock`() = runTest {
        var html = examHtml
        val repo = ExamRepository { html }
        val initial = repo.refreshForAlerts()
        val before = LocalDateTime.of(2026, 10, 23, 19, 59)
        var pending = ExamReminderPlan.plan(initial, before)
        var known = AlertDiff.examFingerprints(initial)
        val originalKnown = known
        html = examHtml.replace("00:00:00", "00:00")
        val refresh = runCatching { repo.refreshForAlerts() }
        refresh.onSuccess {
            known = AlertDiff.examFingerprints(it)
            pending = ExamReminderPlan.plan(it, before.plusMinutes(2), pending.map { day -> day.date }.toSet())
        }
        assertTrue("Partial response must not reach destructive worker updates", refresh.isFailure)
        assertEquals(originalKnown, known)
        assertEquals(1, pending.size)
        assertEquals(initial, repo.fetchAll())
        html = examHtml
        pending = ExamReminderPlan.plan(repo.refreshForAlerts(), before.plusMinutes(5), pending.map { it.date }.toSet())
        assertEquals(1, pending.size)
    }

    @Test
    fun `complete empty exam page can still remove old reminders`() = runTest {
        val repo = ExamRepository { "<table id='_ctl1_dgContent'></table>" }
        assertTrue(repo.refreshForAlerts().isEmpty())
    }

    @Test
    fun `partial grades cannot replace an existing complete cache or baseline`() = runTest {
        var html = gradeHtml
        val repo = GradeRepository { html }
        val initial = repo.refreshForAlerts()
        html = gradeHtml.replace("<td>1.0</td>", "")
        assertTrue(runCatching { repo.refreshForAlerts() }.isFailure)
        assertEquals(initial, repo.fetchAll())
    }

    @Test
    fun `foreground still exposes partially parsed lists`() = runTest {
        val repo = ExamRepository { examHtml.replace("00:00:00", "00:00") }
        assertTrue(repo.refresh().isEmpty())
    }
}
