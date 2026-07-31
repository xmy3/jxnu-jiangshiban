package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.CalendarPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * 教学周历（校历）仓库。
 *
 * 数据是 jwc 上的纯静态索引页，更新极慢（一学期才会新增一项）。模式照搬 [ExamRepository]：
 * 单例 + Mutex + `@Volatile cached` + `fetchAll` / `refresh` / `clearCache`。
 *
 * 唯一特殊点：必须显式以 GBK 解码。教务网这页只在 `<meta http-equiv>` 里声明 charset，
 * HTTP 头里没有 charset，OkHttp 的 `response.body.string()` 会按默认 UTF-8 解码 → 全文乱码。
 * 所以这里走 [JwcClient.getBytes] 拿字节流，再手工 `String(bytes, GBK)`。
 *
 * 公开页，不需要 session cookie，也不需要 `getHtmlAuth` 那套会话过期重登逻辑。
 */
class CalendarRepository {

    @Volatile
    private var cached: List<CalendarEntry>? = null
    private val mutex = Mutex()

    suspend fun fetchAll(): List<CalendarEntry> = mutex.withLock {
        cached ?: fetchNow().also { cached = it }
    }

    suspend fun refresh(): List<CalendarEntry> = mutex.withLock {
        cached = null
        fetchNow().also { cached = it }
    }

    // 必须无锁、非 suspend（cached 是 @Volatile）：一旦日后被接进 AuthRepository 持有
    // authMutex 的清理链，加锁版会复活 repo.mutex ⇄ authMutex 跨锁死锁（见 CLAUDE.md）。
    fun clearCache() {
        cached = null
    }

    private suspend fun fetchNow(): List<CalendarEntry> = withContext(Dispatchers.IO) {
        val bytes = JwcClient.getBytes(JxnuUrls.PAGE_CALENDAR_INDEX, "校历索引页返回空响应")
        val html = String(bytes, GBK)
        CalendarPage.parse(html, JxnuUrls.PAGE_CALENDAR_INDEX)
    }

    companion object {
        private val GBK: Charset = Charset.forName("GBK")
        val instance: CalendarRepository by lazy { CalendarRepository() }
    }
}
