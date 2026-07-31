package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
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
) {
    /** 当前已加载（未过滤）的总条目数；给搜索为空时的空状态文案用。 */
    val loadedCount: Int
        get() = (data as? UiState.Success)?.data?.size ?: 0

    /** 是否处于搜索态（trim 后非空）。 */
    val isSearching: Boolean
        get() = searchQuery.isNotBlank()

    /**
     * UI 实际渲染用的列表：先按类型 chip 过滤，再按搜索关键词在 title 上做不区分大小写的子串过滤。
     * 非 Success 时返回空。两段 filter 是 AND 关系，互不干扰。
     */
    val visibleList: List<Announcement>
        get() {
            val raw = (data as? UiState.Success)?.data ?: return emptyList()
            val byType = when (filter) {
                AnnouncementFilter.ALL -> raw
                AnnouncementFilter.NOTIFICATION -> raw.filter { it.type == AnnouncementType.NOTIFICATION }
                AnnouncementFilter.BULLETIN -> raw.filter { it.type == AnnouncementType.BULLETIN }
                AnnouncementFilter.SHOWCASE -> raw.filter { it.type == AnnouncementType.SHOWCASE }
                AnnouncementFilter.PICTURE_NEWS -> raw.filter { it.type == AnnouncementType.PICTURE_NEWS }
            }
            val q = searchQuery.trim()
            return if (q.isEmpty()) byType else byType.filter { it.title.contains(q, ignoreCase = true) }
        }
}

class AnnouncementViewModel(
    private val firstPageFlow: () -> Flow<List<Announcement>> = {
        AnnouncementRepository.instance.firstPageFlow()
    },
    private val fetchPage: suspend (Int) -> List<Announcement> = {
        AnnouncementRepository.instance.fetchAll(page = it)
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

    /** 当前已加载到的最后一页（从 1 开始）。下拉刷新时回到 1。 */
    private var currentPage: Int = 1
    private var loadMoreJob: Job? = null
    private var loadMoreRequestId: Long = 0L
    /** 上一次 loadMore 失败的时间戳。短期冷却（5s）内不再自动重试，避免反复滚动刷屏式打教务网。 */
    private var lastLoadMoreFailureAt: Long = 0L
    private val loadMoreCooldownMs: Long = 5_000L

    init { load() }

    /**
     * 首次加载 / Error 重试。两阶段订阅 [AnnouncementRepository.firstPageFlow]：
     *   - 中间值（通知 + 通告）到达：state → Success(partial)，列表立刻可见；
     *   - 终值（含图文新闻）到达：state → Success(full)，并写 anchor 记为已读。
     *
     * 失败语义：
     *   - partial 都没拿到 → state 切 Error；
     *   - partial 已展示后图文失败 → 静默保留 partial UI，anchor 不写
     *     （等下次进 tab 或刷新时再试图拿全量）。
     */
    fun load() {
        cancelLoadMore()
        _state.value = _state.value.copy(data = UiState.Loading, hasMore = true)
        currentPage = 1
        lastLoadMoreFailureAt = 0L
        viewModelScope.launch {
            var sawAny = false
            var fullSeen = false
            try {
                firstPageFlow().collect { list ->
                    sawAny = true
                    _state.value = _state.value.copy(
                        data = UiState.Success(list),
                        hasMore = list.isNotEmpty(),
                    )
                }
                // collect 自然结束 = full 已经 emit 过
                fullSeen = true
            } catch (t: Throwable) {
                if (!sawAny) {
                    _state.value = _state.value.copy(data = UiState.Error(t.toUserMessage()))
                }
                // 否则:partial 已展示,图文那一路挂了,静默保留 partial UI
            }
            if (fullSeen) {
                val s = _state.value.data
                if (s is UiState.Success) markTopAsRead(s.data)
            }
        }
    }

    /**
     * 下拉刷新。也走流式：partial 一来立刻覆盖列表（用户感受到刷新立刻起效），
     * full 来了再合并一次。anchor 仍只在终值时写。
     */
    fun refresh() {
        if (_isRefreshing.value) return
        cancelLoadMore()
        _isRefreshing.value = true
        viewModelScope.launch {
            var fullSeen = false
            try {
                firstPageFlow().collect { list ->
                    currentPage = 1
                    lastLoadMoreFailureAt = 0L  // 显式刷新清掉冷却
                    _state.value = _state.value.copy(
                        data = UiState.Success(list),
                        hasMore = list.isNotEmpty(),
                    )
                }
                fullSeen = true
            } catch (t: Throwable) {
                // 失败保留旧 state(若 partial 已 emit 则保留 partial)，但要弹一次性提示——
                // 转圈停了会被误读成"刷新完成、列表就是最新"
                _refreshFailed.tryEmit(t.toUserMessage("刷新失败"))
            } finally {
                _isRefreshing.value = false
            }
            if (fullSeen) {
                val s = _state.value.data
                if (s is UiState.Success) markTopAsRead(s.data)
            }
        }
    }

    /**
     * 加载下一页。前置条件：当前 data 是 Success 且 hasMore=true，且无并发刷新/加载。
     * 拉到空页则把 hasMore 置 false 不再追加。
     */
    fun loadMore() {
        val s = _state.value
        if (!s.hasMore) return
        if (_isLoadingMore.value || _isRefreshing.value) return
        // 失败冷却：上次 loadMore 抛错后 5s 内不再自动重试，避免用户滚动反复打教务网
        if (System.currentTimeMillis() - lastLoadMoreFailureAt < loadMoreCooldownMs) return
        val current = (s.data as? UiState.Success)?.data ?: return

        val requestId = ++loadMoreRequestId
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            try {
                val nextPage = currentPage + 1
                val more = fetchPage(nextPage)
                if (requestId != loadMoreRequestId) return@launch
                if (more.isEmpty()) {
                    _state.value = _state.value.copy(hasMore = false)
                } else {
                    currentPage = nextPage
                    // 去重：教务网偶发两页之间数据回流；按 (type, id) 去重
                    val merged = (current + more)
                        .distinctBy { "${it.type}_${it.id}" }
                        .sortedByDescending { it.date }
                    _state.value = _state.value.copy(data = UiState.Success(merged))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 加载更多失败：静默保留并打上冷却时间戳；用户继续滚动 5s 内不再自动重试，
                // 避免一个失败的网络状况反复打教务网。
                if (requestId == loadMoreRequestId) {
                    lastLoadMoreFailureAt = System.currentTimeMillis()
                }
            } finally {
                if (requestId == loadMoreRequestId) {
                    _isLoadingMore.value = false
                    loadMoreJob = null
                }
            }
        }
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

    /**
     * 把当前列表「按日期降序后的第一条」记成已读锚点。
     * 仅在用户实际看到的「第 1 页终值（含图文）」可用时调用；
     * partial 阶段不写、loadMore 不写，避免红点逻辑被中间状态污染。
     */
    private fun markTopAsRead(list: List<Announcement>) {
        list.firstOrNull()?.let { AnnouncementReadAnchor.setAnchor(it.uniqueKey) }
    }
}
