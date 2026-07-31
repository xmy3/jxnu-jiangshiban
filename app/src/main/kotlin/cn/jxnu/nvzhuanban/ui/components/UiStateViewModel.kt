package cn.jxnu.nvzhuanban.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * load/refresh + [UiState] 的通用模板，子类只实现 [fetch]。
 *
 *  - [load]：整页置 Loading 后拉数据，失败置 Error（[ErrorState] 的 onRetry 也走这里）
 *  - [refresh]：带防重入；**失败保留旧数据**、复位转圈并向 [refreshFailed] 发一条一次性
 *    提示（Screen 用 SnackbarHost 展示）——旧数据不被顶掉是全 app 下拉刷新的统一约定，
 *    子类不要绕开 refresh 自己写一份；失败也不能完全静默：转圈停了会被误读成
 *    "刷新完成、显示的就是最新数据"（出分季在成绩页尤其致命）。
 *
 * 基类不在 init 里自动 load（那时子类的 ctor 属性还没初始化）：需要进页即加载的子类
 * 自己写 `init { load() }`，懒加载的（如 TestGradesViewModel）由 UI 显式触发。
 */
abstract class UiStateViewModel<T> : ViewModel() {

    protected val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 刷新失败（旧数据仍在展示）的一次性提示，配合 [rememberTransientErrorSnackbar] 使用。 */
    private val _refreshFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshFailed: SharedFlow<String> = _refreshFailed.asSharedFlow()

    /** 拉取整页数据；refresh=true 时应 bypass Repository 缓存。 */
    protected abstract suspend fun fetch(refresh: Boolean): T

    open fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetch(refresh = false))
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetch(refresh = true))
            } catch (t: Throwable) {
                // Keep the previous state visible; retry from the error state is handled by load().
                _refreshFailed.tryEmit(t.toUserMessage("刷新失败"))
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
