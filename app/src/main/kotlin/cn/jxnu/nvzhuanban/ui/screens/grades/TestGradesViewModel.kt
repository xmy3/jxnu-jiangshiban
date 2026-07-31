package cn.jxnu.nvzhuanban.ui.screens.grades

import cn.jxnu.nvzhuanban.data.model.TestGradeReport
import cn.jxnu.nvzhuanban.data.repository.TestGradeRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

/**
 * 「考试出分」子 Tab 的 ViewModel。与 [GradesViewModel] 分离，便于：
 *  - 两个 Tab 独立刷新（互不影响 isRefreshing 转圈）
 *  - 各自维护自己的缓存与错误态
 *
 * 进入 Grades 页时不立即触发（init 不 load），首次切到该 Tab 时由 UI 显式调用 [loadIfNeeded]。
 * 这样避免学期成绩用户不去看出分页时也付出一次额外网络请求。
 */
class TestGradesViewModel(
    private val repo: TestGradeRepository = TestGradeRepository.instance,
) : UiStateViewModel<TestGradeReport>() {

    /** 首次进入该 Tab 时由 UI 调用；已成功加载过（state 已是 Success）的会自动跳过。 */
    fun loadIfNeeded() {
        if (state.value is UiState.Success) return
        load()
    }

    override suspend fun fetch(refresh: Boolean): TestGradeReport =
        if (refresh) repo.refresh() else repo.fetchAll()
}
