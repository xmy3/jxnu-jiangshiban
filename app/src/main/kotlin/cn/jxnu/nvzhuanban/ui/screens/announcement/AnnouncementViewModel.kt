package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadMarker
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 列表过滤：「全部」=四种都看，否则只看选中的那一种。 */
enum class AnnouncementFilter { ALL, NOTIFICATION, BULLETIN, SHOWCASE, PICTURE_NEWS }

data class AnnouncementScreenState(
    val data: UiState<List<Announcement>> = UiState.Loading,
    val filter: AnnouncementFilter = AnnouncementFilter.ALL,
    /** 搜索关键词。空 / 全空白 = 不搜索。仅在标题里 substring 匹配，忽略大小写。 */
    val searchQuery: String = "",
    /** 还有更多数据可加载；fetch 到空页时翻为 false。 */
    val hasMore: Boolean = true,
    /**
     * 本次打开列表以来自「上次已读位置」之后新增的条目 key（`type:id`）。
     * 进入 tab 时按已读锚点算一次快照，用户点开某条后从集合里移除——
     * 不会因为同一次加载结束写回锚点就瞬间全部消失。
     */
    val unreadKeys: Set<String> = emptySet(),
    /** 最近一次「加载更多」失败（旧列表仍在展示），footer 给显式重试入口。 */
    val loadMoreFailed: Boolean = false,
) {
    /** 当前已加载（未过滤）的总条目数。 */
    val loadedCount: Int
        get() = (data as? UiState.Success)?.data?.size ?: 0

    /**
     * 当前类型 chip 过滤后、**尚未应用搜索词**的条目数。
     * 搜索空态文案用它而不是 [loadedCount]：否则「图文」标签下明明只有 1 条，
     * 空态却说「已加载 16 条」，和之前不给 chip 标条数的理由自相矛盾。
     */
    val filteredCount: Int
        get() = byType.size

    /** 是否处于搜索态（trim 后非空）。 */
    val isSearching: Boolean
        get() = searchQuery.isNotBlank()

    /**
     * UI 实际渲染用的列表：先按类型 chip 过滤，再按搜索关键词在 title 上做不区分大小写的子串过滤。
     * 非 Success 时返回空。两段 filter 是 AND 关系，互不干扰。
     */
    val visibleList: List<Announcement>
        get() {
            val q = searchQuery.trim()
            return if (q.isEmpty()) byType else byType.filter { it.title.contains(q, ignoreCase = true) }
        }

    /** 只按类型 chip 过滤后的列表，搜索词不参与。 */
    private val byType: List<Announcement>
        get() {
            val raw = (data as? UiState.Success)?.data ?: return emptyList()
            return when (filter) {
                AnnouncementFilter.ALL -> raw
                AnnouncementFilter.NOTIFICATION -> raw.filter { it.type == AnnouncementType.NOTIFICATION }
                AnnouncementFilter.BULLETIN -> raw.filter { it.type == AnnouncementType.BULLETIN }
                AnnouncementFilter.SHOWCASE -> raw.filter { it.type == AnnouncementType.SHOWCASE }
                AnnouncementFilter.PICTURE_NEWS -> raw.filter { it.type == AnnouncementType.PICTURE_NEWS }
            }
        }
}

