package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class Exam(
    val id: String,
    val courseName: String,
    val courseCode: String,
    /**
     * 考试日期。教务系统返回的 HH:mm 部分不可靠（往往是 00:00），所以本字段实际只有日期有意义；
     * UI 不展示具体时间，状态判定也只看日期。保留 [LocalDateTime] 类型是为了不动 ExamPage 的解析。
     */
    val startTime: LocalDateTime,
    /** 考试时长（分钟）。教务系统不提供，也不在 UI 中显示。保留字段以兼容已有调用方。 */
    val durationMinutes: Int = 120,
    val location: String,
    val seat: String? = null,
    /** 备注（教务系统返回为空时为 null） */
    val remark: String? = null,
    /** 考核方式：考试 / 考查 */
    val examType: String = "考试",
) {
    fun daysLeftFrom(now: LocalDateTime): Long =
        ChronoUnit.DAYS.between(now.toLocalDate(), startTime.toLocalDate())

    /**
     * 状态按日期判断；不看具体小时，因为教务系统的考试时间字段不真实。
     * 今天 → TODAY；过去 → FINISHED；未来 → UPCOMING。
     */
    fun statusAt(now: LocalDateTime): ExamStatus {
        val today = now.toLocalDate()
        val examDate = startTime.toLocalDate()
        return when {
            examDate.isBefore(today) -> ExamStatus.FINISHED
            examDate.isEqual(today) -> ExamStatus.TODAY
            else -> ExamStatus.UPCOMING
        }
    }
}

enum class ExamStatus { UPCOMING, TODAY, FINISHED }
