package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import cn.jxnu.nvzhuanban.data.model.TrainingPlan
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class TrainingPlanViewModel(
    private val repo: GraduationAuditRepository = GraduationAuditRepository.instance,
) : UiStateViewModel<TrainingPlan>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): TrainingPlan =
        (if (refresh) repo.refresh() else repo.fetch()).trainingPlan
}
