package cn.jxnu.nvzhuanban.ui.screens.profile

import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamStatus
import java.time.LocalDateTime

internal data class ProfileExamSummary(val value: String, val label: String)

/** null 表示该数据源尚未成功加载，不能据此判定没有考试。补缓考日期不可靠，不计算倒计时。 */
internal fun profileExamSummary(
    exams: List<Exam>?,
    makeupCount: Int?,
    now: LocalDateTime,
): ProfileExamSummary {
    if (makeupCount != null && makeupCount > 0) {
        return ProfileExamSummary("$makeupCount 门", "补缓考安排")
    }
    val nearestDays = exams?.asSequence()
        ?.filter { it.statusAt(now) != ExamStatus.FINISHED }
        ?.map { it.daysLeftFrom(now) }
        ?.filter { it >= 0L }
        ?.minOrNull()
    return ProfileExamSummary(
        value = when (nearestDays) {
            null -> "—"
            0L -> "今天"
            else -> "$nearestDays 天"
        },
        label = when {
            nearestDays != null -> if (makeupCount == null) "距常规考试" else "距下场考试"
            exams != null && makeupCount == 0 -> "暂无待考"
            else -> "考试安排"
        },
    )
}
