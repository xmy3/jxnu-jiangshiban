package cn.jxnu.nvzhuanban.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.model.hasPlaceholderName
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.network.toUpdateMessage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.UpdateRepository
import cn.jxnu.nvzhuanban.data.storage.UpdatePrefs
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileData(
    val user: UserProfile,
    val enrichStatus: EnrichStatus = EnrichStatus.Idle,
)

/**
 * 学院/专业/班级等"成绩页补充信息"的拉取状态，驱动 UserCard 的占位文案：
 * [Loading] → "学院信息加载中…"；[Failed] → "加载失败，点击重试"；[Idle] → 已拿到或无需拉取。
 * 没有这个状态时，拉取失败只能永久停在"加载中…"（旧 bug）。
 */
enum class EnrichStatus { Idle, Loading, Failed }

/**
 * 用户主动「检查更新」点击的一次性事件，UI 用 `LaunchedEffect` 收并 Snackbar 呈现。
 * 区分 [Newer] 和 [Latest] 让 UI 决定是弹 Dialog 还是 Snackbar 报"已是最新版本"。
 */
sealed interface UpdateCheckResult {
    data object Checking : UpdateCheckResult
    data object Latest : UpdateCheckResult
    data class Newer(val release: AppRelease) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    // 不放进主构造器：Kotlin 默认参数不会生成 `(Application)` 单参重载，
    // SavedStateViewModelFactory 通过反射找 AndroidViewModel 标准构造器找不到就闪退。
    // 这个坑 ScheduleViewModel 已经踩过一次，参见那边的相同注释。
    private val authRepo: AuthRepository = AuthRepository.instance
    private val gradeRepo: GradeRepository = GradeRepository.instance
    private val updateRepo: UpdateRepository = UpdateRepository.instance

    private val _state = MutableStateFlow<UiState<ProfileData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileData>> = _state.asStateFlow()

    /**
     * 直接转发 [UpdateRepository.latestRelease] —— App 启动时 [NvzhuanbanApp] 已经跑过
     * 一次 [UpdateRepository.checkIfDue]，VM 不在 init 里重复触发。
     */
    val latestRelease: StateFlow<AppRelease?> = updateRepo.latestRelease

