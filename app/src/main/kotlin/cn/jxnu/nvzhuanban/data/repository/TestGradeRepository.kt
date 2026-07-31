package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.TestGradeReport
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.TestGradePage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 考试出分（期中 / 期末）页面仓库。
 *
 * 该页内容时效短（出分期间频繁更新），但单次请求开销不小，所以仍走「内存缓存 + 显式 refresh」的常规模式，
 * 与 [GradeRepository] 一致。下拉刷新通过 [refresh] 绕过缓存重取。
 */
class TestGradeRepository {

    @Volatile
    private var cached: TestGradeReport? = null
    private val mutex = Mutex()

    suspend fun fetchAll(): TestGradeReport = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    suspend fun refresh(): TestGradeReport = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    /** 退出登录时清空内存缓存。**无锁**（`@Volatile cached`），避免 repo.mutex ⇄ authMutex 跨锁死锁——见 GradeRepository.clearCache。 */
    fun clearCache() {
        cached = null
    }

    private suspend fun fetchNow(): TestGradeReport {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_TEST_GRADE, "考试出分页返回空响应")
        return TestGradePage.parse(html)
    }

    companion object {
        val instance: TestGradeRepository by lazy { TestGradeRepository() }
    }
}
