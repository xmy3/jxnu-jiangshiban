package cn.jxnu.nvzhuanban.data.network

import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 静默会话恢复的三态结果。业务请求命中 [JwcError.SessionExpired] 后据此决定去留：
 *  - [Success]：会话已刷新，调用方重放原请求。
 *  - [Transient]：网络/环境性失败（不知道凭证死没死）——**别踢用户**，让 UI 显示可重试错误，稍后自愈。
 *  - [AuthRejected]：没有可用凭证，或 CAS 明确拒绝（密码已改等）——踢到登录页让用户手动处理。
 */
enum class ReauthOutcome { Success, Transient, AuthRejected }

/**
 * 业务 Repository 在拿到 [JwcError.SessionExpired] 时调 [reauth]。
 * [ReauthOutcome.Success] → 重放原 HTTP 请求；[ReauthOutcome.AuthRejected] → 用
 * [SessionEvents.notifyExpired] 把用户踢回登录页；[ReauthOutcome.Transient] → 透传可重试错误、**不踢**。
 *
 * [reauthMutex] 把短时间内的并发 reauth 合并为一次。合并靠**进锁后先复验会话**（[AuthRepository]
 * 内部 double-check）：成绩 / 课表 / 通知 / 头像同时触发 SessionExpired 时，第一个真正跑 CAS 登录，
 * 其余进锁后发现会话已被刷新 → 直接 [ReauthOutcome.Success] 返回，不再各打一次 CAS。
 */
object SessionRecovery {
    private val reauthMutex = Mutex()

    /**
     * 三态静默重登。见 [ReauthOutcome]。
     *
     * 直接消费 Boolean 的历史调用方（如 ArticleDetailRepository）可用 [tryReauthSilently]，
     * 它把三态压成「Success→true，其余→false」的旧语义。
     */
    suspend fun reauth(): ReauthOutcome = reauthMutex.withLock {
        AuthRepository.instance.tryReauthSilently()
    }

    /**
     * 旧的 Boolean 便捷版：只关心「能不能重放」。[ReauthOutcome.Success] → true，其余 → false。
     * 注意 false 无法区分 Transient / AuthRejected，需要区分的调用方请改用 [reauth]。
     */
    suspend fun tryReauthSilently(): Boolean = reauth() == ReauthOutcome.Success
}
