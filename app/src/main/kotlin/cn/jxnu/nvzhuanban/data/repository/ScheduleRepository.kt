package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import java.time.LocalDate

class ScheduleRepository(
    private val gradeRepo: GradeRepository = GradeRepository.instance,
) {

    /**
     * 当前激活的（学期）解析结果。等价于 [cachedBySemester]\[[selectedSemesterValue]\]，
     * 单独存一份方便没切学期时的 fast-path。
     */
    @Volatile
    private var cached: SchedulePage.Parsed? = null

    /**
     * 学期 value → 已加载好的解析结果。切学期时缓存历史结果，再切回去不需要重新 POST。
     *
     * 用 [java.util.concurrent.ConcurrentHashMap]（而非普通 map + mutex）：绝大多数读写仍在 [mutex]
     * 内串行，但 [clearCache] 需要**不经 mutex**地清空（避免 repo.mutex ⇄ authMutex 跨锁死锁，
     * 见 [clearCache] 注释），ConcurrentHashMap 让那次并发 clear 不会撞坏正在 mutex 内进行的读写结构。
     */
    private val cachedBySemester = java.util.concurrent.ConcurrentHashMap<String, SchedulePage.Parsed>()

    /** 当前选中的学期 [SchedulePage.SemesterOption.value]；初值在 [ensureLoaded] 后填入。 */
    @Volatile
    private var selectedSemesterValue: String? = null

    /**
     * 课程名 → 学分 的映射，由 [enrichWithCredits] 从成绩页拉到后填充。
     * SchedulePage 解析不出学分，这里用历史成绩里的学分作 fallback（同名课程学分稳定）。
     */
    @Volatile
    private var creditMap: Map<String, Float>? = null

    private val mutex = Mutex()
    private val enrichMutex = Mutex()

    private suspend fun ensureLoaded(): SchedulePage.Parsed = mutex.withLock {
        ensureLoadedLocked()
    }

    /**
     * 与 [ensureLoaded] 同义，但**调用方需自行持有 [mutex]**。
     * 用来让 [refresh] 把"清缓存 + 重新加载"放进同一把锁内完成 —— 否则中途的
     * [selectSemester] 会看到空缓存并发起一次冲突的 POST，撞坏 ASP.NET ViewState 链。
     */
    private suspend fun ensureLoadedLocked(): SchedulePage.Parsed {
        cached?.let { return it }
        val initial = fetchCurrent()
        // 教务网默认渲染的学期（HTML 里 <option selected="selected"> 那一项）
        val serverValue = initial.serverSelectedValue
        // 按今天日期推断的"本学期"。和服务器默认渲染的可能不一致：
        // 教务有时把默认停在下一学期（开学前几周）或上一学期（暑假初期）。
        val byDateValue = initial.semesters.firstOrNull { it.isCurrent }?.value

        // 不管走哪条路，先把首次响应按它实际代表的学期 value 收进缓存，免得后面再切回来时白多 POST 一次
        if (serverValue != null) {
            cachedBySemester[serverValue] = initial
        }

        // 假期特例：按日期的"本学期"已经结束（寒暑假），而服务器默认渲染的是一个未来学期
        // （教务通常在假期后段就把默认切到下学期）→ 尊重服务器的选择，不再 POST 切回
        // 已结束的学期。教务切默认学期的时机就是"该看新学期了"的最好信号，还省一次网络请求。
        // "未来学期"按第 1 周周一（weekOneMonday）判定，与 isCurrent/相位同一套坐标——
        // 名义 3/1 落在周日时，3/1 当天服务器默认的新学期仍算"未来"，保持预览态而不是回切旧学期。
        // byDate 学期自己的 totalWeeks 拿不到（那页没抓），用当前响应的 totalWeeks 近似——
        // 同校学期周数基本一致，偏差最多让这个特例晚/早生效一两周，无实害。
        val today = LocalDate.now()
        val byDateStart = initial.semesters.firstOrNull { it.isCurrent }?.startDate
        val serverStart = initial.semesters.firstOrNull { it.value == serverValue }?.startDate
        val vacationRespectServer =
            SemesterPhase.at(byDateStart, initial.totalWeeks, today) is SemesterPhase.Ended &&
                serverStart?.let { SemesterPhase.weekOneMonday(it).isAfter(today) } == true

        // 默认渲染的不是"本学期" → 自动 POST 切到本学期。失败就退回到默认数据，至少让用户看到点东西。
        val byDate = if (!vacationRespectServer && byDateValue != null && byDateValue != serverValue) {
            runCatching { fetchSemester(byDateValue, initial) }.getOrNull()
        } else null

        return if (byDate != null) {
            cachedBySemester[byDateValue!!] = byDate
            cached = byDate
            selectedSemesterValue = byDateValue
            byDate
        } else {
            cached = initial
            selectedSemesterValue = serverValue
                ?: byDateValue
                ?: initial.semesters.firstOrNull()?.value
            initial
        }
    }

    /** GET 课表首页：返回服务器当前学期的数据。 */
    private suspend fun fetchCurrent(): SchedulePage.Parsed {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_SCHEDULE, "课表页返回空响应")
        return SchedulePage.parse(html)
    }

    /**
     * POST 切到指定学期。需要把上一份解析结果里的 ASP.NET 隐藏字段原样回带，
     * 否则服务器认不出来这是同一个会话的下一步动作。
     */
    private suspend fun fetchSemester(value: String, donor: SchedulePage.Parsed): SchedulePage.Parsed {
        val prefix = donor.controlPrefix
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("__VIEWSTATE", donor.viewState)
            .add("__VIEWSTATEGENERATOR", donor.viewStateGenerator)
            .add("__EVENTVALIDATION", donor.eventValidation)
            .add("$prefix:ddlSterm", value)
            .add("$prefix:btnSearch", "确定")
            .build()
        val html = JwcClient.postHtmlAuth(JxnuUrls.PAGE_SCHEDULE, form, "课表页返回空响应")
        return SchedulePage.parse(html)
    }

    /** 把 cached 的 courses 用 creditMap 补上学分；creditMap 为空时原样返回。 */
    private fun enrichCourses(parsed: SchedulePage.Parsed): List<Course> {
        val cm = creditMap ?: return parsed.courses
        return parsed.courses.map { c ->
            if (c.credit > 0f) c
            else cm[c.name]?.let { credit -> c.copy(credit = credit) } ?: c
        }
    }

    /**
     * 用 [CourseOverridesStore] 里用户改过的周次覆盖默认 1..18。Store 为空时原样返回。
     * 这一步必须在 [enrichCourses] 之后再做：先补学分（保留 weeks=默认）再 override，
     * 否则学分补全会用错的 Course 实例对比。
     */
    private fun applyWeekOverrides(courses: List<Course>): List<Course> {
        val overrides = CourseOverridesStore.current()
        if (overrides.isEmpty()) return courses
        return courses.map { c ->
            overrides[c.name]?.let { c.copy(weeks = it) } ?: c
        }
    }

    /** 江师大课表页不区分周次，所有周次返回相同的 List<Course>；接口签名保留以兼容 UI 切周。 */
    suspend fun getSchedule(week: Int): List<Course> =
        applyWeekOverrides(enrichCourses(ensureLoaded()))

    /**
     * 用户修改某门课的实际上课周次。null/空 list = 恢复教务网默认（1..18）。
     * 不需要重新拉教务网；下次 [getSchedule] 自动把新覆盖叠加上去。
     */
    fun setCourseWeeks(name: String, weeks: List<Int>?) {
        CourseOverridesStore.set(name, weeks)
    }

    /**
     * 切到指定 [value] 学期（来自 [SchedulePage.SemesterOption.value]）。
     * 已缓存就秒切；未缓存就 POST 一次教务网，结果存进 [cachedBySemester]。
     *
     * 切学期不影响 [creditMap]：学分映射来自所有历史成绩，切学期也通用。
     */
    suspend fun selectSemester(value: String) {
        if (value == selectedSemesterValue && cached != null) return
        mutex.withLock {
            val hit = cachedBySemester[value]
            if (hit != null) {
                cached = hit
                selectedSemesterValue = value
                return@withLock
            }
            // 切学期需要现存的 ViewState 当 POST 凭据
            val donor = cached ?: fetchCurrent().also { first ->
                cached = first
                // 把初始响应按它实际代表的学期 value 索引（来自 HTML 的 <option selected>），
                // 避免后续切回这一学期时白多一次 POST。注意 [SemesterOption.isCurrent] 是按日期
                // 推断的，跟服务器渲染的学期可能不一致，不能当缓存 key。
                val sel = first.serverSelectedValue
                    ?: first.semesters.firstOrNull { it.isCurrent }?.value
                    ?: first.semesters.firstOrNull()?.value
                if (sel != null) {
                    selectedSemesterValue = sel
                    cachedBySemester[sel] = first
                }
            }
            if (value == selectedSemesterValue) return@withLock
            val parsed = fetchSemester(value, donor)
            cachedBySemester[value] = parsed
            cached = parsed
            selectedSemesterValue = value
        }
    }

    suspend fun refresh() {
        // 整个 refresh 串行在 mutex 内：clear → 重新拉 → 必要时 selectSemester。
        // 旧实现把 ensureLoaded() 放在锁外，并发的 selectSemester 会看到空缓存 → 自己再发起一次 POST，
        // 两个 POST 各自带不同时刻 fetch 出的 ViewState，互相把对方的 session 弹掉。
        val prevCached: SchedulePage.Parsed?
        val prevByValue: Map<String, SchedulePage.Parsed>
        val keepSelected: String?
        mutex.withLock {
            prevCached = cached
            prevByValue = cachedBySemester.toMap()
            keepSelected = selectedSemesterValue
            cached = null
            cachedBySemester.clear()
            selectedSemesterValue = null
            try {
                ensureLoadedLocked()
                // 用户手动切过学期就保留选择，仅刷数据；从未切过学期就回到当前学期（ensureLoadedLocked 已处理）。
                // 这里需要在持锁状态下做后续切换，避免与并发 selectSemester 抢 ViewState。
                if (keepSelected != null && keepSelected != selectedSemesterValue) {
                    switchToSemesterLocked(keepSelected)
                }
            } catch (t: Throwable) {
                cached = prevCached
                cachedBySemester.clear()
                cachedBySemester.putAll(prevByValue)
                selectedSemesterValue = keepSelected
                throw t
            }
        }
    }

    /**
     * 与 [selectSemester] 同义但**调用方需自行持有 [mutex]**。
     * 用在 [refresh] 内部，把"reload current → switch back to user-picked semester"一气呵成。
     */
    private suspend fun switchToSemesterLocked(value: String) {
        if (value == selectedSemesterValue && cached != null) return
        val hit = cachedBySemester[value]
        if (hit != null) {
            cached = hit
            selectedSemesterValue = value
            return
        }
        val donor = cached ?: error("switchToSemesterLocked 调用前应已 ensureLoadedLocked")
        val parsed = fetchSemester(value, donor)
        cachedBySemester[value] = parsed
        cached = parsed
        selectedSemesterValue = value
    }

    /**
     * 后台用成绩页补全 [creditMap]。失败静默；成功后 [getSchedule] 自动返回补全后的数据。
     * 由 ViewModel 在课表加载完成后调用，best-effort，**不应阻塞 UI**。
     *
     * 已经加载过就直接返回，不重复请求。
     *
     * @return true 表示这次调用真的拉到了新数据（首次成功），调用方可据此决定是否要再 emit 一次。
     */
    suspend fun enrichWithCredits(): Boolean = enrichMutex.withLock {
        if (creditMap != null) return@withLock false
        val result = runCatching { gradeRepo.fetchAll() }.getOrNull() ?: return@withLock false
        // 同名课程历史上学分一致；偶发不一致时后面学期覆盖前面（保留最新）
        creditMap = result.semesters
            .flatMap { it.grades }
            .filter { it.credit > 0f }
            .associate { it.courseName to it.credit }
        true
    }

    /** 所有可选学期；未加载过返回空列表。 */
    fun availableSemesters(): List<SchedulePage.SemesterOption> = cached?.semesters.orEmpty()

    /** 当前选中的学期 value；未加载过返回 null。 */
    fun currentSemesterValue(): String? = selectedSemesterValue

    /**
     * 今天相对**当前选中学期**的相位（进行中第几周 / 未开学 / 已结束）。
     * 未加载或教务没给开学日时返回 null。仅当选中的是"本学期"时对"今天是第几周"有意义；
     * 选中历史学期时会得到 [SemesterPhase.Ended]、未来学期得到 [SemesterPhase.NotStarted]，
     * 由 ViewModel 结合 [SchedulePage.SemesterOption.isCurrent] 解读。
     */
    fun currentPhase(today: LocalDate = LocalDate.now()): SemesterPhase? = cached?.phaseAt(today)
    fun totalWeeks(): Int = cached?.totalWeeks ?: 18
    fun currentSemester(): String = cached?.semester ?: "—"
    /** 当前已加载学期的开学日；UI 用来把 `(week, weekday)` 反算成日期。 */
    fun currentSemesterStart(): java.time.LocalDate? = cached?.semesterStart

    /**
     * 退出登录时清空所有内存 + 派生状态；下一用户登录后看到的是从零开始的视图。
     *
     * **无锁、非 suspend**：直接置 `@Volatile` 标量为 null + 清 ConcurrentHashMap。绝不能 `mutex.withLock` ——
     * 本方法经 `AuthRepository.clearRepositoryCaches` 在**持有 authMutex 时**调用，而 `ensureLoaded`/
     * `selectSemester`/`refresh` 持有本类 mutex 期间会经 `getHtmlAuth` → reauth 去抢 authMutex；两者一旦
     * 都加锁就构成 `repo.mutex ⇄ authMutex` 跨锁死锁环。无锁清空最差只是与一次并发加载的良性竞态
     * （可能残留刚加载的一份），下次 refresh 自愈；跨账号安全由「登录成功后的换号检测 + 清空」兜底。
     */
    fun clearCache() {
        cached = null
        selectedSemesterValue = null
        cachedBySemester.clear()
        creditMap = null
    }

    companion object {
        val instance: ScheduleRepository by lazy { ScheduleRepository() }
    }
}
