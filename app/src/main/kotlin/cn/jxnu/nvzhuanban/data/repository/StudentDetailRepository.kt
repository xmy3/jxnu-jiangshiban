package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.StudentInfo
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.StudentDetailPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 学生基本信息仓库。
 *
 * 按 userNum（学号 base64）缓存，避免在搜索 → 详情 → 返回 → 再点详情时重复 GET。
 * 退登需要清空。
 */
class StudentDetailRepository {

    // 缓存用 map intrinsic monitor 保护，独立于 coroutine [mutex]，好让 [clearCache] 无锁清空——
    // 避免 repo.mutex ⇄ authMutex 跨锁死锁（见 GradeRepository.clearCache）。
    private val cache = mutableMapOf<String, StudentInfo>()
    private val mutex = Mutex()

    suspend fun fetch(userNum: String): StudentInfo = mutex.withLock {
        synchronized(cache) { cache[userNum] }?.let { return@withLock it }
        val html = JwcClient.getHtmlAuth(
            JxnuUrls.studentDetailUrl(userNum),
            "学生信息页返回空响应",
        )
        val parsed = StudentDetailPage.parse(html)
        synchronized(cache) { cache[userNum] = parsed }
        parsed
    }

    /** 退登清空。**无锁、非 suspend**（只锁 map monitor），避免 repo.mutex ⇄ authMutex 跨锁死锁。 */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        val instance: StudentDetailRepository by lazy { StudentDetailRepository() }
    }
}
