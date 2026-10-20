package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出分 / 考试变更比对逻辑的回归。
 *
 * 这套逻辑的失败模式很隐蔽：漏报（真出分了不提醒）用户根本察觉不到，误报（把老成绩当新
 * 成绩弹一遍）会直接把开关关掉。所以「什么算变化」逐条钉死在这里。
 */
class AlertDiffTest {

    private fun grade(
        id: String = "25-26第1学期-CS101",
        courseName: String = "高等数学",
        score: String = "88",
        gpa: Float? = 1.2f,
        makeupScore: String? = null,
    ) = Grade(
        id = id,
        semester = "25-26第1学期",
        courseName = courseName,
        courseCode = "CS101",
        credit = 4f,
        score = score,
        gpa = gpa,
        makeupScore = makeupScore,
    )

    private fun exam(
        id: String = "CS101-2026-01-08 00:00:00",
        date: LocalDateTime = LocalDateTime.of(2026, 1, 8, 0, 0),
        location: String = "B203",
        seat: String? = "12",
        remark: String? = null,
    ) = Exam(
        id = id,
        courseName = "高等数学",
        courseCode = "CS101",
        startTime = date,
        location = location,
        seat = seat,
        remark = remark,
    )

    @Test
    fun `empty known set reports every grade as new`() {
        val change = AlertDiff.grades(emptyMap(), listOf(grade(), grade(id = "second")))
        assertEquals(2, change.added.size)
        assertEquals(0, change.changed.size)
    }

    @Test
    fun `unchanged grades produce no change`() {
        val current = listOf(grade())
        val known = AlertDiff.gradeFingerprints(current)
        assertTrue(AlertDiff.grades(known, current).isEmpty)
    }

    @Test
    fun `a newly scored course is reported as added`() {
        val known = AlertDiff.gradeFingerprints(listOf(grade()))
        val change = AlertDiff.grades(known, listOf(grade(), grade(id = "new", courseName = "大学英语")))
        assertEquals(1, change.added.size)
        assertEquals("大学英语", change.added.first().courseName)
        assertEquals(0, change.changed.size)
    }

    @Test
    fun `a changed score is reported as changed not added`() {
        val known = AlertDiff.gradeFingerprints(listOf(grade(score = "60")))
        val change = AlertDiff.grades(known, listOf(grade(score = "88")))
        assertEquals(0, change.added.size)
        assertEquals(1, change.changed.size)
        assertEquals("88", change.changed.first().score)
    }

    @Test
    fun `a newly published makeup score is reported as changed`() {
        val known = AlertDiff.gradeFingerprints(listOf(grade(makeupScore = null)))
        val change = AlertDiff.grades(known, listOf(grade(makeupScore = "75")))
        assertEquals(0, change.added.size)
        assertEquals(1, change.changed.size)
    }

    @Test
    fun `a grade cleared by the school is dropped without noise`() {
        val known = AlertDiff.gradeFingerprints(listOf(grade(), grade(id = "removed")))
        assertTrue(AlertDiff.grades(known, listOf(grade())).isEmpty)
    }

    @Test
    fun `duplicate ids are only counted once`() {
        val current = listOf(grade(), grade())
        val change = AlertDiff.grades(emptyMap(), current)
        assertEquals(1, change.added.size)
    }

    @Test
    fun `exam time of day is ignored so a bogus HH mm cannot cause a false alarm`() {
        // 教务网的考试时间是假的（常见 00:00），同一天换个时分不该报「考试变更」
        val known = AlertDiff.examFingerprints(
            listOf(exam(date = LocalDateTime.of(2026, 1, 8, 0, 0))),
        )
        val change = AlertDiff.exams(
            known,
            listOf(exam(date = LocalDateTime.of(2026, 1, 8, 14, 30))),
        )
        assertTrue(change.isEmpty)
    }

    @Test
    fun `exam moved to another day is reported as changed`() {
        val known = AlertDiff.examFingerprints(listOf(exam()))
        val change = AlertDiff.exams(
            known,
            listOf(exam(date = LocalDateTime.of(2026, 1, 9, 0, 0))),
        )
        assertEquals(0, change.added.size)
        assertEquals(1, change.changed.size)
    }

    @Test
    fun `exam moved to another room or seat is reported as changed`() {
        val base = AlertDiff.examFingerprints(listOf(exam()))
        assertEquals(1, AlertDiff.exams(base, listOf(exam(location = "A101"))).changed.size)
        assertEquals(1, AlertDiff.exams(base, listOf(exam(seat = "30"))).changed.size)
    }

    @Test
    fun `exam fingerprint does not include the course name`() {
        // 名字由 id 唯一确定，改名字不改 id 时不该刷一条通知 —— 这里只断言指纹稳定
        val e = exam()
        assertEquals(AlertDiff.examFingerprint(e), AlertDiff.examFingerprint(e.copy(courseName = "高数")))
    }

    @Test
    fun `empty input produces empty change`() {
        assertTrue(AlertDiff.grades(emptyMap(), emptyList()).isEmpty)
        assertTrue(AlertDiff.exams(emptyMap(), emptyList()).isEmpty)
    }
}
