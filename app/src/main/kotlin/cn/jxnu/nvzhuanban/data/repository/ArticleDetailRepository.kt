package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.ReauthOutcome
import cn.jxnu.nvzhuanban.data.network.SessionRecovery
import cn.jxnu.nvzhuanban.data.network.pages.ArticleDetailPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 教务通知正文页 Repository。
 *
 * 数据来源：`Portal/ArticlesView.aspx?id=<id>`。详情页 **大多需要登录**（虽然列表是公开的），
 * 所以 fetch 走 [JwcClient.getHtmlAuth]——session 失效时静默 reauth 重放一次。
 *
 * 缓存策略：进程内 LRU（最多 16 篇）。用户从列表点进去 → 返回 → 再点同一篇，命中缓存秒开；
 * 16 上限是「同一通知 tab 一次浏览深度」的经验值，超过这个量内存占用也仍可忽略。
 * [refresh] 强制旁路缓存；列表退出登录时由调用方触发 [clearCache]。
 */
class ArticleDetailRepository {

    private val mutex = Mutex()
    // 缓存本身用 map 自己的 intrinsic monitor 保护（synchronized(cache)），**独立于** coroutine [mutex]。
    // 这样 [clearCache] 能不经 [mutex]、也不 suspend 地安全清空——否则 clearCache 走 mutex.withLock
    // 会和「fetch 持 mutex 期间经 getHtmlAuth → reauth 抢 authMutex」构成 repo.mutex ⇄ authMutex 跨锁死锁。
    private val cache = object : LinkedHashMap<String, ArticleDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArticleDetail>?): Boolean =
            size > MAX_CACHE_SIZE
    }

    suspend fun fetch(articleId: String): ArticleDetail = mutex.withLock {
        synchronized(cache) { cache[articleId] }?.let { return@withLock it }
        val detail = fetchRemote(articleId)
        // 不缓存"需要登录"占位页：否则用户去登录回来再点同一篇会命中旧占位，永远看不到正文。
        if (!detail.requiresLogin) synchronized(cache) { cache[articleId] = detail }
        detail
    }

    suspend fun refresh(articleId: String): ArticleDetail = mutex.withLock {
        val detail = fetchRemote(articleId)
        synchronized(cache) {
            if (!detail.requiresLogin) cache[articleId] = detail else cache.remove(articleId)
        }
        detail
    }

    /**
     * 退出登录时清空缓存。**无锁、非 suspend**（只锁 map 自己的 monitor，绝不碰 coroutine [mutex]）——
     * 避免 repo.mutex ⇄ authMutex 跨锁死锁（见 GradeRepository.clearCache）。缓存内容是按全局
     * 文章 id 键控的公开通知正文，最差残留一条也非隐私泄露，下次 refresh 自愈。
     */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private suspend fun fetchRemote(articleId: String): ArticleDetail =
        withContext(Dispatchers.IO) {
            val url = articleUrl(articleId)
            val detail = ArticleDetailPage.parse(
                JwcClient.getHtmlAuth(url, "通知详情页返回空响应"),
                url,
            )
            // 「需要登录」占位页是 HTTP 200 / 同域 / 不重定向、页内只有一条登录外链，
            // JwcResponseGuard 不会把它当 SessionExpired，所以 getHtmlAuth 的自动恢复没触发。
            // 这里主动静默重登一次再重取。三态分流（别机械地按 !=Success 一律引导登录）：
            //  - Success：cookie 已新鲜，raw 重取正文（仍占位则照常引导登录）。
            //  - Transient：网络/环境性失败——抛可重试网络错误，让详情页显示错误态而非「去登录」误导。
            //  - AuthRejected：无凭证 / 密码已改——返回占位页，UI 渲染「去登录」引导。
            if (!detail.requiresLogin) return@withContext detail
            when (SessionRecovery.reauth()) {
                ReauthOutcome.Success -> ArticleDetailPage.parse(
                    JwcClient.getHtml(url, "通知详情页返回空响应"),
                    url,
                )
                ReauthOutcome.Transient -> throw JwcException(JwcError.Network())
                ReauthOutcome.AuthRejected -> detail
            }
        }

    private fun articleUrl(id: String) = "${JxnuUrls.JWC_BASE}/Portal/ArticlesView.aspx?id=$id"

    companion object {
        private const val MAX_CACHE_SIZE = 16
        val instance: ArticleDetailRepository by lazy { ArticleDetailRepository() }
    }
}
