package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import cn.jxnu.nvzhuanban.data.model.TrainingPlan
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class TrainingPlanViewModel(
    private val repo: GraduationAuditRepository = GraduationAuditRepository.instance,
    private val gradeRepo: GradeRepository = GradeRepository.instance,
) : UiStateViewModel<TrainingPlan>() {

    init { load() }

    /**
     * 上一次成功读到的成绩页累计学分。
     *
     * [fetch] 在 refresh 时会清掉 [GradeRepository] 缓存再重拉；那次网络失败时如果直接落 null，
     * UI 会把已经显示的 63 悄悄退回 59（毕业审核页那一套不含慕课的口径），看起来像"刷新后学分掉了"。
     * 保留上一次的值，一次网络抖动最多是数字不更新，不会倒退。
     */
    @Volatile
    private var lastCumulativeCredits: Float? = null

    override suspend fun fetch(refresh: Boolean): TrainingPlan {
        val plan = (if (refresh) repo.refresh() else repo.fetch()).trainingPlan
        // best-effort：成绩页只用来校正「已修学分」口径（补上校外慕课/交流那部分）。
        // 取不到就退回毕业审核页的 currentCredits，绝不因为成绩页失败把整页打成错误态。
        val cumulative = runCatching {
            (if (refresh) gradeRepo.refresh() else gradeRepo.fetchAll()).meta.totalCredit
        }.getOrNull() ?: lastCumulativeCredits
        lastCumulativeCredits = cumulative
        return plan.copy(cumulativeCredits = cumulative)
    }
}
