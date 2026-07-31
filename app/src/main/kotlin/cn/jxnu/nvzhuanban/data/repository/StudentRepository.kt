package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.StudentSearchQuery
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.StudentSearchPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody

/**
 * 学生检索仓库——与 [TeacherRepository] 镜像，只是 URL 换成 [JxnuUrls.PAGE_STUDENT_SEARCH]，
 * 解析器换成 [StudentSearchPage]，结果是 [cn.jxnu.nvzhuanban.data.model.Student]。
 *
 * 缓存的是上一次响应（作为下一次 POST 的 donor）；每次关键词不同，搜索结果本身不缓存。
 */
class StudentRepository {

    @Volatile
    private var donor: StudentSearchPage.Parsed? = null

    private val mutex = Mutex()

    suspend fun search(query: StudentSearchQuery): StudentSearchPage.Parsed = mutex.withLock {
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
        val html = JwcClient.postHtmlAuth(JxnuUrls.PAGE_STUDENT_SEARCH, form, "学生查询页返回空响应")
        StudentSearchPage.parse(html).also { donor = it }
    }

    /** 退出登录时清空 donor。**无锁**（`@Volatile donor`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        donor = null
    }

    private suspend fun fetchSeed(): StudentSearchPage.Parsed {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_STUDENT_SEARCH, "学生查询页返回空响应")
        return StudentSearchPage.parse(html)
    }

    companion object {
        val instance: StudentRepository by lazy { StudentRepository() }
    }
}
