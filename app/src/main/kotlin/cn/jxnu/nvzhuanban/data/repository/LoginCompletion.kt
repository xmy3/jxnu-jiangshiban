package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal suspend fun completeLogin(
    state: MutableStateFlow<AuthState>,
    loadProfile: suspend () -> UserProfile,
    scheduleAlerts: suspend () -> Unit,
) {
    val profile = loadProfile()
    currentCoroutineContext().ensureActive()
    // 发布 LoggedIn 会触发导航并取消登录页协程，已确认的会话仍须完成提醒调度。
    withContext(NonCancellable) {
        state.value = AuthState.LoggedIn(profile)
        scheduleAlerts()
    }
}
