package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody

/**
 * 通用的「别人的课表」仓库——每次查教师/学生课表都新建一个实例（不走 by lazy 单例），
 * 由 ViewModel 持有，VM 销毁即释放。
 *
 * 教师课表 URL：`MyControl/All_Display.aspx?UserControl=Xfz_Kcb.ascx&UserType=Teacher&UserNum=…`
 * 学生课表 URL：同上，`UserType=Student`。两者 HTML 结构一致，都用 `_ctl6_*` 控件 id，
 * 共用 [SchedulePage] 解析；切学期 POST 表单字段名前缀由 [SchedulePage.Parsed.controlPrefix] 提供。
 *
 * 注意：本仓库 GET/POST 同一个 [scheduleUrl]——构造时把完整 URL 传进来即可。
 */
class UserScheduleRepository(private val scheduleUrl: String) {

    private val mutex = Mutex()

    @Volatile
    private var cached: SchedulePage.Parsed? = null

    /** value -> parsed，加速重选历史学期。 */
    private val cachedBySemester = mutableMapOf<String, SchedulePage.Parsed>()

    @Volatile
    private var selectedValue: String? = null

    /**
     * 首次加载课表。逻辑与 [ScheduleRepository.ensureLoadedLocked] 一致：
     *  1. GET seed —— 教务网默认渲染的学期（HTML 里 `<option selected>` 那一项）；
     *  2. 与"按日期推断的本学期" [SchedulePage.SemesterOption.isCurrent] 比较；
     *  3. 不一致时（教务常把别人课表的默认停在下一学期，比如 5 月看到的是 26-27 第 1 学期），
     *     **自动再 POST 一次切到本学期**，省掉用户手动切学期的步骤；POST 失败就退回 seed。
     *
     * 两份解析结果都按各自的 value 索引进 [cachedBySemester]，避免后续切回时白多 POST 一次。
     */
    suspend fun fetch(): SchedulePage.Parsed = mutex.withLock {
        cached?.let { return@withLock it }
        val seed = fetchSeed()
        val serverValue = seed.serverSelectedValue
        if (serverValue != null) {
            cachedBySemester[serverValue] = seed
        }
        val byDateValue = seed.semesters.firstOrNull { it.isCurrent }?.value
        val byDate = if (byDateValue != null && byDateValue != serverValue) {
            runCatching { fetchSemester(byDateValue, seed) }.getOrNull()
        } else null

        if (byDate != null && byDateValue != null) {
            cachedBySemester[byDateValue] = byDate
            cached = byDate
            selectedValue = byDateValue
            byDate
        } else {
            cached = seed
            selectedValue = serverValue ?: byDateValue ?: seed.semesters.firstOrNull()?.value
            seed
        }
    }

    /** 切到指定学期（来自 [SchedulePage.SemesterOption.value]），缓存命中即秒切。 */
    suspend fun selectSemester(value: String): SchedulePage.Parsed = mutex.withLock {
        cachedBySemester[value]?.let {
            cached = it
            selectedValue = value
            return@withLock it
        }
        val donor = cached ?: fetchSeed().also { first ->
            cached = first
            val serverValue = first.serverSelectedValue
            if (serverValue != null) {
                cachedBySemester[serverValue] = first
                selectedValue = serverValue
            }
        }
        if (value == selectedValue && cached != null) return@withLock cached!!
        val parsed = fetchSemester(value, donor)
        cachedBySemester[value] = parsed
        cached = parsed
        selectedValue = value
        parsed
    }

    private suspend fun fetchSeed(): SchedulePage.Parsed {
        val html = JwcClient.getHtmlAuth(scheduleUrl, "课表页返回空响应")
        return SchedulePage.parse(html)
    }

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
        val html = JwcClient.postHtmlAuth(scheduleUrl, form, "课表页返回空响应")
        return SchedulePage.parse(html)
    }
}
