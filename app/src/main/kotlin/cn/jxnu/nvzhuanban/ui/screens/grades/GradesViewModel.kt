package cn.jxnu.nvzhuanban.ui.screens.grades

import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

data class GradesData(
    val semesters: List<SemesterSummary>,
    val graduationMinimumCredits: Float? = null,
)

class GradesViewModel(
    private val repo: GradeRepository = GradeRepository.instance,
    private val graduationAuditRepo: GraduationAuditRepository = GraduationAuditRepository.instance,
) : UiStateViewModel<GradesData>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): GradesData {
        val semesters = if (refresh) repo.refresh().semesters else repo.getAllSemesters()
        val minimumCredits = runCatching {
            if (refresh) graduationAuditRepo.refresh().minimumCredits else graduationAuditRepo.fetch().minimumCredits
        }.getOrNull()
        return GradesData(
            semesters = semesters,
            graduationMinimumCredits = minimumCredits,
        )
    }
}