    private val _checkResult = MutableSharedFlow<UpdateCheckResult>(extraBufferCapacity = 1)
    val checkResult: SharedFlow<UpdateCheckResult> = _checkResult.asSharedFlow()

    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut: StateFlow<Boolean> = _isLoggingOut.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val base = (authRepo.state.value as? AuthState.LoggedIn)?.profile
                if (base == null) {
                    _state.value = UiState.Error("未登录")
                    return@launch
                }
                val needsEnrich = base.needsGradeEnrichment()
                // 先用现有（可能是降级的）profile 渲染；学院信息待 enrich 时先置 Loading 避免文案闪烁
                _state.value = UiState.Success(
                    ProfileData(base, if (needsEnrich) EnrichStatus.Loading else EnrichStatus.Idle),
                )
                // 姓名自愈：弱网登录恢复时首页拉取失败会把姓名降级成占位并钉进 AuthState。
                // 这里在网络恢复后重取一次首页修正，成功就替换掉占位 profile（学院信息随后由 enrich 补）。
                if (base.hasPlaceholderName) {
                    authRepo.refreshProfile()?.let { refreshed -> updateUser { refreshed } }
                }
                if (needsEnrich) enrichFromGrades()
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    /**
     * TopAppBar 手动刷新：重取首页姓名 + 强制重取成绩页学院/学分。区别于 [load] 的自愈路径，
     * 这里不看 needsGradeEnrichment 门控（成功过也允许刷），成绩页走 [GradeRepository.refresh]
     * bypass 内存缓存，保证学期初学分变动能拉到新值。
     */
    fun refresh() {
        if (_isRefreshing.value) return
        if (_state.value !is UiState.Success) {
            load()
            return
        }
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                // 首页只提供姓名/学号，学院/学分等 enrich 字段保留旧值，随后由强制 enrich 覆盖，
                // 避免刷新瞬间已展示的学院信息退回"加载中"占位。
                authRepo.refreshProfile()?.let { refreshed ->
                    updateUser { current ->
                        refreshed.copy(
                            college = current.college,
                            major = current.major,
                            className = current.className,
                            cumulativeCredits = current.cumulativeCredits,
                            graduationMinimumCredits = current.graduationMinimumCredits,
                        )
                    }
                }
                enrichNow(force = true)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 从成绩页拉学院/专业/班级/已修学分补全 profile。无参——读当前 [_state] 里的 user 作基底，
     * 所以 [retryEnrich] 能直接复用。失败不再静默吞掉，而是落 [EnrichStatus.Failed] 让 UI 给重试入口。
     */
    private fun enrichFromGrades() {
        viewModelScope.launch { enrichNow(force = false) }
    }

    /** [force] = true 时经 [GradeRepository.refresh] 绕过缓存重连成绩页。 */
    private suspend fun enrichNow(force: Boolean) {
        setEnrichStatus(EnrichStatus.Loading)
        val meta: GradePage.StudentMeta? = runCatching {
            (if (force) gradeRepo.refresh() else gradeRepo.fetchAll()).meta
        }.getOrNull()
        if (meta == null) {
            setEnrichStatus(EnrichStatus.Failed)
            return
        }
        updateUser { current ->
            current.copy(
                college = meta.college?.takeIf { it.isNotBlank() } ?: current.college,
                major = meta.major?.takeIf { it.isNotBlank() } ?: current.major,
                className = meta.className?.takeIf { it.isNotBlank() } ?: current.className,
                // 姓名仍是占位时用成绩页 meta 的姓名回填——修复旧 `ifBlank` 对"同学"判 false
                // 导致真实姓名无法覆盖占位的坑。已自愈（非占位）则不动。
                name = if (current.hasPlaceholderName) {
                    meta.name?.takeIf { it.isNotBlank() } ?: current.name
                } else current.name,
                studentId = current.studentId.ifBlank { meta.studentId.orEmpty().ifBlank { current.studentId } },
                cumulativeCredits = meta.totalCredit ?: current.cumulativeCredits,
            )
        }
        setEnrichStatus(EnrichStatus.Idle)
    }

    /** UserCard「加载失败，点击重试」回调：重新打成绩页（上次失败未写缓存，这次会真重连）。 */
    fun retryEnrich() {
        if (_state.value !is UiState.Success) return
        enrichFromGrades()
    }

    private fun updateUser(transform: (UserProfile) -> UserProfile) {
        val cur = _state.value as? UiState.Success ?: return
        _state.value = UiState.Success(cur.data.copy(user = transform(cur.data.user)))
    }

    private fun setEnrichStatus(status: EnrichStatus) {
        val cur = _state.value as? UiState.Success ?: return
        _state.value = UiState.Success(cur.data.copy(enrichStatus = status))
    }

    fun logout(onLoggedOut: () -> Unit) {
        // logout 现在是 suspend：需要按顺序清掉全部 Repository 缓存 + 课程覆盖 + 通知锚点 + widget snapshot 等
        if (_isLoggingOut.value) return
        _isLoggingOut.value = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    authRepo.logout()
                }
                onLoggedOut()
            } finally {
                _isLoggingOut.value = false
            }
        }
    }

    /**
     * 用户主动点「检查更新」时调用。绕过 24h 限频，结果通过 [checkResult] 一次性事件
     * 推回 UI。失败有 message，UI 显示 Snackbar；拿到更新版本则发 [UpdateCheckResult.Newer]
     * 让 UI 弹 Dialog；已是最新发 [UpdateCheckResult.Latest] 让 UI Snackbar 提示。
     */
    fun forceCheck() {
        viewModelScope.launch {
            _checkResult.emit(UpdateCheckResult.Checking)
            val versionName = currentVersionName()
            runCatching { updateRepo.forceCheck(versionName) }.fold(
                onSuccess = { release ->
                    _checkResult.emit(
                        if (release != null) UpdateCheckResult.Newer(release)
                        else UpdateCheckResult.Latest,
                    )
                },
                onFailure = { t ->
                    _checkResult.emit(UpdateCheckResult.Failed(t.toUpdateMessage()))
                },
            )
        }
    }

    /**
     * 标记某个版本"不再提醒"。仅影响静默检查路径的判断（再点 SettingsRow 仍能 forceCheck
     * 重新看到 Dialog，相当于自带"取消跳过"入口）。
     */
    fun skipVersion(tag: String) {
        UpdatePrefs.setSkippedVersion(tag)
    }

    private fun currentVersionName(): String = runCatching {
        val ctx = getApplication<Application>()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    private fun UserProfile.needsGradeEnrichment(): Boolean =
        college.isBlank() || major.isBlank() || className.isBlank() || cumulativeCredits <= 0f
}
