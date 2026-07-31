package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.MakeupExam
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.MakeupExamPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 补缓考考试安排仓库。
 *
 * 补缓考公告频率比常规考试更低（每学期开学初出一次），所以同样走「内存缓存 + 显式 refresh」。
 * 单例 + Mutex 与 [ExamRepository] 一致。
 */
class MakeupExamRepository {

    @Volatile
    private var cached: List<MakeupExam>? = null
    private val mutex = Mutex()

    suspend fun fetchAll(): List<MakeupExam> = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    suspend fun refresh(): List<MakeupExam> = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    /** 退出登录时清空内存缓存。**无锁**（`@Volatile cached`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        cached = null
    }

    private suspend fun fetchNow(): List<MakeupExam> {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_MAKEUP_EXAM, "补缓考页返回空响应")
        return MakeupExamPage.parse(html)
    }

    companion object {
        val instance: MakeupExamRepository by lazy { MakeupExamRepository() }
    }
}
