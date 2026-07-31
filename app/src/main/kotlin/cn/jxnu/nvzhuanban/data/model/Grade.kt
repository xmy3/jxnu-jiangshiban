package cn.jxnu.nvzhuanban.data.model

import kotlin.math.roundToInt

/**
 * 一门已结课课程的成绩。
 */
data class Grade(
    val id: String,
    /** 学期标识，如 "25-26第1学期" */
    val semester: String,
    val courseName: String,
    val courseCode: String,
    val credit: Float,
    /** 显示的分数或等级，如 "92"、"良好"、"通过" */
    val score: String,
    /**
     * 江师大「标准分」(实质即排名归一化绩点)，可正可负。
     * 用于 UI 上的「平均绩点」加权计算。
     * 教务系统的字段名是「标准分」(Z-score)，不是 4 分制 GPA。
     */
    val gpa: Float?,
    /** 补考成绩（无补考为 null） */
    val makeupScore: String? = null,
    /** 备注（教务系统返回为空时为 null） */
    val remark: String? = null,
) {
    /** 折算分（用于 GPA 计算时的"分数*学分"） */
    val weightedGpa: Float? get() = gpa?.let { it * credit }
}

/** 一个学期的成绩汇总（用于 UI 头部和分组） */
data class SemesterSummary(
    val semester: String,
    val grades: List<Grade>,
) {
    val totalCredit: Float = grades.sumOf { it.credit.toDouble() }.toFloat()
    val gpa: Float = run {
        val totalWeighted = grades.mapNotNull { it.weightedGpa }.sum()
        val totalCredits = grades.filter { it.gpa != null }.sumOf { it.credit.toDouble() }.toFloat()
        if (totalCredits > 0) totalWeighted / totalCredits else 0f
    }
    val count: Int = grades.size
}

/** 江师大学分都是整数，UI 显示去掉小数位节约空间；用 roundToInt 兜底浮点累加的精度抖动 */
internal fun Float.formatCredit(): String = roundToInt().toString()
