package cn.jxnu.nvzhuanban.ui.screens.grades

import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

data class GradesData(
    val semesters: List<SemesterSummary>,
    val graduationMinimumCredits: Float? = null,
    /**
     * 成绩页官方「累计学分」（含校外慕课/交流，教务显示成 `59 + 4 = 63`）。
     * 头部「已修学分」优先用它，和「我的」「培养方案」同源；null/0 时才退回逐门求和。
     */
    val cumulativeCredits: Float? = null,
)

class GradesViewModel(
    private val repo: GradeRepository = GradeRepository.instance,
    private val graduationAuditRepo: GraduationAuditRepository = GraduationAuditRepository.instance,
) : UiStateViewModel<GradesData>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): GradesData {
        val parsed = if (refresh) repo.refresh() else repo.fetchAll()
        val minimumCredits = runCatching {
            if (refresh) graduationAuditRepo.refresh().minimumCredits else graduationAuditRepo.fetch().minimumCredits
        }.getOrNull()
        return GradesData(
            semesters = parsed.semesters,
            graduationMinimumCredits = minimumCredits,
            cumulativeCredits = parsed.meta.totalCredit,
        )
    }
}
