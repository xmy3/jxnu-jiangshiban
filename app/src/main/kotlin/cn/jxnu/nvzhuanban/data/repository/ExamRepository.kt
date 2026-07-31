package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.ExamPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 考试安排仓库。
 *
 * 考试页内容更新频率低（每学期出几次新），所以走「内存缓存 + 显式 refresh」：
 *  - [getUpcomingExams] / [fetchAll]：首次拉网络，之后返回缓存
 *  - [refresh]：清缓存并重拉，下拉刷新走这里
 *
 * 课表页的"7 天内考试横幅"也复用同一份缓存，不会因此多发一次 HTTP。
 */
class ExamRepository {

    @Volatile
    private var cached: List<Exam>? = null
    private val mutex = Mutex()

    /** 缓存优先；首次或 [refresh] 后才走网络。 */
    suspend fun fetchAll(): List<Exam> = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    /** 历史命名保留：等价于 [fetchAll]，给 ExamsViewModel 用。 */
    suspend fun getUpcomingExams(): List<Exam> = fetchAll()

    suspend fun refresh(): List<Exam> = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    /** 退出登录时清空内存缓存。**无锁**（`@Volatile cached`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        cached = null
    }

    private suspend fun fetchNow(): List<Exam> {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_EXAM, "考试页返回空响应")
        return ExamPage.parse(html)
    }

    companion object {
        val instance: ExamRepository by lazy { ExamRepository() }
    }
}
