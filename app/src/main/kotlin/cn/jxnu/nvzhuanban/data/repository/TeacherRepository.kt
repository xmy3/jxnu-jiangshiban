package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.TeacherSearchQuery
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.TeacherSearchPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody

/**
 * 教工检索仓库。
 *
 * 教务网搜索是 ASP.NET 标准 PostBack：每次 POST 都要带上前一次响应里的 `__VIEWSTATE` 三件套。
 * 这里缓存的不是搜索结果（每次关键词都不同），而是上一次响应作为下一次 POST 的 donor；
 * 第一次进入时如果没有 donor，先 GET 一次种子页面拿到初始 hidden field。
 */
class TeacherRepository {

    @Volatile
    private var donor: TeacherSearchPage.Parsed? = null

    private val mutex = Mutex()

    suspend fun search(query: TeacherSearchQuery): TeacherSearchPage.Parsed = mutex.withLock {
        val seed = donor ?: fetchSeed().also { donor = it }
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("__LASTFOCUS", "")
            .add("__VIEWSTATE", seed.viewState)
            .add("__VIEWSTATEGENERATOR", seed.viewStateGenerator)
            .add("__EVENTVALIDATION", seed.eventValidation)
            .add("_ctl1:rbtType", "SQL")
            .add("_ctl1:txtKeyWord", query.keyword)
            .add("_ctl1:ddlType", query.field.formValue)
            .add("_ctl1:ddlSQLType", query.mode.formValue)
            .add("_ctl1:btnSearch", "查询")
            .build()
        val html = JwcClient.postHtmlAuth(JxnuUrls.PAGE_TEACHER_SEARCH, form, "教工查询页返回空响应")
        TeacherSearchPage.parse(html).also { donor = it }
    }

    /** 退出登录时清空 donor，下一次搜索会重新 GET seed。**无锁**（`@Volatile donor`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        donor = null
    }

    private suspend fun fetchSeed(): TeacherSearchPage.Parsed {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_TEACHER_SEARCH, "教工查询页返回空响应")
        return TeacherSearchPage.parse(html)
    }

    companion object {
        val instance: TeacherRepository by lazy { TeacherRepository() }
    }
}
