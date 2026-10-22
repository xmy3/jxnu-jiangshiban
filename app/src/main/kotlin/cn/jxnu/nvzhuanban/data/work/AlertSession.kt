package cn.jxnu.nvzhuanban.data.work

import cn.jxnu.nvzhuanban.data.model.AuthState
import kotlinx.coroutines.Deferred
import kotlin.coroutines.cancellation.CancellationException

/** 启动恢复只是等待屏障；随后手动登录/退出时，后台检查必须服从当前身份。 */
internal suspend fun canRunAlertCheck(
    sessionRestore: Deferred<Boolean>,
    currentState: () -> AuthState,
): Boolean {
    try {
        sessionRestore.await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 启动恢复失败不代表用户此后无法手动登录。
    }
    return currentState() is AuthState.LoggedIn
}
