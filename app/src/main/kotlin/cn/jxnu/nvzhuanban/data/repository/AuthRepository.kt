package cn.jxnu.nvzhuanban.data.repository

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.model.hasPlaceholderName
import cn.jxnu.nvzhuanban.data.network.AuthStorage
import cn.jxnu.nvzhuanban.data.network.CasLoginClient
import cn.jxnu.nvzhuanban.data.network.JxnuHttpClient
import cn.jxnu.nvzhuanban.data.network.ReauthOutcome
import cn.jxnu.nvzhuanban.data.network.SecureCredentialStore
import cn.jxnu.nvzhuanban.data.network.pages.UserDefaultPage
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.AvatarPrefs
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import cn.jxnu.nvzhuanban.data.storage.PeopleSearchHistoryStore
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import cn.jxnu.nvzhuanban.ui.components.clearDecodedImageCache
import cn.jxnu.nvzhuanban.ui.widget.TodayScheduleWidget
import cn.jxnu.nvzhuanban.ui.widget.WidgetUpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 登录态仓库（Phase 3：免登录）。
 *
 * 状态恢复优先级（[tryRestoreSession]）：
 *  1. 仍有效的 cookie（probeSession=Valid）→ 直接 LoggedIn（快，~100ms，复用 probe 拿到的首页 HTML）
 *  2. cookie 失效（Invalid）但有保存的凭证 → 静默重登（SSO 续票 → 账密登录）→ LoggedIn
 *  3. 网络够不着（Unreachable）但有凭证 → **乐观放行**：降级 profile 直接 LoggedIn，课表走 widget
 *     快照离线兜底，姓名/学院信息进主界面后自愈；网络恢复后业务请求自然 reauth
 *  4. 无凭证 / CAS 明确拒绝 → LoggedOut / 登录页
 *
 * 会话恢复统一走 [reauthLadder]：先免密 SSO 续票（本地 TGC 有效时不打密码），失败再完整账密登录。
 * 只有账密这一步受 [autoLoginThrottle] 保护——高频同账号密码 POST 才会刺激 CAS 风控。
 *
 * 运行中的静默重登（[tryReauthSilently]）返回 [ReauthOutcome] 三态：Success 重放 / AuthRejected 踢登录页 /
 * Transient 不踢人稍后重试。并发请求同时过期时靠进锁 double-check 会话真正合并为一次登录。
 *
 * 凭证存储：用户勾"记住账号"时，密码会被 [SecureCredentialStore] 加密（Android Keystore 硬件密钥）
 * 持久化。仅本机解密，卸载即销毁。
 *
 * [authMutex] 把 [login] / [tryRestoreSession] / [logout] / [expireSession] / [tryReauthSilently]
 * 串行化：防止冷启动时用户手敲登录与 tryRestoreSession 并发执行 CAS 登录（两条 clearForHost + login
 * 会把 cookie jar 推到半填半清状态、_state 闪烁 LoggedIn → Error → LoggedIn）。
 */
