package cn.jxnu.nvzhuanban.ui.screens.peoplesearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.Student
import cn.jxnu.nvzhuanban.data.model.StudentMatchMode
import cn.jxnu.nvzhuanban.data.model.StudentSearchField
import cn.jxnu.nvzhuanban.data.model.StudentSearchQuery
import cn.jxnu.nvzhuanban.data.model.Teacher
import cn.jxnu.nvzhuanban.data.model.TeacherMatchMode
import cn.jxnu.nvzhuanban.data.model.TeacherSearchField
import cn.jxnu.nvzhuanban.data.model.TeacherSearchQuery
import cn.jxnu.nvzhuanban.data.network.pages.StudentSearchPage
import cn.jxnu.nvzhuanban.data.network.pages.TeacherSearchPage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.StudentRepository
import cn.jxnu.nvzhuanban.data.repository.TeacherRepository
import cn.jxnu.nvzhuanban.data.storage.PeopleSearchHistoryEntry
import cn.jxnu.nvzhuanban.data.storage.PeopleSearchHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 检索对象类型。教工与学生共用同一个搜索 UI、走不同后端。 */
enum class PersonType { TEACHER, STUDENT }

/** 检索字段。两种人共用「姓名 / 编号」二选一，编号文案在 UI 里按 [PersonType] 动态切换（教号 / 学号）。 */
enum class PeopleSearchField { NAME, ID }

/** 匹配模式：精确 / 模糊，对应教务网 `ddlSQLType` 的两种 option。 */
enum class PeopleMatchMode { EXACT, FUZZY }

/**
 * 统一的查询结果项。把 [Teacher] / [Student] 的公共字段抬到接口层，让 Screen 渲染时一份代码搞定；
 * 类型特有的字段（教工没班级、学生有班级）通过子类型判断时取。
 */
sealed interface PersonResult {
    val type: PersonType
    val name: String
    val gender: String
    /** 教工对应 base64(教号)；学生对应 base64(学号)。点详情 / 课表用。 */
    val userNum: String
    /** 「所在单位」。教工是部门，学生是学院。 */
    val department: String
    /** 列表卡片次行展示的工号字符串。教工 = 教号，学生 = 学号。 */
    val idText: String

    data class TeacherResult(val teacher: Teacher) : PersonResult {
        override val type: PersonType = PersonType.TEACHER
        override val name: String = teacher.name
        override val gender: String = teacher.gender
        override val userNum: String = teacher.userNum
        override val department: String = teacher.department
        override val idText: String = teacher.teacherId
    }

    data class StudentResult(val student: Student) : PersonResult {
        override val type: PersonType = PersonType.STUDENT
        override val name: String = student.name
        override val gender: String = student.gender
        override val userNum: String = student.userNum
        override val department: String = student.department
        override val idText: String = student.studentId
        /** 学生独有：班级（教工没有这一行）。 */
        val className: String = student.className
    }
}

sealed interface PeopleSearchUiState {
    /** 首次进入、还没点过查询。 */
    data object Initial : PeopleSearchUiState
    data object Loading : PeopleSearchUiState
    data class Success(
        val type: PersonType,
        val results: List<PersonResult>,
        val message: String?,
        val count: Int?,
    ) : PeopleSearchUiState
    data class Error(val message: String) : PeopleSearchUiState
}

/**
 * 师生统一查询 ViewModel。
 *
 * 按 [PersonType] 分发到 [TeacherRepository] 或 [StudentRepository]，两个仓库各自维护
 * donor seed 缓存（首查 GET seed，之后 POST 复用 ViewState），互不影响——退出登录会把它们
 * 一起清掉（见 [cn.jxnu.nvzhuanban.data.repository.AuthRepository.clearAllUserDataOnSignOut]）。
 */
