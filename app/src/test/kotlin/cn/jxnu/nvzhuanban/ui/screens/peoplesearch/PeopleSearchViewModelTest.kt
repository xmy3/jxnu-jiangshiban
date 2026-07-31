package cn.jxnu.nvzhuanban.ui.screens.peoplesearch

import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.MainDispatcherRule
import cn.jxnu.nvzhuanban.data.model.Student
import cn.jxnu.nvzhuanban.data.model.Teacher
import cn.jxnu.nvzhuanban.data.network.pages.StudentSearchPage
import cn.jxnu.nvzhuanban.data.network.pages.TeacherSearchPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `older teacher result cannot replace newer student result after type switch`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val teacherStarted = CompletableDeferred<Unit>()
            val releaseTeacher = CompletableDeferred<Unit>()
            val viewModel = PeopleSearchViewModel(
                teacherSearch = {
                    teacherStarted.complete(Unit)
                    withContext(NonCancellable) { releaseTeacher.await() }
                    teacherPage("旧教工")
                },
                studentSearch = { studentPage("新学生") },
            )

            viewModel.search(PersonType.TEACHER, "旧", PeopleSearchField.NAME, PeopleMatchMode.FUZZY)
            runCurrent()
            assertTrue(teacherStarted.isCompleted)

            viewModel.clearResults()
            viewModel.search(PersonType.STUDENT, "新", PeopleSearchField.NAME, PeopleMatchMode.FUZZY)
            runCurrent()
            assertSuccess(viewModel, PersonType.STUDENT, "新学生")

            releaseTeacher.complete(Unit)
            advanceUntilIdle()

            assertSuccess(viewModel, PersonType.STUDENT, "新学生")
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `clear results prevents an in-flight search from writing back`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val teacherStarted = CompletableDeferred<Unit>()
            val releaseTeacher = CompletableDeferred<Unit>()
            val viewModel = PeopleSearchViewModel(
                teacherSearch = {
                    teacherStarted.complete(Unit)
                    withContext(NonCancellable) { releaseTeacher.await() }
                    teacherPage("迟到教工")
                },
                studentSearch = { studentPage("unused") },
            )

            viewModel.search(PersonType.TEACHER, "迟到", PeopleSearchField.NAME, PeopleMatchMode.EXACT)
            runCurrent()
            assertTrue(teacherStarted.isCompleted)

            viewModel.clearResults()
            releaseTeacher.complete(Unit)
            advanceUntilIdle()

            assertEquals(PeopleSearchUiState.Initial, viewModel.state.value)
            viewModel.viewModelScope.cancel()
        }

    private fun assertSuccess(
        viewModel: PeopleSearchViewModel,
        type: PersonType,
        name: String,
    ) {
        val state = viewModel.state.value
        assertTrue(state is PeopleSearchUiState.Success)
        state as PeopleSearchUiState.Success
        assertEquals(type, state.type)
        assertEquals(listOf(name), state.results.map { it.name })
    }

    private fun teacherPage(name: String) = TeacherSearchPage.Parsed(
        teachers = listOf(
            Teacher(
                name = name,
                teacherId = "T001",
                department = "测试学院",
                gender = "男",
                userNum = "VDAwMQ==",
            ),
        ),
        message = "查询结果：1 条记录",
        viewState = "",
        viewStateGenerator = "",
        eventValidation = "",
    )

    private fun studentPage(name: String) = StudentSearchPage.Parsed(
        students = listOf(
            Student(
                name = name,
                studentId = "S001",
                department = "测试学院",
                className = "测试班",
                gender = "女",
                userNum = "UzAwMQ==",
            ),
        ),
        message = "查询结果：1 条记录",
        viewState = "",
        viewStateGenerator = "",
        eventValidation = "",
    )
}
