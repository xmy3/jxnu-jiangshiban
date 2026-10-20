package cn.jxnu.nvzhuanban.data.work

import cn.jxnu.nvzhuanban.data.model.ExamReminderDay
import cn.jxnu.nvzhuanban.data.model.ExamReminderItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 考前一天提醒的文案回归。
 *
 * 这条通知是**半夜响**的，写错了没人会去验证第二遍（用户第二天的第一反应是「这啥」）。
 * 而且它必须只说「明天」+ 考场/座位：教务系统给的考试时间是假的，写个具体时刻会害人迟到。
 */
class AlertNotifierCopyTest {

    private fun item(name: String, room: String = "B203", seat: String? = null) =
        ExamReminderItem(courseName = name, location = room, seat = seat)

    private fun day(vararg items: ExamReminderItem) =
        ExamReminderDay(LocalDate.of(2026, 1, 9), items.toList())

    @Test
    fun `单门考试说一门`() {
        assertEquals("明天有考试", AlertNotifier.examReminderTitle(day(item("高等数学"))))
    }

    @Test
    fun `多门考试带数量`() {
        assertEquals(
            "明天有 2 门考试",
            AlertNotifier.examReminderTitle(day(item("高等数学"), item("大学英语"))),
        )
    }

    @Test
    fun `正文带考场和座位`() {
        assertEquals(
            "高等数学 · B203 · 座位 12",
            AlertNotifier.examReminderBody(day(item("高等数学", "B203", "12"))),
        )
    }

    @Test
    fun `没有考场时不留下多余分隔符`() {
        assertEquals("高等数学", AlertNotifier.examReminderBody(day(item("高等数学", ""))))
    }

    @Test
    fun `超过三条折叠成等几项`() {
        val body = AlertNotifier.examReminderBody(
            day(item("课程一"), item("课程二"), item("课程三"), item("课程四")),
        )
        assertEquals("课程一 · B203、课程二 · B203、课程三 · B203 等 4 项", body)
    }
}