class AuthRepository private constructor(
    private val appContext: Context,
    private val cas: CasLoginClient,
    private val storage: AuthStorage,
    private val creds: SecureCredentialStore,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val authMutex = Mutex()

    /**
     * 自动重登节流。手动登录不受影响（用户明确意图），仅自动通道（[tryRestoreSession] /
     * [tryReauthSilently]）受指数退避保护：避免本地保存的旧密码反复打 CAS 把账号锁死。
     *
     * 触发场景：用户在网页端改了密码 / 学校强制改密 / CAS 临时拒绝 → 旧凭证不再可用。
     * 第一次失败若判 [CasLoginClient.Result.InvalidCredentials]，凭证已被清掉，不会再有第二次。
     * 但 [CasLoginClient.Result.Transient]（网络抖动 / 5xx / 需验证码）凭证保留，下次冷启动
     * 还会重试 —— 这里的节流就是兜底这条路径。
     */
    private val autoLoginThrottle = AutoLoginThrottle()

    class LoginException(message: String) : RuntimeException(message)

    suspend fun login(username: String, password: String, captcha: String = "") = authMutex.withLock {
        _state.value = AuthState.Loading
        when (val r = cas.login(username.trim(), password, captcha.trim())) {
            is CasLoginClient.Result.Success -> {
                // 手动登录成功 → 重置自动通道节流，让后续冷启动 silent retry 立即可用
                autoLoginThrottle.recordSuccess()
                onLoginSuccess(username.trim(), password)
            }
            is CasLoginClient.Result.InvalidCredentials -> {
                // 手动登录失败不阻挡用户重试，但**也算进自动通道的失败计数**：
                // 否则用户在登录页连点 5 次错密码，下次冷启动 silent retry 仍会无脑试一次。
                autoLoginThrottle.recordFailure()
                _state.value = AuthState.Error(r.message)
                throw LoginException(r.message)
            }
            is CasLoginClient.Result.Transient -> {
                // 网络 / 环境性失败：同样计入失败计数（防连点风暴），但文案是「稍后重试」而非「密码错」。
                autoLoginThrottle.recordFailure()
                _state.value = AuthState.Error(r.message)
                throw LoginException(r.message)
            }
        }
    }

    /**
     * 登录页「使用已保存的密码登录」入口：从 [SecureCredentialStore] 载出密码，走**手动登录通道**
     * （[login]，驱动 _state=Loading/LoggedIn、不受 [autoLoginThrottle] 冷却阻挡、成功重置计数）。
     * 绝不走 [tryReauthSilently]——那是自动通道，受节流、不改 _state，用户点了可能「没反应」。
     *
     * 没有可用凭证（极少见：并发被清）时抛 [LoginException]，让 UI 提示改用手输。
     */
    suspend fun loginWithSavedCredentials() {
        val saved = creds.load() ?: throw LoginException("本机没有已保存的密码，请手动登录")
        login(saved.username, saved.password)
    }

    private suspend fun onLoginSuccess(username: String, password: String) {
        // 换号登录检测：上一个成功登录的用户和这次不同，说明设备易主/换账号，
        // 上一用户的本地派生数据（周次覆盖 / 已读锚点 / widget 快照）必须清掉。
        // 同一用户重登（会话过期后最常见的路径）则全部保留。
        val previousUser = storage.lastLoggedUser
        if (previousUser != null && previousUser != username) {
            // 清理链路含 SharedPreferences / widget 快照文件删除，切 IO 防主线程卡顿
            withContext(Dispatchers.IO) { clearAllUserDataOnSignOut() }
            // 「显示学生头像」是带隐私语义的开关（默认 false）：换号后不让新用户继承上一用户
            // 的授权、静默发起头像请求，由新用户自己重新勾选。同账号重登不受影响。
            AvatarPrefs.setShowAvatar(false)
        }
        storage.lastLoggedUser = username
        // 凭证落盘是 Tink 加密 + fsync 的同步写（save 用 commit 才能拿到成败位），必须切 IO，
        // 否则手动登录这一下会在主线程（viewModelScope 默认 Main）卡顿，低端机上是 ANR 隐患。
        withContext(Dispatchers.IO) {
            if (storage.rememberMe) {
                val saved = creds.save(username, password)
                if (saved) {
                    storage.lastUsername = username
                } else if (creds.isPermanentlyBroken()) {
                    // EncryptedSharedPreferences 确定性损坏且修复阶梯也救不回（厂商魔改 ROM /
                    // keystore 彻底坏 / 设备空间满）：诚实关掉自动登录——承诺"记住"却永远记不住
                    // 更糟。一次性警告塞到 lastLoginWarning，由登录页 ViewModel 取走显示。
                    storage.rememberMe = false
                    storage.lastUsername = null
                    creds.clear()
                    lastLoginWarning = "本机无法安全保存密码，已关闭自动登录"
                } else {
                    // 瞬时 Keystore 故障（开机早期未就绪 / 系统繁忙）：密码这次没存上，但**不**关
                    // rememberMe——一次抖动就把开关永久关掉，正是「瞬时当永久」要消灭的形态。
                    // 下次登录（或本次会话内的再次保存）自然重试。
                    lastLoginWarning = "本机安全存储暂时不可用，本次密码未能保存"
                }
            } else {
                creds.clear()
            }
        }
        WidgetSnapshotStore.resumeWrites()
        val profile = runCatching { UserDefaultPage.fetchAndParse(username) }
            .getOrNull()
            ?: UserDefaultPage.parse(username, "")
        _state.value = AuthState.LoggedIn(profile)
    }

    /**
     * 一次性的登录侧警告（不是 Error —— 登录本身成功了，只是有衍生异常需要告诉用户）。
     * [LoginViewModel] 在 submit 成功后调 [consumeLastLoginWarning] 取走并清零。
     */
    @Volatile private var lastLoginWarning: String? = null

    fun consumeLastLoginWarning(): String? {
        val w = lastLoginWarning
        lastLoginWarning = null
        return w
    }

    /**
     * 启动时尝试恢复登录态。返回 true 表示已是 LoggedIn，登录页可被跳过。
     *
     * 调用方式（NvzhuanbanApp）：异步发起，UI 在 splash 屏等结果。
     */
    suspend fun tryRestoreSession(): Boolean = authMutex.withLock {
        // 优先走 cookie，快。三态探测区分「有效 / 确证失效 / 网络够不着」。
        when (val probe = cas.probeSession()) {
            is CasLoginClient.SessionProbe.Valid -> {
                // probe 已经把 /User/Default.aspx 的 HTML 取回来了，直接解析，省掉紧接着的第二次 GET。
                return@withLock finishRestoreFromValidHtml(probe.html)
            }
            CasLoginClient.SessionProbe.Unreachable -> {
                // 网络够不着但本地有可用凭证 → **乐观放行**：先进主界面（课表走 widget 快照离线兜底、
                // ProfileViewModel 姓名自愈），后台会话稍后由业务请求自然触发 reauth。
                // 只认真正保存的密码（creds），绝不用 lastUsername/lastLoggedUser——那会把「已登出但网络差」
                // 的用户静默登回去。乐观放行**不写 lastLoggedUser**（没发生过验证登录，无权动换号锚点）。
                val saved = creds.load() ?: return@withLock false
                _state.value = AuthState.LoggedIn(degradedProfile(saved.username))
                return@withLock true
            }
            CasLoginClient.SessionProbe.Invalid -> Unit // 落到下面的凭证重登
        }
        // cookie 确证失效，尝试用保存的凭证静默重登
        val saved = creds.load() ?: return@withLock false
        // 自动通道节流：上次失败后还在冷却窗口内则跳过，避免旧密码反复打 CAS。
        // 冷启动 throttle 已随进程重置，所以正常冷启动不会被拦；用户可手动登录（不受 throttle 限制）。
        if (autoLoginThrottle.shouldSkip()) return@withLock false
        when (reauthLadder(saved.username, saved.password)) {
            CasLoginClient.Result.Success -> {
                autoLoginThrottle.recordSuccess()
                onLoginSuccess(saved.username, saved.password)
                true
            }
            is CasLoginClient.Result.InvalidCredentials -> {
                // CAS 明确拒绝（密码已改、账号冻结等）→ 清凭证，避免下次再用过期密码反复试。
                autoLoginThrottle.recordFailure()
                creds.clear()
                false
            }
            is CasLoginClient.Result.Transient -> {
                // 瞬时失败（网络/环境）：保留凭证。有凭证 → 同样乐观放行（离线兜底），别把只是没网的用户
                // 挡在登录页手输密码。降级 profile 不写 lastLoggedUser。
                // 刻意**不** recordFailure：冷启动首败多为网络慢热，此刻预支 30s 冷却会把进主界面后
                // 网络恢复的第一波业务 reauth 全部拦成「网络错误」（用户视角：明明有网却报无网）。
                // 风暴防护不受影响——高频重试真正来自业务请求触发的 tryReauthSilently，那条路自己计数。
                _state.value = AuthState.LoggedIn(degradedProfile(saved.username))
                true
            }
        }
    }

    /** [CasLoginClient.SessionProbe.Valid] 分支：用 probe 拿到的首页 HTML 完成恢复，不再重复 GET。 */
    private suspend fun finishRestoreFromValidHtml(html: String): Boolean {
        // 即使本地没保存学号（rememberMe=false 的老用户），也能从 lblUserInfor 把学号 + 姓名解出来。
        val username = storage.lastUsername ?: creds.load()?.username ?: ""
        val profile = UserDefaultPage.parse(username, html)
        // 解析失败（极端：HTML 结构变了）连学号都没有 → 视为未登录，强制重登
        if (profile.studentId.isBlank()) return false
        // cookie 直连路径同样要记录/比对「上一个登录用户」（存量靠 cookie 续命的用户 lastLoggedUser
        // 会一直是 null，换号检测失效）。JXNU 的 CAS 登录名就是学号，与 onLoginSuccess 同口径。
        val previousUser = storage.lastLoggedUser
        if (previousUser != null && previousUser != profile.studentId) {
            withContext(Dispatchers.IO) { clearAllUserDataOnSignOut() }
            // 同 onLoginSuccess：换号后头像开关回到默认关，不继承上一用户的授权。
            AvatarPrefs.setShowAvatar(false)
        }
        storage.lastLoggedUser = profile.studentId
        // cookie 直接有效本身就是"自动通道成功"，重置 throttle
        autoLoginThrottle.recordSuccess()
        _state.value = AuthState.LoggedIn(profile)
        return true
    }

    /** 乐观放行 / 瞬时失败时的降级 profile：只有学号（从本地凭证），姓名占位，进主界面后自愈。 */
    private fun degradedProfile(username: String): UserProfile = UserDefaultPage.parse(username, "")

    suspend fun logout() = authMutex.withLock {
        // 与 onLoginSuccess 的换号清理同款切 IO：cas.logout 删 cookie 文件、清理链删 widget
        // 快照文件，都是磁盘操作；调用方 ProfileViewModel 的 NonCancellable 只换 Job 不换
        // 调度器，不包 IO 这些就全落在主线程。
        withContext(Dispatchers.IO) {
            cas.logout()
            creds.clear()
            clearAllUserDataOnSignOut()
        }
        _state.value = AuthState.LoggedOut
    }

    suspend fun expireSession(message: String = "登录已过期，请重新登录") = authMutex.withLock {
        // 误踢守卫：广播 notifyExpired 到 AppNav 消费之间，可能有另一路并发请求刚好把会话 reauth 成功了。
        // 真去清 cookie / 踢登录页之前，先复验一次——会话其实还活着就直接放行（保住刚建立的新会话），
        // 只在确证失效时才销毁。这挡住了「弱网下一个请求失败、把兄弟请求刚续上的会话连带清掉」的竞态。
        if (cas.probeSession() is CasLoginClient.SessionProbe.Valid) return@withLock
        cas.logout()
        // 只清内存缓存（会话相关、可重新拉取），**不**清 CourseOverridesStore / 已读锚点 /
        // widget 快照这类同账号的本地数据 —— 会话过期是可自愈事件，预期同一用户马上重登
        // （凭证也特意保留了），不应把它当成主动登出销毁用户手工维护的数据。
        // 换号场景的清理由 onLoginSuccess 的换号检测兜底。
        clearRepositoryCaches()
        _state.value = AuthState.Error(message)
    }

    /**
     * 用本地保存的凭据**静默**重登，返回 [ReauthOutcome] 三态。不修改 [_state]（用户视角看不到 Loading），
     * 供业务请求做"过期 → 自动恢复"重放：
     *  - [ReauthOutcome.Success]：会话已刷新（cookie double-check 命中，或 SSO 续票 / 账密登录成功）→ 重放。
     *  - [ReauthOutcome.AuthRejected]：没保存凭据，或 CAS 明确拒绝（密码已改等，已清凭据）→ 踢登录页。
     *  - [ReauthOutcome.Transient]：网络/环境性失败 or 被节流跳过 → 不踢人、稍后重试。
     *
     * **并发合并**靠进锁后先 double-check 会话：成绩/课表/通知/头像同时过期时，第一个真跑 [reauthLadder]，
     * 其余进锁（[SessionRecovery.reauthMutex]）后 probeSession 已 Valid → 直接 Success 返回，不再各打一次 CAS。
     *
     * 与 [login] / [tryRestoreSession] / [logout] 共用 [authMutex]。
     */
    suspend fun tryReauthSilently(): ReauthOutcome = authMutex.withLock {
        // 并发合并：若别的 reauth 已经把会话续上了，直接成功返回，不再重复登录。
        // （SessionRecovery.reauthMutex 已把并发 reauth 串行化，这里是串行队列里每个人的进锁复验。）
        if (cas.probeSession() is CasLoginClient.SessionProbe.Valid) return@withLock ReauthOutcome.Success
        val saved = creds.load() ?: return@withLock ReauthOutcome.AuthRejected
        // 自动通道节流：冷却窗口内不再打 CAS。归 Transient（不踢人）而非 AuthRejected——
        // 凭证多半仍有效，只是此刻不宜重试，等冷却过或用户手动登录即可恢复。
        if (autoLoginThrottle.shouldSkip()) return@withLock ReauthOutcome.Transient
        when (reauthLadder(saved.username, saved.password)) {
            CasLoginClient.Result.Success -> {
                autoLoginThrottle.recordSuccess()
                ReauthOutcome.Success
            }
            is CasLoginClient.Result.InvalidCredentials -> {
                autoLoginThrottle.recordFailure()
                creds.clear()
                ReauthOutcome.AuthRejected
            }
            is CasLoginClient.Result.Transient -> {
                autoLoginThrottle.recordFailure()
                ReauthOutcome.Transient
            }
        }
    }

    /**
     * 会话恢复的阶梯：先试免密 SSO 续票（本地 TGC 仍有效时秒续、不打密码、不刺激 CAS 风控），
     * 失败再退回完整账密登录。**只有账密这一步**会真正提交密码、才受风控影响，SSO 续票不算。
     *
     * 必须在持有 [authMutex] 时调用；两步都走 raw 路径（[CasLoginClient.tryRefreshViaSso] /
     * [CasLoginClient.login] 内部均不经 *Auth），不会重入 [SessionRecovery]。
     */
    private suspend fun reauthLadder(username: String, password: String): CasLoginClient.Result {
        if (cas.tryRefreshViaSso()) return CasLoginClient.Result.Success
        return cas.login(username, password)
    }

    /**
     * 重新拉一次用户首页，刷新基础 profile（姓名 / 学号 / 年级 / 头像 URL）。
     *
     * 触发场景：首次登录或会话恢复时弱网拉首页失败 → [onLoginSuccess] / [tryRestoreSession]
     * 回退到 `parse(username, "")` 空 HTML → 姓名退化成占位（学号仍来自传入参数），且这条
     * 降级 profile 被钉进 [_state] 后整个会话不再刷新。[cn.jxnu.nvzhuanban.ui.screens.profile.ProfileViewModel]
     * 进页面时若发现姓名仍是占位，调本方法在网络恢复后自愈，不必等用户重启 / 重登。
     *
     * 仅当前为 [AuthState.LoggedIn] 时有效。成功且姓名非占位 → 写回 [_state] 并返回新 profile；
     * 失败 / 仍占位 → 返回 null，保留原 profile（不拿更差的数据覆盖已有的）。
     *
     * 走 [UserDefaultPage.fetchAndParse]（内部 raw getHtml，不触发 reauth），所以持有
     * [authMutex] 期间不会经 [cn.jxnu.nvzhuanban.data.network.SessionRecovery] 重入本类锁。
     */
    suspend fun refreshProfile(): UserProfile? = authMutex.withLock {
        val current = (_state.value as? AuthState.LoggedIn)?.profile ?: return@withLock null
        val refreshed = runCatching { UserDefaultPage.fetchAndParse(current.studentId) }
            .getOrNull()
            ?: return@withLock null
        if (refreshed.hasPlaceholderName) return@withLock null
        _state.value = AuthState.LoggedIn(refreshed)
        refreshed
    }

    /**
     * 把所有"上一用户"留下的派生数据清空：
     *  - 全部业务 Repository 单例的内存缓存（见 [clearRepositoryCaches]，新增 repo 记得挂进去）
     *  - 课程周次本地覆盖（CourseOverridesStore）
     *  - 通知已读锚点（AnnouncementReadAnchor）—— 否则下一用户继承上一用户的"最后已读"
     *  - 桌面 widget snapshot 文件 —— 否则锁屏小部件还在显示上一用户的今日课表
     *  - 图片缓存（内存解码位图 + OkHttp 磁盘缓存）—— 否则上一用户的头像 / 师生照片跨账号残留
     *
     * 注意：调用方已持有 [authMutex]，这里串行执行即可，不会和并发的 login 抢锁。
     */
    private suspend fun clearAllUserDataOnSignOut() {
        clearRepositoryCaches()
        CourseOverridesStore.clearAll()
        PeopleSearchHistoryStore.clearAll()
        AnnouncementReadAnchor.clear()
        clearWidgetSnapshotOnSignOut()
        clearDecodedImageCache()
        JxnuHttpClient.get().clearHttpCache()
    }

    /** 仅清各 Repository 单例的内存缓存 —— 会话过期时用（数据可在重登后重新拉取）。 */
    private suspend fun clearRepositoryCaches() {
        ScheduleRepository.instance.clearCache()
        GradeRepository.instance.clearCache()
        TestGradeRepository.instance.clearCache()
        ExamRepository.instance.clearCache()
        MakeupExamRepository.instance.clearCache()
        GraduationAuditRepository.instance.clearCache()
        AnnouncementRepository.instance.clearCache()
        ArticleDetailRepository.instance.clearCache()
        TeacherRepository.instance.clearCache()
        TeacherDetailRepository.instance.clearCache()
        StudentRepository.instance.clearCache()
        StudentDetailRepository.instance.clearCache()
        CourseOfferingRepository.instance.clearCache()
        StudentInfoCheckRepository.instance.clearCache()
    }

    private suspend fun clearWidgetSnapshotOnSignOut() {
        WidgetSnapshotStore.clear(appContext)
        WidgetUpdateScheduler.cancel(appContext)
        runCatching {
            val mgr = GlanceAppWidgetManager(appContext)
            if (mgr.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                TodayScheduleWidget().updateAll(appContext)
            }
        }
    }

    /** 给登录页预填学号用。 */
    fun rememberedUsername(): String? = storage.lastUsername.takeIf { storage.rememberMe && creds.isAvailable() }

    /** 给登录页初始化"下次自动登录" checkbox 状态用，默认 true。 */
    fun isRememberMeOn(): Boolean = storage.rememberMe && creds.isAvailable()

    /** 登录页判断是否能展示「使用已保存的密码登录」入口。 */
    fun hasSavedCredentials(): Boolean = storage.rememberMe && creds.hasCredentials()

    fun updateRememberMe(enabled: Boolean): Boolean {
        val actual = enabled && creds.isAvailable()
        storage.rememberMe = actual
        if (!actual) {
            storage.lastUsername = null
            creds.clear()
        }
        return actual
    }

    companion object {
        @Volatile private var INSTANCE: AuthRepository? = null

        fun init(context: Context): AuthRepository {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: run {
                    val app = context.applicationContext
                    JxnuHttpClient.init(app)
                    val storage = AuthStorage.init(app)
                    val creds = SecureCredentialStore.init(app)
                    val cas = CasLoginClient(app)
                    AuthRepository(app, cas, storage, creds).also { INSTANCE = it }
                }
            }
        }

        val instance: AuthRepository
            get() = INSTANCE
                ?: error("AuthRepository 未初始化，请在 NvzhuanbanApp.onCreate 调用 AuthRepository.init(this)")
    }
}

