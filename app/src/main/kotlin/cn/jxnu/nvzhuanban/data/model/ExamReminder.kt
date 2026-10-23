package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 一场考试里「考前一天提醒」真正需要的信息。
 *
 * 刻意不带时刻：教务系统返回的考试时间不可靠（常常是 00:00），提醒里写一个假时间比不写更糟。
 */
data class ExamReminderItem(
    val courseName: String,
    val location: String,
    val seat: String?,
)

/** 某个考试日要提醒的一组考试。同一天连考两门合成一条通知，不分开弹。 */
data class ExamReminderDay(
    val date: LocalDate,
    val items: List<ExamReminderItem>,
)

/**
 * 「考前一天提醒」的纯规划层（无 Android 依赖，JVM 可测）。
 *
 * 为什么要有这个东西：出分靠后台轮询（最多迟到几小时还能忍），但考试是**有截止时间**的 ——
 * 早上 8 点考试，7 点才告诉你「今天有考试」就没意义了。所以走 AlarmManager 的定期闹钟，
 * 提前一晚把「明天考什么、在哪个考场」发出来。
 *
 * 时刻选 20:00 的理由：白天在教室、早上再提醒来不及准备；20 点学生基本在宿舍，
 * 还来得及找准考证、买笔、设闹钟。
 */
object ExamReminderPlan {

    /** 提醒时刻：考试**前一天** 20:00。 */
    val REMINDER_TIME: LocalTime = LocalTime.of(20, 0)

    /** 某场考试对应的提醒时刻。 */
    fun reminderAt(examDate: LocalDate): LocalDateTime =
        examDate.minusDays(1).atTime(REMINDER_TIME)

    /**
     * 从完整考试列表算出需要保留的提醒日。
     *
     * 丢掉两类：
     *  - 提醒时刻已经过去且没有待送达闹钟的。已排入 [pendingDates] 的明日考试仍保留，
     *    让系统延迟投递的闹钟有机会送达；到考试当天则过期。已发送的提醒已从存档移除，
     *    不会再次进入计划。首次在八点后开启提醒仍不补发。
     *  - 同一门课重复出现的行：去重后不会在同一条通知里念两遍。
     *
     * 返回按日期升序，方便调用方稳定地分配 requestCode / 打日志。
     */
    fun plan(
        exams: List<Exam>,
        now: LocalDateTime,
        pendingDates: Set<LocalDate> = emptySet(),
    ): List<ExamReminderDay> =
        exams
            .groupBy { it.startTime.toLocalDate() }
            .map { (date, sameDay) ->
                ExamReminderDay(
                    date = date,
                    items = sameDay.map { it.toReminderItem() }.distinct(),
                )
            }
            .filter {
                reminderAt(it.date).isAfter(now) ||
                    (it.date in pendingDates && it.date.isAfter(now.toLocalDate()))
            }
            .sortedBy { it.date }

    private fun Exam.toReminderItem() = ExamReminderItem(
        courseName = courseName,
        location = location,
        seat = seat?.takeIf { it.isNotBlank() },
    )
}
