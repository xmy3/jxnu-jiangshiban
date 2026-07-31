package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.GraduationAuditPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GraduationAuditRepository {

    @Volatile
    private var cached: GraduationAuditPage.Parsed? = null

    private val mutex = Mutex()

    suspend fun fetch(): GraduationAuditPage.Parsed = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    suspend fun refresh(): GraduationAuditPage.Parsed = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    /** 退出登录时清空内存缓存。**无锁**（`@Volatile cached`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        cached = null
    }

    private suspend fun fetchNow(): GraduationAuditPage.Parsed {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_GRADUATION_AUDIT, "毕业审核页返回空响应")
        return GraduationAuditPage.parse(html)
    }

    companion object {
        val instance: GraduationAuditRepository by lazy { GraduationAuditRepository() }
    }
}
