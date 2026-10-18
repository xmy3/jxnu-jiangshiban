package cn.jxnu.nvzhuanban.data.model

data class TrainingPlan(
    val minimumCredits: Float? = null,
    /**
     * 毕业审核页的「当前所修学分总数」：**只统计培养方案内课程**，
     * 不含校外慕课/赴外交流学分（那部分只在成绩页「累计学分」里体现，形如 `59 + 4 = 63`）。
     */
    val currentCredits: Float? = null,
    val overallStandardScore: Float? = null,
    val degreeCourseTotal: Int? = null,
    val retakenDegreeCourseCount: Int? = null,
    val creditSummaries: List<TrainingPlanCreditSummary> = emptyList(),
    val sections: List<TrainingPlanSection> = emptyList(),
    /**
     * 成绩页的官方「累计学分」（含校外慕课/交流）。null 表示这次没取到成绩页。
     * 与 [currentCredits] 是两套口径，UI 必须用 [earnedCredits]，否则会和
     * 「我的 → 已修学分」「成绩页累计学分」对不上。
     */
    val cumulativeCredits: Float? = null,
) {
    /**
     * 已修学分：培养方案内学分与成绩页累计学分取**较大值**（口径细节见 [earnedCreditsOf]）。
     *
     * 取 max 而不是相加：教务哪天把交流学分并进毕业审核，[currentCredits] 自己就会变大，
     * 相加会重复计数；取 max 在「未并入口径」和「已并入口径」下都得到同一个总数。
     */
    val earnedCredits: Float?
        get() = earnedCreditsOf(currentCredits, cumulativeCredits)

    /** 成绩页累计比培养方案多出的部分（即校外慕课/交流学分）；没有差额时为 null。 */
    val externalCredits: Float?
        get() = currentCredits?.let { plan ->
            // 0.001f 容差：浮点累加抖动不能让 63 - 62.999 = 0.001 这种噪声冒出来
            earnedCredits?.minus(plan)?.takeIf { it > 0.001f }
        }
}

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
