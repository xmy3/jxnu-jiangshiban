package cn.jxnu.nvzhuanban.ui.screens.profile

import cn.jxnu.nvzhuanban.data.model.StudentBasicInfo
import cn.jxnu.nvzhuanban.data.repository.StudentInfoCheckRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class StudentInfoViewModel(
    private val repo: StudentInfoCheckRepository = StudentInfoCheckRepository.instance,
) : UiStateViewModel<StudentBasicInfo>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): StudentBasicInfo =
        if (refresh) repo.refresh() else repo.fetch()
}
