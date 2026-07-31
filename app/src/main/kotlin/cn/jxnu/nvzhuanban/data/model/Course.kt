package cn.jxnu.nvzhuanban.data.model

/**
 * 课表上的一节课。一节课在一周中的某一天占据若干节次（如第 1-2 节）。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    /** 1 = 周一，7 = 周日 */
    val weekday: Int,
    /** 起始节次（从 1 开始） */
    val startSection: Int,
    /** 结束节次（含） */
    val endSection: Int,
    /** 上课的周次列表，如 [1,3,5,7,9,11,13,15] 表示奇数周 */
    val weeks: List<Int>,
    val credit: Float,
    val type: CourseType = CourseType.LECTURE,
    /**
     * 课表格里第三段的原文 —— 学生页大致是「合班X老师.N班」或「YY级XX专业N班」，
     * 教师页则是教师所授班级（"YY级XX专业N班"）。仅作展示，不参与逻辑判断；
     * 给老师视角的课表用，因为老师页拿不到 dgStudentLesson 里的 teacher 字段。
     */
    val className: String = "",
) {
    val sectionCount: Int get() = endSection - startSection + 1
    fun isInWeek(week: Int): Boolean = week in weeks
}

enum class CourseType { LECTURE, LAB, PE, SEMINAR, ONLINE }
