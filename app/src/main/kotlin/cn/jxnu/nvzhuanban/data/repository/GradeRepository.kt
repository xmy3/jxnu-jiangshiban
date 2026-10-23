package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.ParseWarnings
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.network.pages.PageParse
import cn.jxnu.nvzhuanban.data.network.pages.requireComplete

class GradeRepository(
    private val fetchHtml: suspend () -> String = {
        JwcClient.getHtmlAuth(JxnuUrls.PAGE_GRADE, "成绩页返回空响应")
    },
) {

    /**
     * 内存缓存：成绩页一次性返回所有学期，反复刷无意义。多个调用方共享同一份解析结果
     * （Profile 取 StudentMeta、ScheduleRepository 用 grades 取学分映射）。需要刷新走 [refresh]。
     */
    private val cache = RepositoryCache<GradePage.Parsed>()

    /** 完整成绩页面解析结果（含个人信息）。供需要 totalCredit / 班级名等信息的页面调用。 */
    suspend fun fetchAll(): GradePage.Parsed = cache.load { fetchNow() }

    private suspend fun fetchNow(): GradePage.Parsed = fetchPage().value

    private suspend fun fetchPage(): PageParse<GradePage.Parsed> {
        val html = fetchHtml()
        val page = GradePage.parsePage(html)
        // 只在真正拉网络时上报；缓存命中不重复弹同一句告警
        page.warnings.forEach(ParseWarnings::report)
        return page
    }

    suspend fun getAllSemesters(): List<SemesterSummary> = fetchAll().semesters

    suspend fun refresh(): GradePage.Parsed = cache.load(refresh = true) { fetchNow() }

    suspend fun refreshForAlerts(): GradePage.Parsed = cache.load(refresh = true) {
        fetchPage().requireComplete()
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        val instance: GradeRepository by lazy { GradeRepository() }
    }
}
