package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementScreen(
    onItemClick: (Announcement) -> Unit,
    viewModel: AnnouncementViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    // 分组标题 / 卡片日期都相对「今天」算，跨零点自动刷新，避免整夜挂着昨天的分组
    val today = rememberToday()

    // 搜索栏开关；与 ViewModel 的 searchQuery 解耦：
    // 用户可以打开搜索栏但没输入（query 仍为空）— 此时列表展示不变。
    // rememberSaveable：底部导航 saveState=true 切走再回来时,searchQuery 在 VM 里仍保留,
    // 这里必须一起保留 isSearchActive 否则会出现"搜索框消失但列表仍按 query 过滤"的迷惑态。
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // 列表滚动状态提到屏幕层：换分类 / 进出搜索态时，先用「点击这一刻」的位置记下当前分类，
    // 再请求新列表在下一帧用它自己的历史位置定位。
    // 用 requestScrollToItem 而不是 scrollToItem：前者等下一次测量才生效，不会被旧列表
    // （可能更短）的 item 数 clamp；后者是 suspend 且在旧内容上立刻执行。
    val listState = rememberLazyListState()
    val savedPositions = remember { mutableStateMapOf<String, Int>() }
    val listKey = listStateKey(state.filter, state.isSearching)

    fun rememberCurrentScrollFor(newKey: String) {
        savedPositions[listKey] = listState.firstVisibleItemIndex
        listState.requestScrollToItem(savedPositions[newKey] ?: 0)
    }

    fun exitSearch() {
        if (state.isSearching) rememberCurrentScrollFor(listStateKey(state.filter, false))
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    // 搜索态下按返回先退出搜索，而不是离开整个 tab
    BackHandler(enabled = isSearchActive) { exitSearch() }

    // 打开搜索栏时自动聚焦弹键盘
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchFocus.requestFocus()
    }

    // 先按 chip + 搜索词过滤，再铺平成「分组头 + 条目」；两段都只在真正变化时重算，
    // 否则每次重组都会产生新 List 破坏 LazyColumn 的跳过优化
    val visible = remember(state.data, state.filter, state.searchQuery) { state.visibleList }
    val rows = remember(visible, today) { groupAnnouncements(visible, today) }
    val highlightQuery = if (state.isSearching) state.searchQuery.trim() else ""

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                rememberTransientErrorSnackbar(viewModel.refreshFailed, viewModel.loadMoreError),
            )
        },
        topBar = {
            if (isSearchActive) {
                SearchTopBar(
                    query = state.searchQuery,
                    onQueryChange = { q ->
                        val nowSearching = q.isNotBlank()
                        if (nowSearching != state.isSearching) {
                            rememberCurrentScrollFor(listStateKey(state.filter, nowSearching))
                        }
                        viewModel.setSearchQuery(q)
                    },
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
            .padding(padding)
            // 搜索态键盘弹起时，窗口不会自己 resize（MainActivity 走 enableEdgeToEdge），
            // 不显式吃掉 ime inset 的话列表底部和空态按钮会被键盘盖住
            .imePadding(),
        ) {
            FilterRow(
                current = state.filter,
                onSelect = { f ->
                    if (f != state.filter) {
                        rememberCurrentScrollFor(listStateKey(f, state.isSearching))
                        viewModel.selectFilter(f)
                    } else {
                        // 再点一次当前分类 = 回到顶部（列表滚远后的快捷手势）
                        listState.requestScrollToItem(0)
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                    if (rows.isEmpty()) {
                        when {
                            // 搜索没找到：单独一屏，包含「加载更早的通知继续搜索」按钮（hasMore 时），
                            // 让用户在已加载页内没匹配时仍能扩大搜索范围
                            state.isSearching -> SearchEmpty(
                                scopedCount = state.filteredCount,
                                // 搜索只作用于当前 chip 的范围内；范围被 chip 收窄时文案要说清，
                                // 并给一个「在全部里搜」的出口，否则用户会以为库里真没这条。
                                narrowed = state.filter != AnnouncementFilter.ALL,
                                query = state.searchQuery.trim(),
                                hasMore = state.hasMore,
                                isLoadingMore = isLoadingMore,
                                onLoadMore = viewModel::loadMore,
                                onSearchAll = {
                                    listState.requestScrollToItem(0)
                                    viewModel.selectFilter(AnnouncementFilter.ALL)
                                },
                            )
                            state.filter != AnnouncementFilter.ALL ->
                                EmptyState(stringResource(R.string.announcement_filter_empty))
                            else -> EmptyState(stringResource(R.string.announcement_empty))
                        }
                    } else {
                        AnnouncementList(
                            rows = rows,
                            today = today,
                            listState = listState,
                            unreadKeys = state.unreadKeys,
                            highlightQuery = highlightQuery,
                            isSearching = state.isSearching,
                            hasMore = state.hasMore,
                            isLoadingMore = isLoadingMore,
                            loadMoreFailed = state.loadMoreFailed,
                            onLoadMore = viewModel::loadMore,
                            onRetryLoadMore = viewModel::retryLoadMore,
                            onItemClick = { a ->
                                // 点开即视为读过：列表里的未读点立刻消失，返回时不会又亮起来
                                viewModel.markItemRead(a.uniqueKey)
                                onItemClick(a)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 当前日期，跨零点自动更新。
 * 分组标题（今天 / 昨天 / 本周）与卡片日期都依赖它，整夜后台挂着不刷新会显示成昨天。
 */
@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            // +1s 缓冲：保证醒来时日期真的已经翻篇，避免 23:59:59.xxx 的边界抖动
            val waitMs = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L) + 1_000L
            delay(waitMs)
            today = LocalDate.now()
        }
    }
    return today
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
    scopedCount: Int,
    narrowed: Boolean,
    query: String,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onSearchAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 可滚动：否则外层 PullToRefreshBox 在空态收不到下拉手势
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (narrowed) {
                stringResource(R.string.announcement_search_empty_in_filter, scopedCount, query)
            } else {
                stringResource(R.string.announcement_search_empty_template, scopedCount, query)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (narrowed) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSearchAll) {
                Text(stringResource(R.string.announcement_search_in_all))
            }
        }
        if (hasMore) {
            Spacer(Modifier.height(if (narrowed) 0.dp else 12.dp))
            TextButton(onClick = onLoadMore, enabled = !isLoadingMore) {
                Text(
                    text = if (isLoadingMore) {
                        stringResource(R.string.announcement_loading_more)
                    } else {
                        stringResource(R.string.announcement_search_load_more)
                    },
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(stringResource(R.string.announcement_filter_all), current == AnnouncementFilter.ALL) {
            onSelect(AnnouncementFilter.ALL)
        }
        FilterPill(
            stringResource(R.string.announcement_filter_notification),
            current == AnnouncementFilter.NOTIFICATION,
        ) { onSelect(AnnouncementFilter.NOTIFICATION) }
        FilterPill(
            stringResource(R.string.announcement_filter_bulletin),
            current == AnnouncementFilter.BULLETIN,
        ) { onSelect(AnnouncementFilter.BULLETIN) }
        FilterPill(
            stringResource(R.string.announcement_filter_showcase),
            current == AnnouncementFilter.SHOWCASE,
        ) { onSelect(AnnouncementFilter.SHOWCASE) }
        FilterPill(
            stringResource(R.string.announcement_filter_picture_news),
            current == AnnouncementFilter.PICTURE_NEWS,
        ) { onSelect(AnnouncementFilter.PICTURE_NEWS) }
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

/** 每种筛选 / 搜索态各自记住滚动位置，切回来不用重新翻。 */
private fun listStateKey(filter: AnnouncementFilter, isSearching: Boolean): String =
    "${filter.name}:$isSearching"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnouncementList(
    rows: List<AnnouncementRow>,
    today: LocalDate,
    listState: LazyListState,
    unreadKeys: Set<String>,
    highlightQuery: String,
    isSearching: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onItemClick: (Announcement) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 滚到距底部 3 项时自动 loadMore。derivedStateOf 把派生计算挂在 listState 上，
    // 只在 layoutInfo 变化时才重算，避免每帧重组。只读 listState.layoutInfo（快照状态），
    // 不捕获 rows 参数——无 key 的 remember 会把列表引用冻结在首次组合，
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
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore, isSearching, loadMoreFailed) {
        if (!isSearching && shouldLoadMore && hasMore && !isLoadingMore && !loadMoreFailed) onLoadMore()
    }

    // 长列表滚远后给一个回到顶部的入口；超过一屏多才出现，避免刚进页面就挡住内容
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 6 }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 横向 padding 交给每个条目自己加：吸顶分组头要铺满整宽、用不透明底色盖住
            // 从下面滚过去的卡片，contentPadding 左右留白会让卡片从缝隙里透出来
            contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp),
        ) {
            rows.forEach { row ->
                when (row) {
                    is AnnouncementRow.Header -> stickyHeader(key = row.key, contentType = "header") {
                        GroupHeader(
                            group = row.group,
                            today = today,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is AnnouncementRow.Item -> item(key = row.key, contentType = "item") {
                        val announcement = row.announcement
                        AnnouncementCard(
                            a = announcement,
                            isUnread = announcement.uniqueKey in unreadKeys,
                            highlightQuery = highlightQuery,
                            today = today,
                            onItemClick = onItemClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .animateItem(),
                        )
                    }
                }
            }
            item(key = "__footer__") {
                ListFooter(
                    hasMore = hasMore,
                    isLoadingMore = isLoadingMore,
                    loadMoreFailed = loadMoreFailed,
                    isSearching = isSearching,
                    onLoadMore = onLoadMore,
                    onRetry = onRetryLoadMore,
                )
            }
        }

        AnimatedVisibility(
            visible = showBackToTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.announcement_back_to_top),
                )
            }
        }
    }
}

/**
 * 吸顶分组头。底色必须是不透明的页面背景色：它停在顶部时下面正有卡片滚过去，
 * 半透明会让文字叠字。
 */
@Composable
private fun GroupHeader(
    group: AnnouncementGroup,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一点品牌色的小竖条，让分组头在满屏卡片里能被一眼扫到
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = groupLabel(group, today),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun groupLabel(group: AnnouncementGroup, today: LocalDate): String = when (group) {
    AnnouncementGroup.Today -> stringResource(R.string.announcement_group_today)
    AnnouncementGroup.Yesterday -> stringResource(R.string.announcement_group_yesterday)
    AnnouncementGroup.ThisWeek -> stringResource(R.string.announcement_group_this_week)
    is AnnouncementGroup.Month ->
        if (group.year == today.year) {
            stringResource(R.string.announcement_group_month, group.month)
        } else {
            stringResource(R.string.announcement_group_year_month, group.year, group.month)
        }
}

@Composable
private fun ListFooter(
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    isSearching: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
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
                    text = stringResource(R.string.announcement_loading_more),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 失败不再静默（旧实现用户滚到底只看到空白）：给明确的「点击重试」，
            // 并绕过自动重试的 5s 冷却
            loadMoreFailed -> TextButton(onClick = onRetry) {
                Text(stringResource(R.string.announcement_load_more_retry))
            }
            !hasMore -> Text(
                text = stringResource(R.string.announcement_no_more),
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

@Composable
private fun AnnouncementCard(
    a: Announcement,
    isUnread: Boolean,
    highlightQuery: String,
    today: LocalDate,
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
                .padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isUnread) {
                        UnreadDot()
                        Spacer(Modifier.width(6.dp))
                    }
                    TypeTag(type = a.type)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = dateText(a.date, today),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = highlightedTitle(a.title, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    // 未读加粗 + 圆点：读过的条目视觉上退一档，长列表一眼能扫出新增
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val thumb = a.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                // 图文新闻列表的 240×180 缩略图，按 4:3 缩略。RemoteJwcImage 内部走 OkHttp，
                // 走的是 jwc 域；图片是公开资源，但用同一客户端避免另起 Coil。
                // 加载中 / 失败时 fallback 为空，依赖 modifier 上的 surfaceContainerHighest 背景做占位。
                RemoteJwcImage(
                    url = thumb,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 76.dp, height = 57.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    fallback = {},
                )
            }
        }
    }
}

/** 未读小圆点。带一句内容描述，TalkBack 读卡时会把它并进整卡的描述里。 */
@Composable
private fun UnreadDot() {
    val description = stringResource(R.string.announcement_unread)
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun dateText(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) {
        stringResource(R.string.announcement_date, date.monthValue, date.dayOfMonth)
    } else {
        stringResource(
            R.string.announcement_date_with_year,
            date.year,
            date.monthValue,
            date.dayOfMonth,
        )
    }

/** 搜索时把命中的关键词染成品牌色并加粗；无搜索词时零开销返回原文。 */
@Composable
private fun highlightedTitle(title: String, query: String): AnnotatedString {
    if (query.isEmpty()) return remember(title) { AnnotatedString(title) }
    val accent = MaterialTheme.colorScheme.primary
    val matchBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    return remember(title, query, accent, matchBackground) {
        buildAnnotatedString {
            var start = 0
            while (start <= title.length) {
                val hit = title.indexOf(query, startIndex = start, ignoreCase = true)
                if (hit < 0) {
                    append(title.substring(start))
                    break
                }
                if (hit > start) append(title.substring(start, hit))
                withStyle(
                    SpanStyle(
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        background = matchBackground,
                    ),
                ) {
                    append(title.substring(hit, hit + query.length))
                }
                start = hit + query.length
            }
        }
    }
}

@Composable
private fun TypeTag(type: AnnouncementType) {
    val (bg, fg, label) = when (type) {
        AnnouncementType.NOTIFICATION -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.announcement_filter_notification),
        )
        AnnouncementType.BULLETIN -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.announcement_filter_bulletin),
        )
        // 风采多是喜报 / 获奖类报道，用 errorContainer 的柔和红呼应「喜报红」，与另外三色区分
        AnnouncementType.SHOWCASE -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.announcement_filter_showcase),
        )
        AnnouncementType.PICTURE_NEWS -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(R.string.announcement_filter_picture_news),
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
