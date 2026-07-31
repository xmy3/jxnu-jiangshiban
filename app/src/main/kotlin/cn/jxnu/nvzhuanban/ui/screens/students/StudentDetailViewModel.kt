package cn.jxnu.nvzhuanban.ui.screens.students

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.jxnu.nvzhuanban.data.model.StudentInfo
import cn.jxnu.nvzhuanban.data.repository.StudentDetailRepository
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel

class StudentDetailViewModel(
    val userNum: String,
    private val repo: StudentDetailRepository = StudentDetailRepository.instance,
) : UiStateViewModel<StudentInfo>() {

    init { load() }

    override suspend fun fetch(refresh: Boolean): StudentInfo = repo.fetch(userNum)

    companion object {
        fun factory(userNum: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { StudentDetailViewModel(userNum) }
        }
    }
}
