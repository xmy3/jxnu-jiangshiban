package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar
import cn.jxnu.nvzhuanban.ui.theme.AppShape
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementScreen(
    onItemClick: (Announcement) -> Unit,
    viewModel: AnnouncementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()

    // 搜索栏开关；与 ViewModel 的 searchQuery 解耦：
    // 用户可以打开搜索栏但没输入（query 仍为空）— 此时列表展示不变。
    // rememberSaveable：底部导航 saveState=true 切走再回来时,searchQuery 在 VM 里仍保留,
    // 这里必须一起保留 isSearchActive 否则会出现"搜索框消失但列表仍按 query 过滤"的迷惑态。
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    fun exitSearch() {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    // 搜索态下按返回先退出搜索，而不是离开整个 tab
    BackHandler(enabled = isSearchActive) { exitSearch() }

    // 打开搜索栏时自动聚焦弹键盘
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchFocus.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(rememberTransientErrorSnackbar(viewModel.refreshFailed)) },
        topBar = {
            if (isSearchActive) {
                SearchTopBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClose = ::exitSearch,
                    focusRequester = searchFocus,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.announcement_title)) },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.announcement_search),
                            )
                        }
                        RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
            FilterRow(current = state.filter, onSelect = viewModel::selectFilter)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
            ) {
                StateScaffold(
                    state = state.data,
                    onRetry = viewModel::load,
                    loading = { m ->
                        cn.jxnu.nvzhuanban.ui.components.ListSkeleton(modifier = m, showThumbEvery = 3)
                    },
                ) {
                    // remember：同输入返回同实例。visibleList 是计算属性，过滤态下每次读取都
                    // 重新 filter 并分配新 List，isRefreshing/isLoadingMore 翻转这类无关重组
                    // 会让 AnnouncementList 的 list 参数引用不等价、破坏 skip。
                    val list = remember(state.data, state.filter, state.searchQuery) { state.visibleList }
                    if (list.isEmpty()) {
                        when {
                            // 搜索没找到：单独一屏，包含「加载更早的通知继续搜索」按钮（hasMore 时），
                            // 让用户在已加载页内没匹配时仍能扩大搜索范围
                            state.isSearching -> SearchEmpty(
                                loadedCount = state.loadedCount,
                                query = state.searchQuery.trim(),
                                hasMore = state.hasMore,
                                isLoadingMore = isLoadingMore,
                                onLoadMore = viewModel::loadMore,
                            )
                            state.filter != AnnouncementFilter.ALL -> EmptyState("当前分类暂无内容")
                            else -> EmptyState(stringResource(R.string.announcement_empty))
                        }
                    } else {
                        AnnouncementList(
                            list = list,
                            filterKey = state.filter,
                            isSearching = state.isSearching,
                            hasMore = state.hasMore,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = viewModel::loadMore,
                            onItemClick = onItemClick,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    // 用 BasicTextField 而不是 TextField：TopAppBar 的 title slot 高度有限，
    // TextField 自带的 label/indicator 撑不开会被裁。BasicTextField + 自渲染 placeholder 足够。
    TopAppBar(
        title = {
            val textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.announcement_search_hint),
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        },
        navigationIcon = { BackNavigationIcon(onClose) },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.announcement_search_clear),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun SearchEmpty(
    loadedCount: Int,
    query: String,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.announcement_search_empty_template, loadedCount, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hasMore) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onLoadMore, enabled = !isLoadingMore) {
                Text(
                    text = if (isLoadingMore) "加载中…"
                    else stringResource(R.string.announcement_search_load_more),
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    current: AnnouncementFilter,
    onSelect: (AnnouncementFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 5 个 chip 在 360dp 屏上放不下，靠横向滚动兜底
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(label = "全部", selected = current == AnnouncementFilter.ALL) {
            onSelect(AnnouncementFilter.ALL)
        }
        FilterPill(label = "通知", selected = current == AnnouncementFilter.NOTIFICATION) {
            onSelect(AnnouncementFilter.NOTIFICATION)
        }
        FilterPill(label = "通告", selected = current == AnnouncementFilter.BULLETIN) {
            onSelect(AnnouncementFilter.BULLETIN)
        }
        FilterPill(label = "风采", selected = current == AnnouncementFilter.SHOWCASE) {
            onSelect(AnnouncementFilter.SHOWCASE)
        }
        FilterPill(label = "图文", selected = current == AnnouncementFilter.PICTURE_NEWS) {
            onSelect(AnnouncementFilter.PICTURE_NEWS)
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun AnnouncementList(
    list: List<Announcement>,
    filterKey: AnnouncementFilter,
    isSearching: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (Announcement) -> Unit,
) {
    val listState = rememberLazyListState()

    // 切换过滤器 / 进出搜索态时把滚动位置归零，否则 lastVisible 还停在上一次的 index：
    // 切到一个只有少量条目的分类会立刻触发 derivedStateOf → loadMore，发起多余请求。
    // snapshotFlow + drop(1)：只在值真正变化时归零。若直接 LaunchedEffect(filterKey, isSearching)，
    // 从详情页返回 / 底部导航切回时 effect 重新进组合会重跑，把刚恢复的滚动位置冲回顶部。
    val currentFilter by rememberUpdatedState(filterKey)
    val currentSearching by rememberUpdatedState(isSearching)
    LaunchedEffect(listState) {
        snapshotFlow { currentFilter to currentSearching }
            .drop(1)
            .collect { listState.scrollToItem(0) }
    }

    // 滚到距底部 3 项时自动 loadMore。derivedStateOf 把派生计算挂在 listState 上，
    // 只在 layoutInfo 变化时才重算，避免每帧重组。只读 listState.layoutInfo（快照状态），
    // 不捕获 list 参数——无 key 的 remember 会把 list 引用冻结在首次组合，
    // loadMore 追加 / 切换过滤器后阈值就永远停在旧的 size 上。
    // 搜索态下禁用：搜词冷门时 visibleList 可能很短，自动触发会把所有页都抓回来，
    // 太激进。改用底部「加载更早的通知继续搜索」按钮显式 opt-in。
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            // totalItemsCount 含底部 footer（占 1 项），-4 等效于距数据末尾留 3 项裕度
            lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore, isSearching) {
        if (!isSearching && shouldLoadMore && hasMore && !isLoadingMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { "${it.type}_${it.id}" }) { a ->
            AnnouncementCard(a, onItemClick, modifier = Modifier.animateItem())
        }
        item(key = "__footer__") {
            ListFooter(
                hasMore = hasMore,
                isLoadingMore = isLoadingMore,
                isSearching = isSearching,
                onLoadMore = onLoadMore,
            )
        }
    }
}

@Composable
private fun ListFooter(
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isSearching: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !hasMore -> Text(
                text = "—— 没有更多了 ——",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 搜索态下走显式按钮：自动触发被上面 LaunchedEffect 禁用了，给个明确入口
            isSearching -> TextButton(onClick = onLoadMore) {
                Text(stringResource(R.string.announcement_search_load_more))
            }
            // 非搜索态 hasMore && !isLoadingMore：空白占位，等触发滚动
        }
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

@Composable
private fun AnnouncementCard(
    a: Announcement,
    onItemClick: (Announcement) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onItemClick(a) },
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumb = a.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                // 图文新闻列表的 240×180 缩略图，按 4:3 缩到列表里。RemoteJwcImage 内部走 OkHttp，
                // 走的是 jwc 域；图片是公开资源，但用同一客户端避免另起 Coil。
                // 加载中 / 失败时 fallback 为空，依赖 modifier 上的 surfaceContainerHighest 背景做占位。
                RemoteJwcImage(
                    url = thumb,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 72.dp, height = 54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    fallback = {},
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeTag(type = a.type)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = a.date.format(DATE_FMT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = a.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TypeTag(type: AnnouncementType) {
    val (bg, fg, label) = when (type) {
        AnnouncementType.NOTIFICATION -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "通知",
        )
        AnnouncementType.BULLETIN -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "通告",
        )
        // 风采多是喜报 / 获奖类报道，用 errorContainer 的柔和红呼应「喜报红」，与另外三色区分
        AnnouncementType.SHOWCASE -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "风采",
        )
        AnnouncementType.PICTURE_NEWS -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "图文",
        )
    }
    Box(
        modifier = Modifier
            .clip(AppShape.tag)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
