package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.CourseOfferingForm
import cn.jxnu.nvzhuanban.data.model.CourseOfferingQuery
import cn.jxnu.nvzhuanban.data.model.CourseOfferingTable
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.CourseOfferingPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody

/**
 * 开课安排查询仓库（`Public_Kkap.aspx`）。
 *
 * 与 [StudentRepository] 同款 donor 模式：首查 GET 表单页拿三件套 + 下拉选项，
 * 之后每次查询 POST 复用上一响应的三件套（每次响应都会刷新 donor）。
 * 查询 POST 需要登录会话（匿名恒返「系统错误」，见 [JxnuUrls.PAGE_COURSE_OFFERING]），
 * 两步统一走 *Auth 变体，会话失效自动重登重放。
 *
 * 查询结果本身不缓存——条件组合太多且时效敏感，缓存的只有 donor 表单。
 */
class CourseOfferingRepository {

    @Volatile
    private var donor: CourseOfferingForm? = null

    private val mutex = Mutex()

    /** 表单（学期 / 学院等下拉选项）。UI 进屏时先调它渲染筛选器。 */
    suspend fun fetchForm(): CourseOfferingForm = mutex.withLock {
        donor ?: fetchSeed().also { donor = it }
    }

    suspend fun search(query: CourseOfferingQuery): CourseOfferingTable = mutex.withLock {
        val seed = donor ?: fetchSeed().also { donor = it }
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("__VIEWSTATE", seed.viewState)
            .add("__VIEWSTATEGENERATOR", seed.viewStateGenerator)
            .add("__EVENTVALIDATION", seed.eventValidation)
            .add("ddlSterm", query.semesterValue)
            .add("ddlCollege", query.collegeValue)
            .add("ddlWeek", query.weekValue)
            .add("ddlJC", query.sectionValue)
            .add("txtJS", query.classroom)
            .add("txtKc", query.courseName)
            .add("txtTeacher", query.teacherName)
            .add("btnSearch", "查询")
            .build()
        val html = JwcClient.postHtmlAuth(JxnuUrls.PAGE_COURSE_OFFERING, form, "开课查询页返回空响应")
        if (CourseOfferingPage.isSystemError(html)) {
            // 教务网应用层报错（51 字节裸文本）。丢掉 donor 让下次查询重新 GET 种子——
            // 若是三件套/会话边缘态导致，重种子即可自愈；若是后端真坏，重试也只是同样报错。
            donor = null
            throw JwcException(
                JwcError.Unknown("教务系统处理该查询时报错，请调整筛选条件后重试"),
            )
        }
        // POST 响应会重渲整个表单，刷新 donor 以取最新三件套；结构异常（拿不到 viewState）时保留旧 donor
        CourseOfferingPage.parseForm(html).takeIf { it.viewState.isNotEmpty() }?.let { donor = it }
        CourseOfferingPage.parseResult(html)
    }

    /** 退出登录时清空 donor。**无锁**（`@Volatile donor`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        donor = null
    }

    private suspend fun fetchSeed(): CourseOfferingForm {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_COURSE_OFFERING, "开课查询页返回空响应")
        return CourseOfferingPage.parseForm(html)
    }

    companion object {
        val instance: CourseOfferingRepository by lazy { CourseOfferingRepository() }
    }
}
