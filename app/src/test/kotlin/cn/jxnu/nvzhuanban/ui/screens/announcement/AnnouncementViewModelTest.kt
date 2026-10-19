package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.MainDispatcherRule
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadMarker
import cn.jxnu.nvzhuanban.ui.components.UiState
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `full first page preserves pages loaded after the partial result`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val partial = listOf(announcement("first", "第一页"))
            val full = partial + announcement("picture", "图文新闻")
            val releaseFull = CompletableDeferred<Unit>()
            val requestedPages = mutableListOf<Int>()
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(partial); releaseFull.await(); emit(full) } },
                fetchPage = { page ->
                    requestedPages += page
                    listOf(announcement("page-$page", "第 $page 页", day = 1))
                },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                assertAnnouncements(viewModel, partial + announcement("page-2", "第 2 页", day = 1))

                releaseFull.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, full + announcement("page-2", "第 2 页", day = 1))

                viewModel.loadMore()
                runCurrent()
                assertAnnouncements(viewModel, full + listOf(
                    announcement("page-2", "第 2 页", day = 1),
                    announcement("page-3", "第 3 页", day = 1),
                ))
                assertEquals(listOf(2, 3), requestedPages)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `late second page preserves the full first page`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val partial = listOf(announcement("first", "第一页"))
            val full = partial + announcement("picture", "图文新闻")
            val secondPage = listOf(announcement("second", "第二页", day = 1))
            val releaseFull = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(partial); releaseFull.await(); emit(full) } },
                fetchPage = { releaseSecond.await(); secondPage },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                releaseFull.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, full)
                releaseSecond.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, full + secondPage)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `refresh invalidates an older first-page stream even if it ignores cancellation`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val partial = listOf(announcement("old", "旧第一页"))
            val refreshed = listOf(announcement("new", "刷新第一页"))
            val releaseOld = CompletableDeferred<Unit>()
            var firstPageCalls = 0
            val viewModel = AnnouncementViewModel(
                firstPageFlow = {
                    if (++firstPageCalls == 1) delayedUncancellablePage(partial, releaseOld)
                    else flowOf(refreshed)
                },
            )
            try {
                runCurrent()
                viewModel.refresh()
                runCurrent()
                assertAnnouncements(viewModel, refreshed)
                releaseOld.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, refreshed)
                assertFalse(viewModel.isRefreshing.value)
            } finally {
                releaseOld.complete(Unit)
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `load invalidates an older refresh without leaving the refresh indicator active`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val initial = listOf(announcement("initial", "首次加载"))
            val refreshPartial = listOf(announcement("refresh", "刷新中间值"))
            val reloaded = listOf(announcement("reload", "重新加载"))
            val releaseRefresh = CompletableDeferred<Unit>()
            var firstPageCalls = 0
            val viewModel = AnnouncementViewModel(
                firstPageFlow = {
                    when (++firstPageCalls) {
                        1 -> flowOf(initial)
                        2 -> delayedUncancellablePage(refreshPartial, releaseRefresh)
                        else -> flowOf(reloaded)
                    }
                },
            )
            try {
                runCurrent()
                viewModel.refresh()
                runCurrent()
                assertTrue(viewModel.isRefreshing.value)
                viewModel.load()
                runCurrent()
                assertFalse(viewModel.isRefreshing.value)
                assertAnnouncements(viewModel, reloaded)
                releaseRefresh.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, reloaded)
            } finally {
                releaseRefresh.complete(Unit)
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `failed full first page keeps partial data available for pagination`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val partial = listOf(announcement("partial", "部分首页"))
            val secondPage = listOf(announcement("second", "第二页", day = 1))
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(partial); error("图文暂时不可用") } },
                fetchPage = { page -> assertEquals(2, page); secondPage },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                assertAnnouncements(viewModel, partial + secondPage)
                assertFalse(viewModel.isLoadingMore.value)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `nonempty full page enables pagination after an empty partial`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val full = listOf(announcement("picture", "图文新闻"))
            val releaseFull = CompletableDeferred<Unit>()
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(emptyList()); releaseFull.await(); emit(full) } },
            )
            try {
                runCurrent()
                assertFalse(viewModel.state.value.hasMore)
                releaseFull.complete(Unit)
                runCurrent()
                assertAnnouncements(viewModel, full)
                assertTrue(viewModel.state.value.hasMore)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `full first page does not reopen pagination after an empty next page`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val partial = listOf(announcement("partial", "部分首页"))
            val releaseFull = CompletableDeferred<Unit>()
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(partial); releaseFull.await(); emit(partial) } },
                fetchPage = { emptyList() },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                assertFalse(viewModel.state.value.hasMore)
                releaseFull.complete(Unit)
                runCurrent()
                assertFalse(viewModel.state.value.hasMore)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `refresh failure before partial preserves the old page cursor`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val initial = listOf(announcement("initial", "首页"))
            val requestedPages = mutableListOf<Int>()
            var firstPageCalls = 0
            val viewModel = AnnouncementViewModel(
                firstPageFlow = {
                    if (++firstPageCalls == 1) flowOf(initial)
                    else flow { error("刷新失败") }
                },
                fetchPage = { page ->
                    requestedPages += page
                    listOf(announcement("page-$page", "第 $page 页", day = 1))
                },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                viewModel.refresh()
                runCurrent()
                assertFalse(viewModel.isRefreshing.value)
                viewModel.loadMore()
                runCurrent()
                assertAnnouncements(viewModel, initial + listOf(
                    announcement("page-2", "第 2 页", day = 1),
                    announcement("page-3", "第 3 页", day = 1),
                ))
                assertEquals(listOf(2, 3), requestedPages)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `refresh result is not replaced by an older load-more response`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val initialPage = listOf(announcement("old-1", "旧第一页"))
            val refreshedPage = listOf(announcement("new-1", "刷新第一页"))
            val secondPage = listOf(announcement("old-2", "旧第二页", day = 1))
            val loadMoreStarted = CompletableDeferred<Unit>()
            val releaseLoadMore = CompletableDeferred<Unit>()
            var firstPageCalls = 0
            val viewModel = AnnouncementViewModel(
                firstPageFlow = {
                    firstPageCalls += 1
                    flowOf(if (firstPageCalls == 1) initialPage else refreshedPage)
                },
                fetchPage = { page ->
                    assertEquals(2, page)
                    loadMoreStarted.complete(Unit)
                    withContext(NonCancellable) { releaseLoadMore.await() }
                    secondPage
                },
            )
            advanceUntilIdle()
            assertAnnouncements(viewModel, initialPage)

            viewModel.loadMore()
            runCurrent()
            assertTrue(loadMoreStarted.isCompleted)
            assertTrue(viewModel.isLoadingMore.value)

            viewModel.refresh()
            runCurrent()
            assertAnnouncements(viewModel, refreshedPage)
            assertFalse(viewModel.isLoadingMore.value)

            releaseLoadMore.complete(Unit)
            advanceUntilIdle()

            assertAnnouncements(viewModel, refreshedPage)
            assertFalse(viewModel.isLoadingMore.value)
            assertFalse(viewModel.isRefreshing.value)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `items newer than the saved anchor are unread`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val page = listOf(
                announcement("new", "新通知", day = 3),
                announcement("anchor", "上次看到的最新一条", day = 2),
                announcement("old", "旧通知", day = 1),
            )
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flowOf(page) },
                lastReadMarker = { marker("NOTIFICATION:anchor", day = 2) },
            )
            try {
                runCurrent()
                // 只标锚点之前（更新）的那一条；锚点本身与其后的历史条目都算已读
                assertEquals(setOf("NOTIFICATION:new"), viewModel.state.value.unreadKeys)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `same-day items above the anchor are unread`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val page = listOf(
                announcement("a", "同一天第一条", day = 2),
                announcement("anchor", "锚点", day = 2),
                announcement("c", "同一天锚点之后", day = 2),
            )
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flowOf(page) },
                lastReadMarker = { marker("NOTIFICATION:anchor", day = 2) },
            )
            try {
                runCurrent()
                assertEquals(setOf("NOTIFICATION:a"), viewModel.state.value.unreadKeys)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `partial page missing the anchor does not mark older items unread`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 锚点是图文新闻（首屏 full 阶段才回来）；partial 只有更早的通知。
            // 若按「下标找不到锚点就整页算新」，partial 一秒内会满屏红点。
            val partial = listOf(announcement("notif", "昨天的通知", day = 1))
            val full = partial + announcement(
                "picture",
                "图文新闻",
                day = 2,
                type = AnnouncementType.PICTURE_NEWS,
            )
            val releaseFull = CompletableDeferred<Unit>()
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flow { emit(partial); releaseFull.await(); emit(full) } },
                lastReadMarker = { marker("PICTURE_NEWS:picture", day = 2) },
            )
            try {
                runCurrent()
                assertTrue(viewModel.state.value.unreadKeys.isEmpty())
                releaseFull.complete(Unit)
                runCurrent()
                assertTrue(viewModel.state.value.unreadKeys.isEmpty())
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `first refresh after install picks up the anchor written by the first load`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 全新安装第一次打开：没有锚点 → 不标未读；首次加载终值会把「顶部」写回锚点。
            // 之后的刷新要能拿这份刚落盘的锚点，否则新通知永远不会有未读点。
            var stored: AnnouncementReadMarker? = null
            var page = listOf(announcement("old", "旧通知", day = 1))
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flowOf(page) },
                lastReadMarker = { stored },
            )
            try {
                runCurrent()
                assertTrue(viewModel.state.value.unreadKeys.isEmpty())

                // 模拟存储侧在首次加载终值处写入锚点
                stored = marker("NOTIFICATION:old", day = 1)
                page = listOf(
                    announcement("new", "新通知", day = 3),
                    announcement("old", "旧通知", day = 1),
                )
                viewModel.refresh()
                runCurrent()
                assertEquals(setOf("NOTIFICATION:new"), viewModel.state.value.unreadKeys)
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `tapping an unread item keeps it read after a refresh`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val page = listOf(
                announcement("new", "新通知", day = 3),
                announcement("anchor", "锚点", day = 2),
            )
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flowOf(page) },
                lastReadMarker = { marker("NOTIFICATION:anchor", day = 2) },
            )
            try {
                runCurrent()
                assertEquals(setOf("NOTIFICATION:new"), viewModel.state.value.unreadKeys)

                viewModel.markItemRead("NOTIFICATION:new")
                assertTrue(viewModel.state.value.unreadKeys.isEmpty())

                // 下拉刷新走的是同一份锚点快照，读过的条目不该复活
                viewModel.refresh()
                runCurrent()
                assertTrue(viewModel.state.value.unreadKeys.isEmpty())
            } finally { viewModel.viewModelScope.cancel() }
        }

    @Test
    fun `load more failure exposes retry and bypasses the cooldown when tapped`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val initial = listOf(announcement("initial", "首页"))
            var fetchCalls = 0
            val viewModel = AnnouncementViewModel(
                firstPageFlow = { flowOf(initial) },
                fetchPage = {
                    fetchCalls += 1
                    error("教务网暂时不可用")
                },
            )
            try {
                runCurrent()
                viewModel.loadMore()
                runCurrent()
                assertEquals(1, fetchCalls)
                assertTrue(viewModel.state.value.loadMoreFailed)
                assertFalse(viewModel.isLoadingMore.value)

                // 冷却窗口内自动触发被挡住，不再打教务网
                viewModel.loadMore()
                runCurrent()
                assertEquals(1, fetchCalls)

                // footer 上显式点重试：绕过冷却，真的再请求一次
                viewModel.retryLoadMore()
                runCurrent()
                assertEquals(2, fetchCalls)
            } finally { viewModel.viewModelScope.cancel() }
        }

    private fun marker(uniqueKey: String, day: Int) =
        AnnouncementReadMarker(uniqueKey, LocalDate.of(2026, 7, day))

    private fun delayedUncancellablePage(
        partial: List<Announcement>,
        release: CompletableDeferred<Unit>,
    ): Flow<List<Announcement>> = object : Flow<List<Announcement>> {
        override suspend fun collect(collector: FlowCollector<List<Announcement>>) {
            collector.emit(partial)
            withContext(NonCancellable) {
                release.await()
                collector.emit(partial + announcement("late", "过期终值"))
            }
        }
    }

    private fun assertAnnouncements(
        viewModel: AnnouncementViewModel,
        expected: List<Announcement>,
    ) {
        val data = viewModel.state.value.data
        assertTrue(data is UiState.Success)
        assertEquals(expected, (data as UiState.Success).data)
    }

    /**
     * 搜索空态的条数必须按「当前 chip 的范围」算：搜索是在 chip 范围内做的，
     * 说「已加载 16 条」而当前分类其实只有 1 条，等于给了一个错的范围暗示。
     */
    @Test
    fun filteredCount_followsChipAndIgnoresQuery() = runTest(mainDispatcherRule.testDispatcher) {
        val all = listOf(
            announcement(id = "1", title = "2026年秋季期中教学检查"),
            announcement(id = "2", title = "2026年校级教改课题立项", type = AnnouncementType.BULLETIN),
            announcement(id = "3", title = "数模竞赛获奖", type = AnnouncementType.PICTURE_NEWS),
        )
        val state = AnnouncementScreenState(
            data = UiState.Success(all),
            filter = AnnouncementFilter.PICTURE_NEWS,
            searchQuery = "不存在的词",
        )
        assertEquals(1, state.filteredCount)
        assertEquals(3, state.loadedCount)
        assertEquals(emptyList<Announcement>(), state.visibleList)
        assertEquals(
            3,
            state.copy(filter = AnnouncementFilter.ALL).filteredCount,
        )
    }

    private fun announcement(
        id: String,
        title: String,
        day: Int = 2,
        type: AnnouncementType = AnnouncementType.NOTIFICATION,
    ) = Announcement(
        id = id,
        title = title,
        date = LocalDate.of(2026, 7, day),
        type = type,
    )
}
