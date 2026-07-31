package cn.jxnu.nvzhuanban.ui.screens.courseoffering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.CourseOfferingForm
import cn.jxnu.nvzhuanban.data.model.CourseOfferingQuery
import cn.jxnu.nvzhuanban.data.model.CourseOfferingTable
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.CourseOfferingRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 查询区状态。表单（[CourseOfferingViewModel.formState]）与结果互相独立。 */
sealed interface CourseOfferingResultState {
    /** 还没点过查询。 */
    data object Initial : CourseOfferingResultState
    data object Loading : CourseOfferingResultState
    data class Success(val table: CourseOfferingTable) : CourseOfferingResultState
    data class Error(val message: String) : CourseOfferingResultState
}

/**
 * 开课查询 ViewModel。
 *
 * 两层状态：
 *  - [formState]：筛选表单（学期 / 学院下拉选项），进屏即拉，失败可整页重试；
 *  - [resultState]：查询结果，由用户点「查询」驱动，Loading / Error 只影响结果区。
 *
 * 不继承 [cn.jxnu.nvzhuanban.ui.components.UiStateViewModel]——那是 load/refresh 单数据源
 * 模板，本页是「表单 + 交互式查询」双层状态，模式与 PeopleSearchViewModel 一致。
 */
class CourseOfferingViewModel(
    private val repo: CourseOfferingRepository = CourseOfferingRepository.instance,
) : ViewModel() {

    private val _formState = MutableStateFlow<UiState<CourseOfferingForm>>(UiState.Loading)
    val formState: StateFlow<UiState<CourseOfferingForm>> = _formState.asStateFlow()

    private val _resultState = MutableStateFlow<CourseOfferingResultState>(CourseOfferingResultState.Initial)
    val resultState: StateFlow<CourseOfferingResultState> = _resultState.asStateFlow()

    private var lastQuery: CourseOfferingQuery? = null

    init {
        loadForm()
    }

    /** 拉取筛选表单（下拉选项 + 三件套 donor）。失败走整页 Error，重试也走这里。 */
    fun loadForm() {
        _formState.value = UiState.Loading
        viewModelScope.launch {
            _formState.value = try {
                UiState.Success(repo.fetchForm())
            } catch (t: Throwable) {
                UiState.Error(t.toUserMessage())
            }
        }
    }

    fun search(query: CourseOfferingQuery) {
        if (_resultState.value is CourseOfferingResultState.Loading) return
        lastQuery = query
        _resultState.value = CourseOfferingResultState.Loading
        viewModelScope.launch {
            _resultState.value = runCatching {
                CourseOfferingResultState.Success(repo.search(query)) as CourseOfferingResultState
            }.getOrElse { t ->
                CourseOfferingResultState.Error(t.toUserMessage())
            }
        }
    }

    /** 结果区错误态的「重试」：复用上次查询入参。 */
    fun retrySearch() {
        lastQuery?.let { search(it) }
    }
}