/**
 * 自动登录通道的失败节流。仅 [AuthRepository.tryRestoreSession] / [AuthRepository.tryReauthSilently]
 * 调用，**手动 [AuthRepository.login] 不受冷却阻挡**（用户明确意图）但会贡献到失败计数。
 *
 * 退避表（连续失败次数 → 冷却时间）：
 *  - 1 次：30 秒
 *  - 2 次：2 分钟
 *  - 3 次：5 分钟
 *  - 4 次：15 分钟
 *  - ≥5 次：30 分钟
 *
 * 任何一次成功（cookie 自验有效 / CAS login 成功）清零计数。计数仅活在进程里——
 * App 被杀重启即重置，避免持久化逻辑引来更难调的状态。冷启动场景下：第一次冷启动失败 →
 * 进程退出 → 第二次冷启动会再试一次（不受节流阻挡）。这是有意为之：用户手动重启 App
 * 视为"用户已知问题、希望重试"，比墨守 30 分钟冷却更合用户预期。
 *
 * 真正要拦的是**单进程内**的高频自动尝试：业务请求触发的 SessionExpired → reauth 死循环。
 */
private class AutoLoginThrottle {
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var consecutiveFailures: Int = 0

    fun shouldSkip(): Boolean {
        val n = consecutiveFailures
        if (n == 0) return false
        val elapsed = System.currentTimeMillis() - lastFailureAt
        return elapsed < cooldownMs(n)
    }

    fun recordFailure() {
        lastFailureAt = System.currentTimeMillis()
        // 限上界，避免 32 次失败后变成天文数字（虽然 cooldownMs 已经 capped）
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_TRACKED)
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        lastFailureAt = 0L
    }

    private fun cooldownMs(n: Int): Long = when (n) {
        0 -> 0L
        1 -> 30_000L
        2 -> 2 * 60_000L
        3 -> 5 * 60_000L
        4 -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    companion object {
        private const val MAX_TRACKED = 8
    }
}
