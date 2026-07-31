package cn.jxnu.nvzhuanban.data.model

data class TrainingPlan(
    val minimumCredits: Float? = null,
    val currentCredits: Float? = null,
    val overallStandardScore: Float? = null,
    val degreeCourseTotal: Int? = null,
    val retakenDegreeCourseCount: Int? = null,
    val creditSummaries: List<TrainingPlanCreditSummary> = emptyList(),
    val sections: List<TrainingPlanSection> = emptyList(),
)

data class TrainingPlanCreditSummary(
    val name: String,
    val earnedCredits: Float,
    val required: Boolean,
)

data class TrainingPlanSection(
    val title: String,
    val requirement: String? = null,
    val courses: List<TrainingPlanCourse>,
)

data class TrainingPlanCourse(
    val category: String? = null,
    val courseCode: String,
    val courseName: String,
    val openingOrder: String? = null,
    val isDegreeCourse: Boolean = false,
    /**
     * 学分。`null` 表示培养方案里"学分"列为空或非数字
     * （之前用 `0f` 兜底，会和"真正 0 学分课"无法区分；现在 UI 渲染要靠 null 来显示"-"占位）。
     */
    val credit: Float?,
    val examScore: String? = null,
    val makeupScore: String? = null,
    val examTime: String? = null,
    val remark: String? = null,
) {
    val isCompleted: Boolean
        get() = !examScore.isNullOrBlank() || !makeupScore.isNullOrBlank()
}
