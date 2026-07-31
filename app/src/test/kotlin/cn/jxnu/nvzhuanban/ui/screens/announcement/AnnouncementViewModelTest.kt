package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.MainDispatcherRule
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.ui.components.UiState
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
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

    private fun assertAnnouncements(
        viewModel: AnnouncementViewModel,
        expected: List<Announcement>,
    ) {
        val data = viewModel.state.value.data
        assertTrue(data is UiState.Success)
        assertEquals(expected, (data as UiState.Success).data)
    }

    private fun announcement(
        id: String,
        title: String,
        day: Int = 2,
    ) = Announcement(
        id = id,
        title = title,
        date = LocalDate.of(2026, 7, day),
        type = AnnouncementType.NOTIFICATION,
    )
}
