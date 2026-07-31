package cn.jxnu.nvzhuanban.ui.screens.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.repository.ScheduleRepository
import cn.jxnu.nvzhuanban.data.widget.ScheduleSnapshot
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.widget.TodayScheduleWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 假期语境下（今天不在正在查看的学期的任何教学周内）的横幅信息。
 *
 * @param semesterEnded true = 正在看的是**已结束的本学期**（寒暑假中打开课表的默认态）；
 *   false = 正在看的是一个**尚未开学**的学期（假期里预览下学期，或学期中翻看未来学期）。
 * @param nextStartDate 开学日 = 第 1 周周一（名义日期经 [SemesterPhase.weekOneMonday] 对齐，
 *   即真实校历的上课首日，如 2026 秋名义 9/1 周二 → 8/31）。semesterEnded 时是下一个学期的
 *   开学日（教务还没放出下学期选项时为 null）；!semesterEnded 时是正在看的学期自己的开学日。
 * @param nextSemesterValue 下学期 option value，供横幅上「看下学期」一键切换；
 *   仅 semesterEnded 且教务已放出下学期时非空。
 */
data class VacationInfo(
    val semesterEnded: Boolean,
    val nextStartDate: LocalDate?,
    val nextSemesterValue: String? = null,
)

data class ScheduleScreenState(
    val selectedWeek: Int,
    val totalWeeks: Int,
    val semester: String,
    val data: UiState<List<Course>> = UiState.Loading,
    /** 所有可选学期。第一次加载完成前是空列表，UI 隐藏切换入口。 */
    val semesters: List<SchedulePage.SemesterOption> = emptyList(),
    /** 当前选中的学期 [SchedulePage.SemesterOption.value]；null 表示尚未确定。 */
    val selectedSemesterValue: String? = null,
    /**
     * 当前学期开学日的开始日期（来自服务器 option value）。WeekdayHeader 用它把
     * `(week, weekday)` 算成具体日期。null 表示尚未加载或服务器没给。
     */
    val semesterStart: LocalDate? = null,
    /**
     * true = 当前展示的是本地磁盘缓存（上次成功看到的课表），因为这次拉取网络失败。
     * UI 顶部据此显示"离线·上次课表"提示；下次成功拉取后复位 false。
     */
    val isOffline: Boolean = false,
    /**
     * 本学期由开学日期算出的"当前教学周"；查看历史/未来学期，或**本学期已结束（假期）**
     * 时为 null（此时没有"本周/今天"概念，UI 不高亮「今」列、不标「本周」chip）。
     * 放在 state 里让 UI 可观察 —— refresh/loadWeek 推进它时列头「今」与周 chip「本周」要跟着走。
     */
    val currentWeek: Int? = null,
    /** 非 null = 假期语境，UI 显示假期横幅（本学期已结束 / 距开学倒计时），详见 [VacationInfo]。 */
    val vacation: VacationInfo? = null,
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    // 不放进主构造器：Kotlin 默认参数不会生成 `(Application)` 重载，
    // SavedStateViewModelFactory 会因找不到 AndroidViewModel 标准构造器而抛错
    private val repo: ScheduleRepository = ScheduleRepository.instance

    /**
     * 「今天」按钮在本学期应落到的周：学期进行中 = 真实当前教学周，已放假 = 最后一周，
     * 未开学 = 第 1 周。初值 1；repo 已有缓存（VM 重建）时马上被下面 state 初始化里的
     * [deriveTimeState] 覆盖，冷启动则在课表拉取完成后覆盖。
     * 仅当选中的是"本学期"时才会更新——历史/未来学期没有"今天"概念。
     */
    private var baselineWeek: Int = 1
    private var hasLoadedOnce: Boolean = false

    /** 用户是否手动切过学期。切过就别让"今日"按钮反复把他们拉回本学期。 */
    private var userPickedSemester: Boolean = false

    private val _state = MutableStateFlow(
        run {
            val ts = deriveTimeState()
            baselineWeek = ts.baselineWeek
            ScheduleScreenState(
                selectedWeek = ts.baselineWeek,
                totalWeeks = repo.totalWeeks(),
                semester = repo.currentSemester(),
                semesters = repo.availableSemesters(),
                selectedSemesterValue = repo.currentSemesterValue(),
                semesterStart = repo.currentSemesterStart(),
                currentWeek = ts.currentWeek,
                vacation = ts.vacation,
            )
        },
    )
    val state: StateFlow<ScheduleScreenState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * 一次性瞬时错误（切学期失败 / 刷新失败但旧数据仍在展示），Screen 收 Snackbar 展示。
     * 与整页 Error 的分工：正在看的课表**绝不因后台失败被顶掉**，只弹提示。
     */
    private val _transientError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val transientError: SharedFlow<String> = _transientError.asSharedFlow()

    init { loadWeek(baselineWeek) }

    /** 由 repo 当前缓存派生的"时间态"，见 [deriveTimeState]。 */
    private data class TimeState(
        /** 「今天」按钮在本学期的落点周（已钳制在 1..totalWeeks）。 */
        val baselineWeek: Int,
        /** 可观察的"本周"；仅"选中本学期且学期进行中"非 null。 */
        val currentWeek: Int?,
        /** 假期横幅信息；非假期语境为 null。 */
        val vacation: VacationInfo?,
        /** 当前选中的是否"本学期"（按日期推断 isCurrent 的那项）。 */
        val isCurrentSelected: Boolean,
    )

    /**
     * 每个数据落点（初始化 / loadWeek / refresh / selectSemester）统一走这里，把
     * [SemesterPhase] 翻译成 UI 时间态。之前的实现直接用"开学日到今天除以 7"当作本周、
     * 不设上限，暑假里会得出「第 20 周」——顶栏显示不存在的周、周 chip 无一选中、
     * 「今」列错误高亮，这是本函数要消灭的历史 bug。
     */
    private fun deriveTimeState(today: LocalDate = LocalDate.now()): TimeState {
        val semesters = repo.availableSemesters()
        val selectedValue = repo.currentSemesterValue()
        val isCurrentSelected = selectedValue != null &&
            semesters.firstOrNull { it.value == selectedValue }?.isCurrent == true
        val totalWeeks = repo.totalWeeks().coerceAtLeast(1)
        return when (val phase = repo.currentPhase(today)) {
            is SemesterPhase.InProgress -> if (isCurrentSelected) {
                val week = phase.week.coerceIn(1, totalWeeks)
                TimeState(week, week, null, isCurrentSelected = true)
            } else {
                // 选中的学期"进行中"却不是 isCurrent 项：仅在两个学期日期窗口重叠时出现，
                // 按"翻看其他学期"处理（无本周概念、无横幅）
                TimeState(baselineWeek, null, null, isCurrentSelected = false)
            }
            is SemesterPhase.Ended -> if (isCurrentSelected) {
                // 寒暑假：本学期已结束。「今天」落到最后一周；找下一个学期做开学倒计时
                //（比较与展示都用第 1 周周一，见 VacationInfo.nextStartDate）
                val next = semesters
                    .mapNotNull { o ->
                        o.startDate?.let { SemesterPhase.weekOneMonday(it) }
                            ?.takeIf { it.isAfter(today) }
                            ?.let { o to it }
                    }
                    .minByOrNull { it.second }
                TimeState(
                    baselineWeek = totalWeeks,
                    currentWeek = null,
                    vacation = VacationInfo(
                        semesterEnded = true,
                        nextStartDate = next?.second,
                        nextSemesterValue = next?.first?.value,
                    ),
                    isCurrentSelected = true,
                )
            } else {
                // 翻看早已结束的历史学期：不算假期语境，不打横幅
                TimeState(baselineWeek, null, null, isCurrentSelected = false)
            }
            is SemesterPhase.NotStarted -> TimeState(
                // 尚未开学的学期：假期里预览下学期（教务把默认切到新学期时也会走到这里）。
                // isCurrent 已按 weekOneMonday 对齐，正常不会出现"选中的本学期未开学"，
                // 条件保留作防御。横幅显示它自己的开学倒计时（真实上课首日）。
                baselineWeek = if (isCurrentSelected) 1 else baselineWeek,
                currentWeek = null,
                vacation = VacationInfo(semesterEnded = false, nextStartDate = phase.weekOneMonday),
                isCurrentSelected = isCurrentSelected,
            )
            null -> TimeState(baselineWeek, null, null, isCurrentSelected)
        }
    }

    /**
     * 切换显示的教学周。仅更新 [_state]，**不**重新拉教务网 —— 江师大课表 HTML 不区分周次，
     * 同一份 list 在所有周都返回相同数据，UI 层会用 [Course.isInWeek] 重新过滤。
     * 把 loadWeek 也跑一遍会把 data 翻成 Loading，闪一下骨架屏，没必要。
     */
    fun selectWeek(week: Int) {
        val bounded = week.coerceIn(1, _state.value.totalWeeks)
        if (bounded == _state.value.selectedWeek) return
        _state.update { it.copy(selectedWeek = bounded) }
    }

    /**
     * 点 "今天" 按钮 → 总是回到「本学期 + 本周」。
     * 当前已在本学期就只切周；不在本学期就先切学期、内部 selectSemester 会把周设到 baselineWeek。
     *
     * 语义就是"撤回任何主动选择"，所以两条分支都把 [userPickedSemester] 复位。
     * selectSemester 内部会把它再翻 true，但这里 launch 内复位发生在 selectSemester 完成之后，
     * 不会影响这次切换内的 selectedWeek 计算，只影响下一次 loadWeek 的 auto-snap 判断。
     */
    fun jumpToToday() {
        userPickedSemester = false
        val current = repo.availableSemesters().firstOrNull { it.isCurrent }
        if (current != null && current.value != _state.value.selectedSemesterValue) {
            selectSemester(current.value)
            // selectSemester launch 一个 coroutine 并已经 set userPickedSemester=true（同步部分），
            // 我们要的语义是"撤回主动选择"——让 selectSemester 完成后再次复位。
            viewModelScope.launch { userPickedSemester = false }
        } else {
            selectWeek(baselineWeek)
        }
    }

    /**
     * 切学期。POST 教务网 → 缓存解析结果 → 重置 selectedWeek / totalWeeks / semester。
     * 同 value 重复切换会被 Repository 短路；已缓存的学期秒切。
     */
    fun selectSemester(value: String) {
        if (value == _state.value.selectedSemesterValue) return
        if (_isRefreshing.value) {
            // 防并发（上一次切换/刷新还在 POST）：不能静默丢掉用户的切换意图——
            // sheet 已经关了，无提示的话就是"点了没反应"
            _transientError.tryEmit("正在刷新，请稍候再试")
            return
        }
        userPickedSemester = true
        // 保留旧课表数据，不切 Loading：已缓存学期 Repository 会秒切（不闪 spinner），
        // 未缓存的场景用户看到旧数据停留 200ms 也比白屏好。
        // 复用 isRefreshing 驱动顶栏 spinner：未缓存学期弱网下要等一次真实 POST，
        // 零指示会让用户以为"点了没反应"而反复重点。
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                repo.selectSemester(value)
                val list = repo.getSchedule(_state.value.selectedWeek)
                val ts = deriveTimeState()
                baselineWeek = ts.baselineWeek
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = repo.totalWeeks(),
                        selectedSemesterValue = value,
                        semesters = repo.availableSemesters(),
                        semesterStart = repo.currentSemesterStart(),
                        // 切到本学期 → 跳到"今天"基线周（进行中=本周，已放假=最后一周）；
                        // 切到历史/未来学期 → 第 1 周
                        selectedWeek = if (ts.isCurrentSelected) ts.baselineWeek else 1,
                        isOffline = false,
                        currentWeek = ts.currentWeek,
                        vacation = ts.vacation,
                    )
                }
                refreshWidgetSnapshot(list)
            } catch (t: Throwable) {
                // 失败保留正在展示的旧学期课表：整页置 Error 会把课表顶掉，而 Error 的
                // 重试走 refresh() 恢复的还是旧学期（切换意图已丢），只弹一次性提示更诚实。
                // 此前无成功数据（还停在 Loading / Error）才置 Error 给出重试入口。
                if (_state.value.data is UiState.Success) {
                    _transientError.tryEmit(t.toUserMessage("切换学期失败"))
                } else {
                    _state.update { it.copy(data = UiState.Error(t.toUserMessage("切换学期失败"))) }
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 下拉刷新或手动刷新。不切换 data 到 Loading，保留旧数据避免列表闪烁。
     * 顶部 spinner 由 [isRefreshing] 驱动。
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                repo.refresh()
                // 教务网偶尔会改 totalWeeks（个别学期 16/20 周），把当前选中的周次约束回新范围
                val newTotal = repo.totalWeeks()
                val coercedWeek = _state.value.selectedWeek.coerceIn(1, newTotal.coerceAtLeast(1))
                if (coercedWeek != _state.value.selectedWeek) {
                    _state.update { it.copy(selectedWeek = coercedWeek) }
                }
                val list = repo.getSchedule(coercedWeek)
                val ts = deriveTimeState()
                baselineWeek = ts.baselineWeek
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = newTotal,
                        semesters = repo.availableSemesters(),
                        selectedSemesterValue = repo.currentSemesterValue(),
                        semesterStart = repo.currentSemesterStart(),
                        isOffline = false,
                        currentWeek = ts.currentWeek,
                        vacation = ts.vacation,
                    )
                }
                refreshWidgetSnapshot(list)
                // 若上次未成功补学分，这里再试一次（已成功则 enrichWithCredits 内部短路）
                tryEnrichCredits()
            } catch (t: Throwable) {
                // data 已是 Success（在线旧数据或离线缓存）→ 保留不动；仅当还停在 Loading 才兜底/报错
                if (_state.value.data is UiState.Loading) {
                    fallbackToSnapshotOrError(t)
                } else {
                    // 旧数据仍在展示：不能只停转圈（会被误读成"刷新完成、就是最新"）
                    _transientError.tryEmit(t.toUserMessage("刷新失败"))
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 跨零点自愈：ScheduleScreen 的零点 ticker 调用。零点前后 [deriveTimeState] 的输入
     * （今天的日期）变了，但它只在数据落点被调用——页面驻留跨过周日→周一时 currentWeek
     * 停在上一周，「今」列高亮与列头日期会整体落在过期的一周网格上。
     * 在线态全部输入来自 repo 内存缓存，无网络请求。
     */
    fun onDayChanged() {
        val prev = _state.value
        val wasOnCurrentWeek = prev.currentWeek != null && prev.selectedWeek == prev.currentWeek
        if (prev.isOffline) {
            // 离线兜底态 repo 内存缓存是空的（这次拉取失败过），deriveTimeState 会把快照
            // 算出的时间态清成 null（「今」列高亮/假期横幅凭空消失）。改用已还原进 state
            // 的快照开学日重算，与 loadOfflineSnapshot 同源；老快照没存开学日则维持现状。
            val total = prev.totalWeeks.coerceAtLeast(1)
            when (val phase = SemesterPhase.at(prev.semesterStart, total, LocalDate.now())) {
                is SemesterPhase.InProgress -> {
                    val week = phase.week.coerceIn(1, total)
                    _state.update {
                        it.copy(
                            currentWeek = week,
                            vacation = null,
                            selectedWeek = if (wasOnCurrentWeek) week else it.selectedWeek,
                        )
                    }
                }
                is SemesterPhase.Ended -> _state.update {
                    // 跨零点恰逢学期结束：保留快照里已有的下学期开学日（若有）继续倒计时
                    it.copy(
                        currentWeek = null,
                        vacation = VacationInfo(
                            semesterEnded = true,
                            nextStartDate = it.vacation?.nextStartDate,
                        ),
                    )
                }
                is SemesterPhase.NotStarted -> _state.update {
                    it.copy(
                        currentWeek = null,
                        vacation = VacationInfo(semesterEnded = false, nextStartDate = phase.weekOneMonday),
                    )
                }
                null -> Unit
            }
            return
        }
        val ts = deriveTimeState()
        baselineWeek = ts.baselineWeek
        _state.update {
            it.copy(
                currentWeek = ts.currentWeek,
                vacation = ts.vacation,
                // 用户本来就停在"本周"、没有主动翻页 → 跟着推进到新的本周；
                // 主动翻到别的周则不打扰
                selectedWeek = if (wasOnCurrentWeek && ts.currentWeek != null) ts.currentWeek else it.selectedWeek,
            )
        }
    }

    /**
     * 修改某门课实际上课的周次。[weeks] 为 null/空 = 恢复教务网默认（1..18）。
     * 不重新拉教务网，直接重新算当前 selectedWeek 的课列表即可——仓库层会把 override 应用上。
     */
    fun updateCourseWeeks(courseName: String, weeks: List<Int>?) {
        repo.setCourseWeeks(courseName, weeks)
        viewModelScope.launch {
            val list = runCatching { repo.getSchedule(_state.value.selectedWeek) }.getOrNull()
                ?: return@launch
            _state.update {
                if (it.data is UiState.Success) it.copy(data = UiState.Success(list)) else it
            }
            refreshWidgetSnapshot(list)
        }
    }

    private fun loadWeek(week: Int) {
        _state.update { it.copy(data = UiState.Loading) }
        viewModelScope.launch {
            try {
                val list = repo.getSchedule(week)
                val wasFirstLoad = !hasLoadedOnce
                hasLoadedOnce = true
                val ts = deriveTimeState()
                baselineWeek = ts.baselineWeek
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = repo.totalWeeks(),
                        semesters = repo.availableSemesters(),
                        selectedSemesterValue = repo.currentSemesterValue(),
                        semesterStart = repo.currentSemesterStart(),
                        // 仅首次加载、且用户没主动切过学期时，把 selectedWeek 拉到"今天"基线周
                        //（进行中=本周，已放假=最后一周，未开学=第 1 周；均已钳制在合法范围）
                        selectedWeek = if (wasFirstLoad && !userPickedSemester) ts.baselineWeek else it.selectedWeek,
                        isOffline = false,
                        currentWeek = ts.currentWeek,
                        vacation = ts.vacation,
                    )
                }
                // 每次成功拿到课表，把"今天 + 当前周"的快照存给桌面小部件用
                refreshWidgetSnapshot(list)
                // 首次加载完成后，后台拉一次成绩页给课程详情补上学分（详见 ScheduleRepository.enrichWithCredits）
                if (wasFirstLoad) tryEnrichCredits()
            } catch (t: Throwable) {
                // 网络失败 → 回退磁盘快照（上次成功看到的本学期课表），离线也能看
                fallbackToSnapshotOrError(t)
            }
        }
    }

    /**
     * Best-effort：异步从成绩页拉学分映射，成功后用补全学分的 list 再 emit 一次。
     * 失败静默 —— 课表本身已经能展示，学分缺失只是细节回退到 0。
     */
    private fun tryEnrichCredits() {
        viewModelScope.launch {
            val enriched = runCatching { repo.enrichWithCredits() }.getOrDefault(false)
            if (!enriched) return@launch
            // 重新查一次同一周的列表，此时 ScheduleRepository 会把 0 学分自动补上
            val refreshed = runCatching { repo.getSchedule(_state.value.selectedWeek) }.getOrNull()
                ?: return@launch
            _state.update {
                if (it.data is UiState.Success) it.copy(data = UiState.Success(refreshed)) else it
            }
        }
    }

    /**
     * 把当前学期全部课程写入 [WidgetSnapshotStore]，并通知 [TodayScheduleWidget] 立即刷新。
     * Widget 进程独立运行，不主动联网，所有数据靠这一份快照；它会在渲染时按"此刻"
     * 现场算 weekday / week / 今日课程，因此 App 几天没开也能正确切日 / 切节次。
     *
     * 历史学期不要写 snapshot —— widget 永远显示"本学期·今天"。
     * 整个写入流程跑在 IO：JSON 序列化 + 文件写 + AppWidgetManager binder 调用都不能在 Main。
     */
    /** 离线兜底用的还原结果（从磁盘快照转出来）。 */
    private data class RestoredSchedule(
        val courses: List<Course>,
        val semester: String,
        val totalWeeks: Int,
        val semesterStart: LocalDate?,
        val week: Int,
        /** 快照学期"进行中"才非 null；放假/未开学时离线视图同样不高亮「今 / 本周」。 */
        val currentWeek: Int?,
        val vacation: VacationInfo?,
    )

    /** 读磁盘快照（上次成功保存的本学期课表）并还原成可展示数据；无快照返回 null。 */
    private suspend fun loadOfflineSnapshot(): RestoredSchedule? {
        val ctx = getApplication<Application>()
        val snap = withContext(Dispatchers.IO) { WidgetSnapshotStore.load(ctx) }
        if (snap.allCourses.isEmpty()) return null
        val totalWeeks = if (snap.totalWeeks > 0) snap.totalWeeks else 18
        val semesterStart =
            if (snap.hasSemesterStart) LocalDate.ofEpochDay(snap.semesterStartEpochDay) else null
        // 快照只可能是"本学期"的（refreshWidgetSnapshot 只在本学期写），按相位还原离线视图。
        // 假期横幅的开学倒计时用快照里存的下学期开学日（教务未放出时为 null，只显示「本学期已结束」）。
        val week: Int
        val currentWeek: Int?
        var vacation: VacationInfo? = null
        when (val phase = SemesterPhase.at(semesterStart, totalWeeks, LocalDate.now())) {
            is SemesterPhase.InProgress -> {
                week = phase.week.coerceAtMost(totalWeeks)
                currentWeek = week
            }
            is SemesterPhase.Ended -> {
                week = totalWeeks
                currentWeek = null
                vacation = VacationInfo(semesterEnded = true, nextStartDate = snap.nextSemesterStart)
            }
            is SemesterPhase.NotStarted -> {
                week = 1
                currentWeek = null
                vacation = VacationInfo(semesterEnded = false, nextStartDate = phase.weekOneMonday)
            }
            // 老快照没存开学日：沿用保存那一刻的周，无从判断假期
            null -> {
                week = snap.weekAt().coerceAtLeast(1)
                currentWeek = week
            }
        }
        return RestoredSchedule(
            courses = snap.toCourses(),
            semester = snap.semester.ifBlank { "上次课表" },
            totalWeeks = totalWeeks,
            semesterStart = semesterStart,
            week = week,
            currentWeek = currentWeek,
            vacation = vacation,
        )
    }

    /**
     * 拉取失败时的统一兜底：有磁盘快照就以「离线态」展示上次看到的课表，否则升级为 Error。
     * 离线态隐藏学期切换（快照不含可选学期列表），并把周次/学期重置成快照里的值。
     */
    private suspend fun fallbackToSnapshotOrError(error: Throwable) {
        val offline = loadOfflineSnapshot()
        if (offline == null) {
            _state.update { it.copy(data = UiState.Error(error.toUserMessage()), isOffline = false) }
            return
        }
        hasLoadedOnce = true
        baselineWeek = offline.week
        _state.update {
            it.copy(
                data = UiState.Success(offline.courses),
                semester = offline.semester,
                totalWeeks = offline.totalWeeks,
                semesterStart = offline.semesterStart,
                selectedSemesterValue = null,
                semesters = emptyList(),
                selectedWeek = offline.week.coerceIn(1, offline.totalWeeks.coerceAtLeast(1)),
                isOffline = true,
                currentWeek = offline.currentWeek,
                vacation = offline.vacation,
            )
        }
    }

    private fun refreshWidgetSnapshot(all: List<Course>) {
        // 切到历史/未来学期时不更新 widget snapshot —— widget 永远显示"本学期·今天"
        val isCurrentSemester = repo.availableSemesters()
            .firstOrNull { it.value == repo.currentSemesterValue() }?.isCurrent == true
        if (!isCurrentSemester) return
        val ctx = getApplication<Application>()
        // 下一个学期的开学日：假期态时 widget 用它显示「距开学 N 天」倒计时。
        // 快照存名义日期（读取侧 nextSemesterStart getter 统一对齐 weekOneMonday），但
        // "哪个学期算未来"必须按第 1 周周一判定——否则真实开学首日（如 8/31，名义 9/1
        // 还没到）会把当前学期自己当成"下一学期"写进快照。
        val today = LocalDate.now()
        val nextStart = repo.availableSemesters()
            .mapNotNull { it.startDate }
            .filter { SemesterPhase.weekOneMonday(it).isAfter(today) }
            .minOrNull()
        val snap = ScheduleSnapshot.fromCourses(
            semester = repo.currentSemester(),
            totalWeeks = repo.totalWeeks(),
            semesterStart = repo.currentSemesterStart(),
            all = all,
            nextSemesterStart = nextStart,
        )
        val snapshotGeneration = WidgetSnapshotStore.generation()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            WidgetSnapshotStore.save(ctx, snap, snapshotGeneration)
            runCatching {
                // 只通知已经被用户添加到桌面的 widget，避免没有 widget 时报错
                val mgr = GlanceAppWidgetManager(ctx)
                if (mgr.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                    TodayScheduleWidget().updateAll(ctx)
                }
            }
        }
    }
}
