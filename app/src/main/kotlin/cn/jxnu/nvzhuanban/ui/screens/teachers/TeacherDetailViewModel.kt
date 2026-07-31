package cn.jxnu.nvzhuanban.ui.screens.teachers

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.jxnu.nvzhuanban.data.model.TeacherInfo
import cn.jxnu.nvzhuanban.data.repository.TeacherDetailRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class TeacherDetailViewModel(
    val userNum: String,
    private val repo: TeacherDetailRepository = TeacherDetailRepository.instance,
) : UiStateViewModel<TeacherInfo>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): TeacherInfo = repo.fetch(userNum)

    companion object {
        fun factory(userNum: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { TeacherDetailViewModel(userNum) }
        }
    }
}