class PeopleSearchViewModel(
    private val teacherSearch: suspend (TeacherSearchQuery) -> TeacherSearchPage.Parsed = {
        TeacherRepository.instance.search(it)
    },
    private val studentSearch: suspend (StudentSearchQuery) -> StudentSearchPage.Parsed = {
        StudentRepository.instance.search(it)
    },
) : ViewModel() {

    private val _state = MutableStateFlow<PeopleSearchUiState>(PeopleSearchUiState.Initial)
    val state: StateFlow<PeopleSearchUiState> = _state.asStateFlow()

    /**
     * 浏览历史（最近点开过详情的人），新→旧。存储在 [PeopleSearchHistoryStore]，
     * 这里换成 UI 直接可用的 [PersonResult]，Screen 在 Initial 态渲染成"最近查看"列表，
     * 点击复用与搜索结果完全相同的 onOpenPerson 路径。
     */
    val history: StateFlow<List<PersonResult>> = PeopleSearchHistoryStore.entries
        .map { list -> list.map { it.toPersonResult() } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PeopleSearchHistoryStore.entries.value.map { it.toPersonResult() },
        )

    /** 用户点开某人详情时记录；同一人重复查看上移到最前。 */
    fun recordVisit(result: PersonResult) {
        PeopleSearchHistoryStore.record(
            PeopleSearchHistoryEntry(
                isTeacher = result.type == PersonType.TEACHER,
                name = result.name,
                gender = result.gender,
                userNum = result.userNum,
                department = result.department,
                idText = result.idText,
                className = (result as? PersonResult.StudentResult)?.className.orEmpty(),
            ),
        )
    }

    fun removeHistory(result: PersonResult) {
        PeopleSearchHistoryStore.remove(
            PeopleSearchHistoryEntry(
                isTeacher = result.type == PersonType.TEACHER,
                name = result.name,
                gender = result.gender,
                userNum = result.userNum,
                department = result.department,
                idText = result.idText,
                className = (result as? PersonResult.StudentResult)?.className.orEmpty(),
            ),
        )
    }

    fun clearHistory() {
        PeopleSearchHistoryStore.clearAll()
    }

    private fun PeopleSearchHistoryEntry.toPersonResult(): PersonResult = if (isTeacher) {
        PersonResult.TeacherResult(
            Teacher(
                name = name,
                teacherId = idText,
                department = department,
                gender = gender,
                userNum = userNum,
            ),
        )
    } else {
        PersonResult.StudentResult(
            Student(
                name = name,
                studentId = idText,
                department = department,
                className = className,
                gender = gender,
                userNum = userNum,
            ),
        )
    }

    private data class LastInput(
        val type: PersonType,
        val keyword: String,
        val field: PeopleSearchField,
        val mode: PeopleMatchMode,
    )
    private var lastInput: LastInput? = null
    private var searchJob: Job? = null
    private var searchRequestId: Long = 0L

    fun search(
        type: PersonType,
        keyword: String,
        field: PeopleSearchField,
        mode: PeopleMatchMode,
    ) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val requestId = ++searchRequestId
        searchJob?.cancel()
        lastInput = LastInput(type, trimmed, field, mode)
        _state.value = PeopleSearchUiState.Loading
        searchJob = viewModelScope.launch {
            val nextState = try {
                when (type) {
                    PersonType.TEACHER -> searchTeacher(trimmed, field, mode)
                    PersonType.STUDENT -> searchStudent(trimmed, field, mode)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                PeopleSearchUiState.Error(t.toUserMessage())
            }
            if (requestId == searchRequestId) {
                _state.value = nextState
            }
        }
    }

    /** 错误态点「重试」直接复用上次入参；首次未查询过就忽略。 */
    fun retry() {
        val last = lastInput ?: return
        search(last.type, last.keyword, last.field, last.mode)
    }

    /**
     * 切换检索对象类型（教工 / 学生）时调用：把状态回到 Initial，避免列表里残留上一种类型的结果。
     * 已是 Initial 时是 no-op（首次进入 LaunchedEffect 触发的初始 onTypeChange 会安全空转）。
     */
    fun clearResults() {
        searchRequestId += 1
        searchJob?.cancel()
        searchJob = null
        lastInput = null
        if (_state.value !is PeopleSearchUiState.Initial) {
            _state.value = PeopleSearchUiState.Initial
        }
    }

    private suspend fun searchTeacher(
        keyword: String,
        field: PeopleSearchField,
        mode: PeopleMatchMode,
    ): PeopleSearchUiState.Success {
        val q = TeacherSearchQuery(
            keyword = keyword,
            field = when (field) {
                PeopleSearchField.NAME -> TeacherSearchField.NAME
                PeopleSearchField.ID -> TeacherSearchField.ID
            },
            mode = when (mode) {
                PeopleMatchMode.EXACT -> TeacherMatchMode.EXACT
                PeopleMatchMode.FUZZY -> TeacherMatchMode.FUZZY
            },
        )
        val parsed = teacherSearch(q)
        return PeopleSearchUiState.Success(
            type = PersonType.TEACHER,
            results = parsed.teachers.map { PersonResult.TeacherResult(it) },
            message = parsed.message,
            count = TeacherSearchPage.extractCount(parsed.message) ?: parsed.teachers.size,
        )
    }

    private suspend fun searchStudent(
        keyword: String,
        field: PeopleSearchField,
        mode: PeopleMatchMode,
    ): PeopleSearchUiState.Success {
        val q = StudentSearchQuery(
            keyword = keyword,
            field = when (field) {
                PeopleSearchField.NAME -> StudentSearchField.NAME
                PeopleSearchField.ID -> StudentSearchField.ID
            },
            mode = when (mode) {
                PeopleMatchMode.EXACT -> StudentMatchMode.EXACT
                PeopleMatchMode.FUZZY -> StudentMatchMode.FUZZY
            },
        )
        val parsed = studentSearch(q)
        return PeopleSearchUiState.Success(
            type = PersonType.STUDENT,
            results = parsed.students.map { PersonResult.StudentResult(it) },
            message = parsed.message,
            count = StudentSearchPage.extractCount(parsed.message) ?: parsed.students.size,
        )
    }
}
