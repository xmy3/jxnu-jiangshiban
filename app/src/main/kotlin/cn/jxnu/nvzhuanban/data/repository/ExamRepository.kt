package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.ParseWarnings
import cn.jxnu.nvzhuanban.data.network.pages.ExamPage
import cn.jxnu.nvzhuanban.data.network.pages.PageParse
import cn.jxnu.nvzhuanban.data.network.pages.requireComplete

/**
 * 考试安排仓库。
 *
 * 考试页内容更新频率低（每学期出几次新），所以走「内存缓存 + 显式 refresh」：
 *  - [getUpcomingExams] / [fetchAll]：首次拉网络，之后返回缓存
 *  - [refresh]：清缓存并重拉，下拉刷新走这里
 *
 * 课表页的"7 天内考试横幅"也复用同一份缓存，不会因此多发一次 HTTP。
 */
class ExamRepository(
    private val fetchHtml: suspend () -> String = {
        JwcClient.getHtmlAuth(JxnuUrls.PAGE_EXAM, "考试页返回空响应")
    },
) {

    private val cache = RepositoryCache<List<Exam>>()

    /** 缓存优先；首次或 [refresh] 后才走网络。 */
    suspend fun fetchAll(): List<Exam> = cache.load { fetchNow() }

    /** 历史命名保留：等价于 [fetchAll]，给 ExamsViewModel 用。 */
    suspend fun getUpcomingExams(): List<Exam> = fetchAll()

    suspend fun refresh(): List<Exam> = cache.load(refresh = true) { fetchNow() }

    suspend fun refreshForAlerts(): List<Exam> = cache.load(refresh = true) {
        fetchPage().requireComplete()
    }

    fun clearCache() {
        cache.clear()
    }

    private suspend fun fetchNow(): List<Exam> = fetchPage().value

    private suspend fun fetchPage(): PageParse<List<Exam>> {
        val html = fetchHtml()
        val page = ExamPage.parsePage(html)
        // 只在真正拉网络时上报；缓存命中不重复弹同一句告警
        page.warnings.forEach(ParseWarnings::report)
        return page
    }

    companion object {
        val instance: ExamRepository by lazy { ExamRepository() }
    }
}