class AnnouncementViewModel(
    private val firstPageFlow: () -> Flow<List<Announcement>> = {
        AnnouncementRepository.instance.firstPageFlow()
    },
    private val fetchPage: suspend (Int) -> List<Announcement> = {
        AnnouncementRepository.instance.fetchAll(page = it)
    },
    /** 已读锚点读取入口；测试注入用。null = 首次使用（或旧版本没有日期）→ 不标未读。 */
    private val lastReadMarker: () -> AnnouncementReadMarker? = {
        AnnouncementReadAnchor.current
    },
) : ViewModel() {

    private val _state = MutableStateFlow(AnnouncementScreenState())
    val state: StateFlow<AnnouncementScreenState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** 刷新失败（旧列表仍在展示）的一次性提示，Screen 用 Snackbar 展示。 */
    private val _refreshFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshFailed: SharedFlow<String> = _refreshFailed.asSharedFlow()

    /** 加载更多失败的一次性提示（footer 同时给出重试按钮）。 */
    private val _loadMoreError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val loadMoreError: SharedFlow<String> = _loadMoreError.asSharedFlow()

    /** 当前已加载到的最后一页（从 1 开始）。下拉刷新时回到 1。 */
    private var currentPage: Int = 1
    /** 首页的 partial/full 与后续页分别保存，任一响应都不能覆盖另一部分。 */
    private var firstPageItems: List<Announcement> = emptyList()
    private var laterPageItems: List<Announcement> = emptyList()
    private var paginationExhausted = false
    private var firstPageJob: Job? = null
    private var firstPageRequestId: Long = 0L
    private var loadMoreJob: Job? = null
    private var loadMoreRequestId: Long = 0L
    /** 上一次 loadMore 失败的时间戳。短期冷却（5s）内不再自动重试，避免反复滚动刷屏式打教务网。 */
    private var lastLoadMoreFailureAt: Long = 0L
    private val loadMoreCooldownMs: Long = 5_000L

    /**
     * 本次进入列表时的已读锚点快照。
     *
     * 只在 `load()`（首次进入 / 错误重试）时取一次：加载终值会把锚点推进到新的顶部，
     * 若每次 emit 都现读，未读集合会立刻被自己写掉的锚点清空。
     * 下拉刷新**不**重新取快照——刷新期间到达的新条目仍然要能标成未读。
     */
    private var sessionAnchor: AnnouncementReadMarker? = null
    /** 本次会话里用户点开过的条目；重算未读时排除，避免刷新后红点复活。 */
    private val readKeys = mutableSetOf<String>()

    init { load() }

    /**
     * 首次加载 / Error 重试。两阶段订阅 [AnnouncementRepository.firstPageFlow]：
     *   - 中间值（通知 + 通告）到达：state → Success(partial)，列表立刻可见；
     *   - 终值（含图文新闻）到达：替换首页、保留已加载分页，并写 anchor 记为已读。
     *
     * 失败语义：
     *   - partial 都没拿到 → state 切 Error；
     *   - partial 已展示后图文失败 → 静默保留 partial UI，anchor 不写
     *     （等下次进 tab 或刷新时再试图拿全量）。
     */
    fun load() {
        startFirstPage(refresh = false)
    }

    /**
     * 下拉刷新。也走流式：partial 一来立刻覆盖列表（用户感受到刷新立刻起效），
     * full 来了再合并一次。anchor 仍只在终值时写。
     */
    fun refresh() {
        if (_isRefreshing.value) return
        startFirstPage(refresh = true)
    }

    private fun startFirstPage(refresh: Boolean) {
        val requestId = ++firstPageRequestId
        firstPageJob?.cancel()
        cancelLoadMore()
        _isRefreshing.value = refresh
        if (!refresh) {
            // 新的一次「进入」：重新取锚点快照，并忘掉上一轮点开过的条目
            sessionAnchor = lastReadMarker()
            readKeys.clear()
            firstPageItems = emptyList()
            laterPageItems = emptyList()
            paginationExhausted = false
            currentPage = 1
            lastLoadMoreFailureAt = 0L
            _state.value = _state.value.copy(
                data = UiState.Loading,
                hasMore = true,
                unreadKeys = emptySet(),
                loadMoreFailed = false,
            )
        } else {
            // 首次使用（load 时还没有任何锚点）的情况下刷新：首次加载已经把锚点写成
            // 「上一轮看到的顶部」，这里补一次快照，刷新带来的更新条目才能标成未读。
            if (sessionAnchor == null) sessionAnchor = lastReadMarker()
            _state.value = _state.value.copy(loadMoreFailed = false)
        }
        firstPageJob = viewModelScope.launch {
            var sawAny = false
            try {
                firstPageFlow().collect { list ->
                    if (requestId != firstPageRequestId) return@collect
                    if (!sawAny) {
                        // 刷新真正拿到新首页后才丢弃旧分页；失败时仍可沿旧列表继续翻页。
                        currentPage = 1
                        laterPageItems = emptyList()
                        paginationExhausted = false
                        lastLoadMoreFailureAt = 0L
                    }
                    sawAny = true
                    firstPageItems = list
                    publish(
                        data = mergedPages(),
                        // 如果 partial 期间已翻到空页，full 到达不能重新开启分页。
                        hasMore = !paginationExhausted && (list.isNotEmpty() || laterPageItems.isNotEmpty()),
                    )
                }
                // collect 自然结束 = full 已经 emit；锚点只取首页，不能取已混入的后续页。
                if (requestId == firstPageRequestId && sawAny) markTopAsRead(firstPageItems)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (requestId != firstPageRequestId) return@launch
                if (_state.value.data !is UiState.Success) {
                    _state.value = _state.value.copy(data = UiState.Error(t.toUserMessage()))
                } else if (refresh) {
                    // 刷新失败保留已展示列表，但给出提示；首次 partial 降级沿用静默语义。
                    _refreshFailed.tryEmit(t.toUserMessage("刷新失败"))
                }
            } finally {
                if (requestId == firstPageRequestId) {
                    _isRefreshing.value = false
                    firstPageJob = null
                }
            }
        }
    }

    private fun mergedPages(): List<Announcement> =
        (firstPageItems + laterPageItems)
            .distinctBy { it.uniqueKey }
            .sortedByDescending { it.date }

    /** 统一出口：数据变了就顺手重算未读集合，避免各处 copy 漏写导致红点不动。 */
    private fun publish(data: List<Announcement>, hasMore: Boolean) {
        _state.value = _state.value.copy(
            data = UiState.Success(data),
            hasMore = hasMore,
            unreadKeys = computeUnreadKeys(data),
        )
    }

    /**
     * 相对 [sessionAnchor] 推导未读条目。
     *
     * 按日期比较而不是「在列表里的下标」：partial 阶段图文还没回来，锚点可能整条不在列表里，
     * 下标方案会把整页都误判成未读。规则：
     *   - 日期晚于锚点 → 未读；
     *   - 日期等于锚点 → 只有排在锚点前面（更新）的条目算未读；锚点本身与它后面的都算已读；
     *   - 日期更早 → 已读。
     * 用户点开过的条目在 [readKeys] 里，永不再标未读。
     */
    private fun computeUnreadKeys(list: List<Announcement>): Set<String> {
        val anchor = sessionAnchor ?: return emptySet()
        val anchorIndex = list.indexOfFirst { it.uniqueKey == anchor.uniqueKey }
        val unread = LinkedHashSet<String>()
        list.forEachIndexed { index, announcement ->
            val isNewer = when {
                announcement.date.isAfter(anchor.date) -> true
                announcement.date == anchor.date -> anchorIndex >= 0 && index < anchorIndex
                else -> false
            }
            if (isNewer && announcement.uniqueKey !in readKeys) unread += announcement.uniqueKey
        }
        return unread
    }

    /**
     * 加载下一页。当前 data 是 Success 且 hasMore=true、无并发刷新/翻页即可；首页 partial
     * 展示后便允许分页，后续 full 到达只更新首页部分。
     * 拉到空页则把 hasMore 置 false 不再追加。
     */
    fun loadMore() {
        val s = _state.value
        if (!s.hasMore) return
        if (_isLoadingMore.value || _isRefreshing.value) return
        // 失败冷却：上次 loadMore 抛错后 5s 内不再自动重试，避免用户滚动反复打教务网
        if (System.currentTimeMillis() - lastLoadMoreFailureAt < loadMoreCooldownMs) return
        if (s.data !is UiState.Success) return

        val requestId = ++loadMoreRequestId
        _isLoadingMore.value = true
        _state.value = _state.value.copy(loadMoreFailed = false)
        loadMoreJob = viewModelScope.launch {
            try {
                val nextPage = currentPage + 1
                val more = fetchPage(nextPage)
                if (requestId != loadMoreRequestId) return@launch
                if (more.isEmpty()) {
                    paginationExhausted = true
                    publish(data = mergedPages(), hasMore = false)
                } else {
                    currentPage = nextPage
                    // 去重：教务网偶发两页之间数据回流；按 (type, id) 去重
                    laterPageItems = (laterPageItems + more).distinctBy { it.uniqueKey }
                    publish(data = mergedPages(), hasMore = !paginationExhausted)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 加载更多失败：静默保留旧列表 + 打上冷却时间戳；footer 转成「点击重试」，
                // 用户继续滚动 5s 内不再自动重试，避免一个失败的网络状况反复打教务网。
                if (requestId == loadMoreRequestId) {
                    lastLoadMoreFailureAt = System.currentTimeMillis()
                    _state.value = _state.value.copy(loadMoreFailed = true)
                    _loadMoreError.tryEmit(t.toUserMessage("加载更多失败"))
                }
            } finally {
                if (requestId == loadMoreRequestId) {
                    _isLoadingMore.value = false
                    loadMoreJob = null
                }
            }
        }
    }

    /** footer 上的显式重试：绕过 5s 自动重试冷却（用户主动点了，就该真的再打一次）。 */
    fun retryLoadMore() {
        lastLoadMoreFailureAt = 0L
        loadMore()
    }

    private fun cancelLoadMore() {
        loadMoreRequestId += 1
        loadMoreJob?.cancel()
        loadMoreJob = null
        _isLoadingMore.value = false
    }

    fun selectFilter(f: AnnouncementFilter) {
        if (_state.value.filter == f) return
        _state.value = _state.value.copy(filter = f)
    }

    /** 更新搜索关键词。空字符串 = 退出搜索态。 */
    fun setSearchQuery(q: String) {
        if (_state.value.searchQuery == q) return
        _state.value = _state.value.copy(searchQuery = q)
    }

    /** 用户点开某条：立刻消掉它自己的未读点，并记住这次会话里已读过。 */
    fun markItemRead(uniqueKey: String) {
        if (uniqueKey !in _state.value.unreadKeys) return
        readKeys += uniqueKey
        _state.value = _state.value.copy(unreadKeys = _state.value.unreadKeys - uniqueKey)
    }

    /**
     * 把当前列表「按日期降序后的第一条」记成已读锚点。
     * 仅在用户实际看到的「第 1 页终值（含图文）」可用时调用；
     * partial 阶段不写、loadMore 不写，避免红点逻辑被中间状态污染。
     */
    private fun markTopAsRead(list: List<Announcement>) {
        list.firstOrNull()?.let { AnnouncementReadAnchor.setAnchor(it.uniqueKey, it.date) }
    }
}
