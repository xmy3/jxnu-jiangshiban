package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate

/**
 * 补缓考考试安排。
 *
 * 数据来源 `MyControl/All_Display.aspx?UserControl=xfz_Test_BHK.ascx`。
 *
 * 教务网把考试时间/地点的"教室号"、"考试时间"两列经常留空，真正的时间和地点是用自然语言
 * 写在 [examMethod]（页面列名"考试方式"）里，例如
 * `请于3月10日下午15:00在瑶湖校区C田径场参加考试，联系电话：88880000`。
 * [remark] 表示性质：「补考」或「缓考」。
 */
data class MakeupExam(
    val id: String,
    val campus: String,
    val college: String,
    val className: String,
    val studentName: String,
    val studentId: String,
    val courseCode: String,
    val courseName: String,
    val courseType: String,
    val managingDept: String,
    /** 教室号；教务网常留空，此时为 null。 */
    val location: String?,
    /** 考试时间字符串；教务网常留空，此时为 null。真实时间通常在 [examMethod] 里。 */
    val examTime: String?,
    /** "考试方式" 列；实际内容是「请于X月X日X时在X地参加考试」的整段说明文字。 */
    val examMethod: String?,
    /** 「补考」/「缓考」；为空时按"补考"展示。 */
    val remark: String?,
)

private val fullExamDate = Regex("(?<!\\d)(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})(?:日)?(?!\\d)")
private val labeledExamDate = Regex("(?:请于|考试(?:时间|日期)\\s*[：:为]?)\\s*" + fullExamDate.pattern)
private val examDateRange = Regex(fullExamDate.pattern + "\\s*(?:至|到|[-—~～])")

internal fun MakeupExam.toDatedExam(): Exam? {
    if (listOfNotNull(examTime, examMethod).any { examDateRange.containsMatchIn(it) }) return null
    if (!fullExamDate.containsMatchIn(examTime.orEmpty()) &&
        !labeledExamDate.containsMatchIn(examMethod.orEmpty())
    ) return null
    val dates = listOfNotNull(examTime, examMethod).flatMap { text ->
        fullExamDate.findAll(text).map { match ->
            runCatching {
                LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull()
        }.toList()
    }
    val date = dates.distinct().singleOrNull() ?: return null
    val kind = if (remark?.contains("缓考") == true) ExamKind.DEFERRED else ExamKind.MAKEUP
    val label = if (kind == ExamKind.DEFERRED) "缓考" else "补考"
    return Exam(
        id = id,
        courseName = "$courseName（$label）",
        courseCode = courseCode,
        startTime = date.atStartOfDay(),
        location = location.orEmpty(),
        remark = remark,
        examType = label,
        kind = kind,
    )
}
