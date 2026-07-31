package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.AnnouncementPage
import cn.jxnu.nvzhuanban.data.network.pages.PictureNewsPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 教务通知 / 通告 / 教务风采 / 图文新闻 Repository。
 *
 * 四个数据源（[AnnouncementType.NOTIFICATION] / [AnnouncementType.BULLETIN] /
 * [AnnouncementType.SHOWCASE] / [AnnouncementType.PICTURE_NEWS]）合并按日期降序，在同一个列表里展示。
 *
 * - 通知 / 通告 / 风采：`Portal/ArticlesList.aspx?type={Jwtz|Jwgg|Jxfc}`，每页 10 条；
 * - 图文新闻：`Portal/ArticlesPictureNews.aspx?page=N`，每页 9 条，每项带 240×180 缩略图。
 *
 * 四个都是公开页，不需要登录态；但仍走 [JwcClient] 复用 UA/超时配置。
 *
 * ## 第 1 页 = 两阶段流式
 *
 * 图文新闻接口（HTML + 9 张缩略图）比其它几路慢，并发拉取下整页首屏取决于它。
 * 这里把第 1 页拆成两阶段：
 *   1. **partial** = 通知 + 通告 + 风采（走同一个 ArticlesList 端点，同命运）合并按日期降序，先发出去；
 *   2. **full**    = partial + 图文新闻 合并，晚 1~2 秒补齐。
 *
 * 调用方可通过 [firstPageFlow] 订阅两阶段，也可通过 [fetchAll]`(1)` 只等终值。
 * 同帧并发请求复用同一个 [inflight] 对象，省一次 HTTP。
 *
 * ## 风采的「静默回退」防线
 *
 * ArticlesList.aspx 对**无效 type 不报错**，而是静默回退到教务通知(Jwtz)全量列表。
 * 若教务网将来撤掉 Jxfc 栏目，风采那路会开始返回与通知完全相同的条目（仅 type 不同），
 * 列表会成双出现。所以合并前把风采里与通知 / 通告同 id 的条目丢弃（[dedupeShowcase]）——
 * 正常情况下同一篇文章本就不该同时挂两个栏目，该过滤恒安全。
 *
 * ## latestList 写入语义
 *
 * - partial 完成时写入 [_latestList]（部分降级，给红点提供「至少看到通知+通告+风采」）
 * - full 完成时覆盖 [_latestList]
 * - partial 失败 → 不写；full 失败但 partial 已写 → 保留 partial
 *
 * ## 分页 (page >= 2)
 *
 * 翻页没有流式必要：直接 4 路并发一次返回（[fetchPageInternal]）。
 */
class AnnouncementRepository {

    private val _latestList = MutableStateFlow<List<Announcement>>(emptyList())

    /**
     * 最近一次拉取到的「第 1 页」结果。供 App 启动期预热 + 底部导航红点逻辑订阅。
     * 第 1 页流式时：partial 完成先写，full 完成再覆盖；任一阶段失败不回退已写值。
     * 分页 loadMore 不会写这里。
     */
    val latestList: StateFlow<List<Announcement>> = _latestList.asStateFlow()

    /** 两阶段进度：partial 完成 → 通知+通告 ready；full 完成 → 三路全量 ready。 */
    private class Inflight(
        val partial: CompletableDeferred<List<Announcement>>,
        val full: CompletableDeferred<List<Announcement>>,
    )

    @Volatile
    private var inflight: Inflight? = null
    private val inflightMutex = Mutex()

    /**
     * 仓库级 scope，托管 inflight 的 launch。
     * 不挂在调用方的 viewModelScope 上 —— 调用方一旦取消，其他等待该 Deferred 的协程也会失败。
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 构造单个 type 对应的列表页 URL。教务网约定：缺省 page 等价于 page=1（仅 ArticlesList 适用）；
     * ArticlesPictureNews 一直显式带 page。
     */
    private fun pageUrl(type: AnnouncementType, page: Int): String = buildString {
        append(JxnuUrls.JWC_BASE)
        when (type) {
            AnnouncementType.NOTIFICATION -> {
                append("/Portal/ArticlesList.aspx?type=Jwtz")
                if (page > 1) append("&page=").append(page)
            }
            AnnouncementType.BULLETIN -> {
                append("/Portal/ArticlesList.aspx?type=Jwgg")
                if (page > 1) append("&page=").append(page)
            }
            AnnouncementType.SHOWCASE -> {
                append("/Portal/ArticlesList.aspx?type=Jxfc")
                if (page > 1) append("&page=").append(page)
            }
            AnnouncementType.PICTURE_NEWS -> {
                append("/Portal/ArticlesPictureNews.aspx?page=").append(page)
            }
        }
    }

    private suspend fun fetchOne(type: AnnouncementType, page: Int): List<Announcement> = withContext(Dispatchers.IO) {
        val url = pageUrl(type, page)
        when (type) {
            AnnouncementType.NOTIFICATION, AnnouncementType.BULLETIN, AnnouncementType.SHOWCASE -> {
                val html = JwcClient.getHtml(url, "通知列表页返回空响应")
                AnnouncementPage.parse(html, type)
            }
            AnnouncementType.PICTURE_NEWS -> {
                val html = JwcClient.getHtml(url, "图文新闻列表页返回空响应")
                // 把请求 URL 作为 baseUrl 传给解析器，让 img src 的相对路径能解析为绝对路径。
                PictureNewsPage.parse(html, url)
            }
        }
    }

