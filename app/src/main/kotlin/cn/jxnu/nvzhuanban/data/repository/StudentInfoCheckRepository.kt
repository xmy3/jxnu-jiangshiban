package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.StudentBasicInfo
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.StudentInfoCheckPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本人学籍/身份信息仓库（`Student_InforCheck.aspx`）。整页一次返回，内存缓存单槽，退登清空。
 */
class StudentInfoCheckRepository {

    @Volatile
    private var cached: StudentBasicInfo? = null
    private val mutex = Mutex()

    suspend fun fetch(): StudentBasicInfo = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    suspend fun refresh(): StudentBasicInfo = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    private suspend fun fetchNow(): StudentBasicInfo {
        val html = JwcClient.getHtmlAuth(JxnuUrls.PAGE_STUDENT_INFO, "学籍信息页返回空响应")
        return StudentInfoCheckPage.parse(html)
    }

    /** 退登清空。**无锁、非 suspend**（@Volatile 直置 null），避免 repo.mutex ⇄ authMutex 跨锁死锁。 */
    fun clearCache() {
        cached = null
    }

    companion object {
        val instance: StudentInfoCheckRepository by lazy { StudentInfoCheckRepository() }
    }
}
