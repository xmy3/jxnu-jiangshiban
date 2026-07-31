package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/**
 * 订阅一条（或几条）一次性错误消息流并转成 [SnackbarHostState]，Screen 挂进自己
 * Scaffold 的 `snackbarHost = { SnackbarHost(it) }` 即可。
 *
 * 典型来源：[UiStateViewModel.refreshFailed]（刷新失败但旧数据仍在展示）、
 * ScheduleViewModel.transientError（切学期失败）。这类失败**不**顶掉正在展示的内容，
 * 只弹提示——与整页 [ErrorState]（无数据可看，给重试按钮）分工明确。
 */
@Composable
fun rememberTransientErrorSnackbar(vararg messageFlows: Flow<String>): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    for (flow in messageFlows) {
        LaunchedEffect(flow) {
            flow.collect { hostState.showSnackbar(it) }
        }
    }
    return hostState
}
