package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.MakeupExam
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.ParseWarnings
import cn.jxnu.nvzhuanban.data.network.pages.MakeupExamPage
import cn.jxnu.nvzhuanban.data.network.pages.PageParse
import cn.jxnu.nvzhuanban.data.network.pages.requireComplete

/**
 * 补缓考考试安排仓库。
 *
 * 补缓考公告频率比常规考试更低（每学期开学初出一次），所以同样走「内存缓存 + 显式 refresh」。
 * 单例 + Mutex 与 [ExamRepository] 一致。
 */
class MakeupExamRepository(
    private val fetchHtml: suspend () -> String = {
        JwcClient.getHtmlAuth(JxnuUrls.PAGE_MAKEUP_EXAM, "补缓考页返回空响应")
    },
) {

    private val cache = RepositoryCache<List<MakeupExam>>()

    suspend fun fetchAll(): List<MakeupExam> = cache.load { fetchPage().value }

    suspend fun refresh(): List<MakeupExam> = cache.load(refresh = true) { fetchPage().value }

    suspend fun refreshForAlerts(): List<MakeupExam> = cache.load(refresh = true) {
        fetchPage().requireComplete()
    }

    fun clearCache() {
        cache.clear()
    }

    private suspend fun fetchPage(): PageParse<List<MakeupExam>> {
        val html = fetchHtml()
        val page = MakeupExamPage.parsePage(html)
        // 只在真正拉网络时上报；缓存命中不重复弹同一句告警
        page.warnings.forEach(ParseWarnings::report)
        return page
    }

    companion object {
        val instance: MakeupExamRepository by lazy { MakeupExamRepository() }
    }
}
