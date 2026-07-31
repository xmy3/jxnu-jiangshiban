package cn.jxnu.nvzhuanban.ui.screens.peoplesearch

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.ErrorState
import cn.jxnu.nvzhuanban.ui.components.LoadingState
import kotlinx.coroutines.flow.drop

/**
 * 师生统一查询页。
 *
 * 与原来分开的「教工查询」/「学生查询」相比，仅 UI 层合并：底层仍走两套
 * [cn.jxnu.nvzhuanban.data.repository.TeacherRepository] / [cn.jxnu.nvzhuanban.data.repository.StudentRepository]，
 * 解析器、详情 / 课表二级页面、退登清缓存挂钩都不动。
 *
 * UI 状态分两层：
 *  - 顶部 `type` chip（教工 / 学生）：本地 [rememberSaveable]，切换时调 [PeopleSearchViewModel.clearResults]
 *    把上次结果清掉，避免列表里残留另一种人的卡片；
 *  - keyword / field / mode 也是本地 [rememberSaveable]——它们是输入态，跟着 type 走也行，
 *    但用户习惯上"切到学生重新输关键词"更直观，所以一并保留在 Screen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSearchScreen(
    onBack: () -> Unit,
    onOpenPerson: (PersonResult) -> Unit,
    viewModel: PeopleSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(PersonType.TEACHER) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf(PeopleSearchField.NAME) }
    var mode by rememberSaveable { mutableStateOf(PeopleMatchMode.EXACT) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // 首次进页自动聚焦关键词框弹键盘，与通知页搜索栏行为一致。
    // 从详情页返回时 VM 还持有结果（非 Initial），不再弹键盘盖住结果列表。
    // 有浏览历史时同理不弹——键盘会把"最近查看"列表盖掉，历史直达比重敲关键词更快。
    LaunchedEffect(Unit) {
        if (state is PeopleSearchUiState.Initial && history.isEmpty()) focusRequester.requestFocus()
    }

    // 切换教工 / 学生时清掉上一种结果。snapshotFlow + drop(1)：只在 type 真正变化时清——
    // 若直接 LaunchedEffect(type)，从详情页返回 / 旋转屏幕时 effect 重新进组合会重跑，
    // 把 VM 里还活着的搜索结果误清成 Initial（与上方「返回时 VM 还持有结果」的意图冲突）。
    LaunchedEffect(Unit) {
        snapshotFlow { type }
            .drop(1)
            .collect { viewModel.clearResults() }
    }

    val submit: () -> Unit = {
        if (keyword.isNotBlank()) {
            keyboard?.hide()
            viewModel.search(type, keyword.trim(), field, mode)
        }
    }

    // 搜索结果与历史列表共用的详情入口：先记历史再导航，重复查看会把这个人上移到最前
    val openPerson: (PersonResult) -> Unit = { result ->
        viewModel.recordVisit(result)
        onOpenPerson(result)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_search_title)) },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SearchPanel(
                type = type,
                onTypeChange = { type = it },
                keyword = keyword,
                onKeywordChange = { keyword = it },
                field = field,
                onFieldChange = { field = it },
                mode = mode,
                onModeChange = { mode = it },
                onSubmit = submit,
                focusRequester = focusRequester,
            )
            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is PeopleSearchUiState.Initial -> if (history.isEmpty()) {
                        EmptyState(
                            message = stringResource(
                                when (type) {
                                    PersonType.TEACHER -> R.string.people_search_initial_teacher
                                    PersonType.STUDENT -> R.string.people_search_initial_student
                                }
                            ),
                            icon = Icons.Outlined.Search,
                        )
                    } else {
                        HistoryList(
                            history = history,
                            onClickPerson = openPerson,
                            onRemove = viewModel::removeHistory,
                            onClearAll = viewModel::clearHistory,
                        )
                    }
                    is PeopleSearchUiState.Loading -> LoadingState()
                    is PeopleSearchUiState.Error -> ErrorState(
                        message = s.message,
                        onRetry = viewModel::retry,
                    )
                    is PeopleSearchUiState.Success -> if (s.results.isEmpty()) {
                        EmptyState(
                            message = stringResource(
                                when (s.type) {
                                    PersonType.TEACHER -> R.string.people_search_no_match_teacher
                                    PersonType.STUDENT -> R.string.people_search_no_match_student
                                }
                            ),
                            icon = Icons.Outlined.Search,
                        )
                    } else {
                        ResultList(
                            results = s.results,
                            message = s.message,
                            count = s.count,
                            onClickPerson = openPerson,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(
    type: PersonType,
    onTypeChange: (PersonType) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    field: PeopleSearchField,
    onFieldChange: (PeopleSearchField) -> Unit,
    mode: PeopleMatchMode,
    onModeChange: (PeopleMatchMode) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部一行：教工 / 学生切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterPill(
                    label = stringResource(R.string.people_search_type_teacher),
                    selected = type == PersonType.TEACHER,
                    onClick = { onTypeChange(PersonType.TEACHER) },
                )
                FilterPill(
                    label = stringResource(R.string.people_search_type_student),
                    selected = type == PersonType.STUDENT,
                    onClick = { onTypeChange(PersonType.STUDENT) },
                )
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        stringResource(
                            when (type) {
                                PersonType.TEACHER -> R.string.people_search_keyword_hint_teacher
                                PersonType.STUDENT -> R.string.people_search_keyword_hint_student
                            }
                        )
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterPill(
                    label = stringResource(R.string.people_search_field_name),
                    selected = field == PeopleSearchField.NAME,
                    onClick = { onFieldChange(PeopleSearchField.NAME) },
                )
                FilterPill(
                    label = stringResource(
                        when (type) {
                            PersonType.TEACHER -> R.string.people_search_field_teacher_id
                            PersonType.STUDENT -> R.string.people_search_field_student_id
                        }
                    ),
                    selected = field == PeopleSearchField.ID,
                    onClick = { onFieldChange(PeopleSearchField.ID) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterPill(
                    label = stringResource(R.string.people_search_mode_exact),
                    selected = mode == PeopleMatchMode.EXACT,
                    onClick = { onModeChange(PeopleMatchMode.EXACT) },
                )
                FilterPill(
                    label = stringResource(R.string.people_search_mode_fuzzy),
                    selected = mode == PeopleMatchMode.FUZZY,
                    onClick = { onModeChange(PeopleMatchMode.FUZZY) },
                )
            }
            Button(
                onClick = onSubmit,
                enabled = keyword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.people_search_button))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
private fun ResultList(
    results: List<PersonResult>,
    count: Int?,
    message: String?,
    onClickPerson: (PersonResult) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // 学生检索常带 "查询结果过多，只显示前：10 条记录" 这类原文提示，优先展示；
            // 否则退回到 "查询结果：N 条记录"。
            val header = message?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.people_search_count_template, count ?: results.size)
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        items(
            items = results,
            key = { it.userNum.ifEmpty { it.idText + it.name } },
        ) { result ->
            PersonCard(result = result, onClick = { onClickPerson(result) })
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/**
 * 「最近查看」列表：Initial 态（还没搜索）下代替空态占位展示浏览历史。
 * 教工 / 学生混排（不随顶部 chip 过滤——历史的意义是"回到之前那个人"，切 chip 不该把人藏起来），
 * 卡片样式与搜索结果一致，多一个行尾 ✕ 删除单条；表头右侧「清空」删全部。
 */
@Composable
private fun HistoryList(
    history: List<PersonResult>,
    onClickPerson: (PersonResult) -> Unit,
    onRemove: (PersonResult) -> Unit,
    onClearAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.people_search_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.people_search_history_clear))
                }
            }
        }
        items(
            items = history,
            key = { "${it.type}:${it.userNum.ifEmpty { it.idText + it.name }}" },
        ) { result ->
            PersonCard(
                result = result,
                onClick = { onClickPerson(result) },
                trailing = {
                    IconButton(onClick = { onRemove(result) }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.people_search_history_remove),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun PersonCard(
    result: PersonResult,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (result.gender.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = result.gender,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val firstLine = listOfNotNull(
                    result.idText.takeIf { it.isNotBlank() },
                    result.department.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (firstLine.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = firstLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 学生独有：班级行（教工没有这一行）
                if (result is PersonResult.StudentResult && result.className.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = result.className,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Outlined.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
