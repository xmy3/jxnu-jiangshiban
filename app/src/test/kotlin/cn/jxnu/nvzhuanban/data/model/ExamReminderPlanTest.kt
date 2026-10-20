package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「考前一天提醒」排期逻辑的回归。
 *
 * 失败模式和出分提醒不同：这里错一步是**静默漏提醒** —— 学生不会知道"昨晚本该有一条通知"，
 * 等到发现时考试已经考完了。所以边界（今天考、明天考但已过点、跨多天）逐条钉死。
 */
class ExamReminderPlanTest {

    private fun exam(
        courseName: String = "高等数学",
        date: LocalDate = LocalDate.of(2026, 1, 9),
        location: String = "B203",
        seat: String? = null,
    ) = Exam(
        id = "$courseName-$date-$location",
        courseName = courseName,
        courseCode = "CS101",
        startTime = date.atStartOfDay(),
        location = location,
        seat = seat,
    )

    @Test
    fun `明天考试 → 今晚八点提醒`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val plan = ExamReminderPlan.plan(listOf(exam(date = LocalDate.of(2026, 1, 9))), now)

        assertEquals(1, plan.size)
        assertEquals(LocalDate.of(2026, 1, 9), plan[0].date)
        assertEquals(
            LocalDateTime.of(2026, 1, 8, 20, 0),
            ExamReminderPlan.reminderAt(plan[0].date),
        )
    }

    @Test
    fun `今天的考试不再提醒`() {
        val now = LocalDateTime.of(2026, 1, 9, 7, 0)
        val plan = ExamReminderPlan.plan(listOf(exam(date = LocalDate.of(2026, 1, 9))), now)
        assertTrue(plan.isEmpty())
    }

    /**
     * 明天考试、但现在已经过了昨晚八点：不能再排（AlarmManager 排不到过去，立刻补发等于
     * 在学生准备睡觉时突袭弹一条「明天有考试」）。
     */
    @Test
    fun `提醒时刻已过就不再补`() {
        val now = LocalDateTime.of(2026, 1, 8, 21, 30)
        val plan = ExamReminderPlan.plan(listOf(exam(date = LocalDate.of(2026, 1, 9))), now)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `刚好到点仍可排`() {
        val examDate = LocalDate.of(2026, 1, 9)
        val now = ExamReminderPlan.reminderAt(examDate).minusMinutes(1)
        val plan = ExamReminderPlan.plan(listOf(exam(date = examDate)), now)
        assertEquals(1, plan.size)
    }

    @Test
    fun `两天后的考试排在明晚`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val examDate = LocalDate.of(2026, 1, 10)
        val plan = ExamReminderPlan.plan(listOf(exam(date = examDate)), now)

        assertEquals(1, plan.size)
        // 提醒时刻是「考试前一天」，不是「今天」
        assertEquals(LocalDateTime.of(2026, 1, 9, 20, 0), ExamReminderPlan.reminderAt(plan[0].date))
    }

    @Test
    fun `同一天两门考试合并成一条`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val date = LocalDate.of(2026, 1, 9)
        val plan = ExamReminderPlan.plan(
            listOf(exam("高等数学", date, "B203"), exam("大学英语", date, "A101")),
            now,
        )

        assertEquals(1, plan.size)
        assertEquals(listOf("高等数学", "大学英语"), plan[0].items.map { it.courseName })
        assertEquals(listOf("B203", "A101"), plan[0].items.map { it.location })
    }

    @Test
    fun `重复的同一门课只提醒一次`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val date = LocalDate.of(2026, 1, 9)
        val plan = ExamReminderPlan.plan(listOf(exam(date = date), exam(date = date)), now)

        assertEquals(1, plan.size)
        assertEquals(1, plan[0].items.size)
    }

    @Test
    fun `多天按日期升序`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val plan = ExamReminderPlan.plan(
            listOf(
                exam("第三天", LocalDate.of(2026, 1, 11)),
                exam("明天", LocalDate.of(2026, 1, 9)),
                exam("后天", LocalDate.of(2026, 1, 10)),
            ),
            now,
        )

        assertEquals(
            listOf(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11)),
            plan.map { it.date },
        )
    }

    @Test
    fun `已考完的不进计划`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val plan = ExamReminderPlan.plan(
            listOf(
                exam("上周考完", LocalDate.of(2026, 1, 2)),
                exam("明天", LocalDate.of(2026, 1, 9)),
            ),
            now,
        )

        assertEquals(listOf(LocalDate.of(2026, 1, 9)), plan.map { it.date })
    }

    @Test
    fun `座位空白视为没有座位`() {
        val now = LocalDateTime.of(2026, 1, 8, 12, 0)
        val date = LocalDate.of(2026, 1, 9)
        val plan = ExamReminderPlan.plan(
            listOf(exam("空白座位", date, "B203", seat = "  "), exam("有座位", date, "B204", seat = "12")),
            now,
        )

        assertEquals(listOf(null, "12"), plan[0].items.map { it.seat })
    }

    @Test
    fun `没有考试时计划为空`() {
        assertTrue(ExamReminderPlan.plan(emptyList(), LocalDateTime.of(2026, 1, 8, 12, 0)).isEmpty())
    }
}