    /**
     * 无效 type 静默回退防线（详见类 KDoc）：丢掉风采里与通知 / 通告同 id 的条目。
     * 正常数据下同一篇文章不会同时挂两个栏目，该过滤是恒安全的空操作。
     */
    private fun dedupeShowcase(
        showcase: List<Announcement>,
        others: List<Announcement>,
    ): List<Announcement> {
        if (showcase.isEmpty()) return showcase
        val seenIds = others.mapTo(HashSet()) { it.id }
        return showcase.filter { it.id !in seenIds }
    }

    /**
     * 第 1 页两阶段流式拉取。事件序列：
     *   1. partial: 通知 + 通告 + 风采（best-effort）合并；
     *   2. full:    partial + 图文新闻 合并。
     *
     * 部分失败语义：
     *   - 通知 / 通告 任一失败 → 阶段 1 抛异常，整体失败；
     *   - 风采失败 → 当空列表，不拖垮 partial（与类 KDoc 的 best-effort 约定一致）；
     *   - 通知 / 通告 成功但图文失败 → 阶段 1 正常 emit，阶段 2 抛异常。
     *     调用方可在 `catch` 里保留 partial UI（已经渲染）只静默 log 错误。
     *
     * 同帧多个调用方共享同一个 [inflight] 对象。失败完成后引用清空，下一次调用会重新发起。
     */
    fun firstPageFlow(): Flow<List<Announcement>> = flow {
        val inf = obtainInflight()
        emit(inf.partial.await())
        emit(inf.full.await())
    }

    private suspend fun obtainInflight(): Inflight = inflightMutex.withLock {
        inflight ?: createInflight().also { inflight = it }
    }

    private fun createInflight(): Inflight {
        val partialDef = CompletableDeferred<List<Announcement>>()
        val fullDef = CompletableDeferred<List<Announcement>>()
        val handle = Inflight(partialDef, fullDef)
        repoScope.launch {
            try {
                runInflight(partialDef, fullDef)
            } finally {
                // 调用方取消不会影响这里（repoScope 是 SupervisorJob），
                // 但 NonCancellable 兜底确保 inflight 引用一定被清。
                withContext(NonCancellable) {
                    inflightMutex.withLock {
                        if (inflight === handle) inflight = null
                    }
                }
            }
        }
        return handle
    }

    private suspend fun runInflight(
        partialDef: CompletableDeferred<List<Announcement>>,
        fullDef: CompletableDeferred<List<Announcement>>,
    ) {
        // supervisorScope：子 async 抛异常不传播给父 scope；我们用 try/catch 各自接住，
        // 决定是 partial fail 还是 full fail。
        supervisorScope {
            val notif = async { fetchOne(AnnouncementType.NOTIFICATION, 1) }
            val bull = async { fetchOne(AnnouncementType.BULLETIN, 1) }
            val show = async { fetchOne(AnnouncementType.SHOWCASE, 1) }
            val pics = async { fetchOne(AnnouncementType.PICTURE_NEWS, 1) }

            val partial = try {
                // base = 通知 + 通告，是 partial 的硬依赖（任一失败则首页 feed 失败）。
                val base = notif.await() + bull.await()
                // 风采是 best-effort：它虽与通知/通告同 ArticlesList 端点，但独立 socket，
                // 单路瞬时失败（读超时/连接重置/空响应）不该拖垮整张首页 feed——风采是四类里
                // 最不重要的一类。失败当空列表，与图文在 full 阶段的降级待遇对齐。
                val showcase = runCatching { show.await() }.getOrDefault(emptyList())
                (base + dedupeShowcase(showcase, base)).sortedByDescending { it.date }
            } catch (t: Throwable) {
                // 通知/通告失败：partial 和 full 一起失败；图文与风采那两路取消省连接资源
                show.cancel()
                pics.cancel()
                partialDef.completeExceptionally(t)
                fullDef.completeExceptionally(t)
                return@supervisorScope
            }
            partialDef.complete(partial)
            // partial 也写一次 latestList：部分降级，给红点订阅一个能用的列表
            _latestList.value = partial

            val full = try {
                (partial + pics.await()).sortedByDescending { it.date }
            } catch (t: Throwable) {
                fullDef.completeExceptionally(t)
                return@supervisorScope
            }
            fullDef.complete(full)
            _latestList.value = full
        }
    }

    /**
     * 拉取指定页。
     * - page == 1：等流式终值（含图文）；同帧并发复用 inflight。
     * - page >= 2：4 路并发一次返回（[fetchPageInternal]）。
     */
    suspend fun fetchAll(page: Int = 1): List<Announcement> {
        if (page == 1) return obtainInflight().full.await()
        return fetchPageInternal(page)
    }

    private suspend fun fetchPageInternal(page: Int): List<Announcement> = supervisorScope {
        // supervisorScope：风采子路失败不取消兄弟协程，便于对它单独 best-effort。
        val notif = async { fetchOne(AnnouncementType.NOTIFICATION, page) }
        val bull = async { fetchOne(AnnouncementType.BULLETIN, page) }
        val show = async { fetchOne(AnnouncementType.SHOWCASE, page) }
        val pics = async { fetchOne(AnnouncementType.PICTURE_NEWS, page) }
        // 通知/通告/图文任一失败 → 整页失败（loadMore 静默保留旧数据）；
        // 风采与首页一致做 best-effort：单路失败当空列表，不拖垮翻页。
        val base = notif.await() + bull.await()
        val picsList = pics.await()
        val showcase = runCatching { show.await() }.getOrDefault(emptyList())
        (base + dedupeShowcase(showcase, base) + picsList).sortedByDescending { it.date }
    }

    /** 退出登录时清空 latestList，避免下一用户在通知 tab 之前看到上一用户的预热列表。 */
    fun clearCache() {
        _latestList.value = emptyList()
    }

    companion object {
        val instance: AnnouncementRepository by lazy { AnnouncementRepository() }
    }
}
