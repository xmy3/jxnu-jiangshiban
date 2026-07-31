package cn.jxnu.nvzhuanban.ui.screens.userschedule

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.UserScheduleRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.components.UiStateViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserScheduleViewModel(
    val scheduleUrl: String,
    private val repo: UserScheduleRepository = UserScheduleRepository(scheduleUrl),
) : UiStateViewModel<SchedulePage.Parsed>() {

    /** 切学期进行中（缓存命中会在同一帧内复位，进度条不可见）。 */
    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    /** 切学期失败的一次性提示，Screen 收到后 Toast 展示。 */
    private val _switchError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val switchError: SharedFlow<String> = _switchError.asSharedFlow()

    init { load() }

    override suspend fun fetch(refresh: Boolean): SchedulePage.Parsed = repo.fetch()

    /** 用户点学期 chip：本地缓存命中则秒切，没命中走一次 POST。 */
    fun selectSemester(value: String) {
        if (_isSwitching.value) return
        viewModelScope.launch {
            _isSwitching.value = true
            try {
                val parsed = repo.selectSemester(value)
                _state.value = UiState.Success(parsed)
            } catch (t: Throwable) {
                // 失败保留旧学期的 Success 不整页置 Error，只发一次性提示
                _switchError.tryEmit(t.toUserMessage())
            } finally {
                _isSwitching.value = false
            }
        }
    }

    companion object {
        fun factory(scheduleUrl: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { UserScheduleViewModel(scheduleUrl) }
        }
    }
}
