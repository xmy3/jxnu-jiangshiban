package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.ParseWarnings
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.network.pages.PageParse
import cn.jxnu.nvzhuanban.data.network.pages.requireComplete
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GradeRepository(
    private val fetchHtml: suspend () -> String = {
        JwcClient.getHtmlAuth(JxnuUrls.PAGE_GRADE, "成绩页返回空响应")
    },
) {

    /**
     * 内存缓存：成绩页一次性返回所有学期，反复刷无意义。多个调用方共享同一份解析结果
     * （Profile 取 StudentMeta、ScheduleRepository 用 grades 取学分映射）。需要刷新走 [refresh]。
     */
    @Volatile
    private var cached: GradePage.Parsed? = null
    private val mutex = Mutex()

    /** 完整成绩页面解析结果（含个人信息）。供需要 totalCredit / 班级名等信息的页面调用。 */
    suspend fun fetchAll(): GradePage.Parsed = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    private suspend fun fetchNow(): GradePage.Parsed = fetchPage().value

    private suspend fun fetchPage(): PageParse<GradePage.Parsed> {
        val html = fetchHtml()
        val page = GradePage.parsePage(html)
        // 只在真正拉网络时上报；缓存命中不重复弹同一句告警
        page.warnings.forEach(ParseWarnings::report)
        return page
    }

    suspend fun getAllSemesters(): List<SemesterSummary> = fetchAll().semesters

    suspend fun refresh(): GradePage.Parsed = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    suspend fun refreshForAlerts(): GradePage.Parsed = mutex.withLock {
        fetchPage().requireComplete().also { cached = it }
    }

    /**
     * 退出登录时清空内存缓存，避免下一用户登录后看到上一用户的成绩。
     *
     * **故意无锁**：`cached` 是 `@Volatile`，直接置 null 即可。绝不能 `mutex.withLock` ——
     * 本方法经 `AuthRepository.clearRepositoryCaches` 在**持有 authMutex 时**调用，而业务 `fetchAll`
     * 持有本类 mutex 期间会经 `getHtmlAuth` → reauth 去抢 authMutex；两者一旦加锁就构成
     * `repo.mutex ⇄ authMutex` 跨锁死锁环。无锁清空最差只是一次过期数据被读到，下次 refresh 自愈。
     */
    fun clearCache() {
        cached = null
    }

    companion object {
        val instance: GradeRepository by lazy { GradeRepository() }
    }
}
