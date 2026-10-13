package cn.jxnu.nvzhuanban.ui.screens.profile

import cn.jxnu.nvzhuanban.data.model.Exam
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileExamSummaryTest {
    private val now = LocalDateTime.of(2026, 10, 13, 9, 0)

    @Test
    fun `makeup exams are shown when regular exams are empty or finished`() {
        listOf(emptyList(), listOf(exam(-1))).forEach { regular ->
            assertEquals(ProfileExamSummary("2 门", "补缓考安排"), profileExamSummary(regular, 2, now))
        }
    }

    @Test
    fun `unknown makeup dates take precedence over a regular countdown`() {
        assertEquals(ProfileExamSummary("1 门", "补缓考安排"), profileExamSummary(listOf(exam(3)), 1, now))
    }

    @Test
    fun `failed or pending source never implies there are no exams`() {
        assertEquals("考试安排", profileExamSummary(emptyList(), null, now).label)
        assertEquals("考试安排", profileExamSummary(null, 0, now).label)
        assertEquals("补缓考安排", profileExamSummary(null, 1, now).label)
    }

    @Test
    fun `no exams requires both sources to have loaded`() {
        assertEquals(ProfileExamSummary("—", "暂无待考"), profileExamSummary(emptyList(), 0, now))
        assertEquals("暂无待考", profileExamSummary(listOf(exam(-1)), 0, now).label)
    }

    @Test
    fun `regular countdown is explicit while makeup source is unknown`() {
        assertEquals(ProfileExamSummary("3 天", "距常规考试"), profileExamSummary(listOf(exam(3)), null, now))
        assertEquals(ProfileExamSummary("今天", "距下场考试"), profileExamSummary(listOf(exam(0)), 0, now))
    }

    private fun exam(daysFromNow: Long) = Exam(
        id = "test-$daysFromNow", courseName = "示例课程", courseCode = "test",
        startTime = now.plusDays(daysFromNow), location = "示例教室",
    )
}
