package cn.jxnu.nvzhuanban.data.model

import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeGradeAlertTest {
    @Test
    fun `same-name exchange courses survive reordering and individual score changes`() {
        val old = grades("A", "A").mapIndexed { index, grade -> grade.copy(score = (80 + index).toString()) }
        val known = AlertDiff.gradeFingerprints(old)
        assertTrue(AlertDiff.grades(known, old.reversed()).isEmpty)
        val changed = old.reversed().map { if (it.score == "80") it.copy(score = "90") else it }
        val change = AlertDiff.grades(known, changed)
        assertTrue(change.added.isEmpty())
        assertEquals(listOf("90"), change.changed.map { it.score })
    }

    @Test
    fun `an additional same-name exchange course is still new`() {
        val known = AlertDiff.gradeFingerprints(grades("A"))
        assertEquals(1, AlertDiff.grades(known, grades("A", "A")).added.size)
    }

    private fun grades(vararg names: String): List<Grade> = GradePage.parse(
        "<table><tr><th>学期名称</th><th>学校</th><th>课程名称</th><th>学分</th><th>成绩</th></tr>" +
            names.joinToString("") { name ->
                "<tr><td>26-27第1学期</td><td>Test University</td><td>$name</td><td>2</td><td>88</td></tr>"
            } + "</table>",
    ).semesters.flatMap { it.grades }

    @Test
    fun `inserting a course reports only the new course`() {
        val known = AlertDiff.gradeFingerprints(grades("A", "B"))
        val change = AlertDiff.grades(known, grades("C", "A", "B"))
        assertEquals(listOf("C"), change.added.map { it.courseName })
        assertTrue(change.changed.isEmpty())
    }

    @Test
    fun `reordering and removing rows do not report new scores`() {
        val known = AlertDiff.gradeFingerprints(grades("A", "B", "C"))
        assertTrue(AlertDiff.grades(known, grades("C", "A")).isEmpty)
    }

    @Test
    fun `legacy positional baseline migrates without losing score changes`() {
        val old = grades("A", "B")
        val known = old.associate { it.id to AlertDiff.gradeFingerprint(it) }
        val current = grades("B", "A").map { if (it.courseName == "A") it.copy(score = "90") else it }
        val change = AlertDiff.grades(known, current)
        assertTrue(change.added.isEmpty())
        assertEquals(listOf("A"), change.changed.map { it.courseName })
    }
}
