package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.theme.AppShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanSearchScreen(onBack: () -> Unit, viewModel: TrainingPlanSearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()

    // AppNav 的外层 Scaffold 已留出系统栏空间，本页不重复添加。
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(title = { Text("查询培养方案") }, navigationIcon = { BackNavigationIcon(onBack) },
            windowInsets = WindowInsets(0, 0, 0, 0))
    }) { padding ->
        StateScaffold(state = state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { page ->
            val groups = remember(page.tables) { page.tables.toPlanCourseGroups() }
            val semesters = remember(groups) { groups.semesterOptions() }
            var selectedSemester by rememberSaveable(page.tables) { mutableStateOf<String?>(null) }
            val visibleGroups = remember(groups, selectedSemester) { groups.forSemester(selectedSemester) }
            var filtersExpanded by rememberSaveable { mutableStateOf(true) }
            var expandedGroups by rememberSaveable { mutableStateOf(setOf(0)) }
            LaunchedEffect(searched) { if (searched) filtersExpanded = false }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item(key = "filters") {
                    FilterPanel(
                        page = page,
                        expanded = filtersExpanded,
                        busy = busy,
                        onToggle = { filtersExpanded = !filtersExpanded },
                        onSelect = viewModel::select,
                        onSearch = {
                            selectedSemester = null
                            viewModel.search()
                        },
                    )
                }
                error?.let { message ->
                    item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                if (searched) {
                    if (groups.isEmpty()) {
                        item(key = "empty") {
                            Text(page.message ?: "没有查到培养方案课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        item(key = "summary") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "共 ${visibleGroups.sumOf { it.courses.size }} 门课程 · ${visibleGroups.count { it.courses.isNotEmpty() }} 个模块",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                )
                                SemesterMenu(semesters, selectedSemester) { semester ->
                                    selectedSemester = semester
                                    // 切换学期时直接展开匹配模块，避免结果藏在已收起的模块里。
                                    expandedGroups = groups.forSemester(semester).mapIndexedNotNull { index, group ->
                                        index.takeIf { group.courses.isNotEmpty() }
                                    }.toSet()
                                }
                            }
                        }
                        visibleGroups.forEachIndexed { groupIndex, group ->
                            if (group.courses.isEmpty()) return@forEachIndexed
                            val expanded = groupIndex in expandedGroups
                            item(key = "group-$groupIndex") {
                                GroupHeader(group, expanded) {
                                    expandedGroups = if (expanded) expandedGroups - groupIndex else expandedGroups + groupIndex
                                }
                            }
                            if (expanded) {
                                itemsIndexed(group.courses, key = { courseIndex, _ -> "course-$selectedSemester-$groupIndex-$courseIndex" }) { index, course ->
                                    CourseRow(course, isLast = index == group.courses.lastIndex)
                                }
                            }
                        }
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun SemesterMenu(options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = 160.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(selected ?: "全部学期", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.ExpandMore, contentDescription = "按学期筛选")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部学期") }, onClick = { expanded = false; onSelect(null) })
            options.forEach { semester ->
                DropdownMenuItem(text = { Text(semester) }, onClick = { expanded = false; onSelect(semester) })
            }
        }
    }
}

@Composable
private fun FilterPanel(
    page: TrainingPlanSearchPage.Parsed,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onSelect: (String, String) -> Unit,
    onSearch: () -> Unit,
) {
    if (!expanded) {
        val selection = page.filters.mapNotNull { filter ->
            filter.options.firstOrNull { it.value == filter.selectedValue }?.label
        }.joinToString(" · ")
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button, onClickLabel = "展开筛选", onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Text(selection, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Tune, contentDescription = "筛选年级和专业", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .clickable(role = Role.Button, onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("筛选条件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起筛选" else "展开筛选")
            }
            if (expanded) {
                page.filters.forEach { filter ->
                    FilterMenu(filter, enabled = !busy, onSelect = { onSelect(filter.name, it) })
                }
                Button(onClick = onSearch, enabled = !busy && page.submitName != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (busy) "查询中…" else "查询培养方案")
                }
                if (page.submitName == null) Text("教务系统未提供查询按钮", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun GroupHeader(group: PlanCourseGroup, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = Role.Button, onClick = onToggle),
        shape = if (expanded) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 10.dp).width(3.dp).height(16.dp)
                .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
            Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text("${group.courses.size} 门", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CourseRow(course: PlanCourseRow, isLast: Boolean) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val canExpand = course.prerequisite != null
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(if (isLast) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (canExpand) Modifier
                .semantics { stateDescription = if (detailsExpanded) "先修课程已展开" else "先修课程已收起" }
                .clickable(role = Role.Button, onClickLabel = "切换先修课程") { detailsExpanded = !detailsExpanded }
            else Modifier),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                course.credit?.let {
                    Text("$it 学分", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                course.semester?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (course.isDegreeCourse) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("学位课", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 5.dp))
                    }
                }
                if (canExpand) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (detailsExpanded) "收起先修课" else "先修课",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Icon(if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            AnimatedVisibility(visible = detailsExpanded && canExpand) {
                Text("先修课程：${course.prerequisite}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!isLast) HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun FilterMenu(filter: TrainingPlanSearchPage.Filter, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    Column {
        Text(filter.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { keyword = ""; expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(filter.options.firstOrNull { it.value == filter.selectedValue }?.label ?: "请选择",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
        // 专业始终可搜索，不能因某个年级的可选专业少于 60 个就退回普通菜单。
        if (filter.label.contains("专业") || filter.options.size > 60) {
            if (expanded) {
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                val listState = rememberLazyListState()
                val visible = remember(keyword, filter.options) {
                    filter.options.filter { it.label.contains(keyword.trim(), ignoreCase = true) }
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                LaunchedEffect(keyword) { listState.scrollToItem(0) }
                AlertDialog(
                    onDismissRequest = { expanded = false }, title = { Text("选择${filter.label}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                label = { Text("搜索${filter.label}") },
                                placeholder = { Text("输入名称关键词") },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (keyword.isNotEmpty()) IconButton(onClick = { keyword = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                            Text(
                                if (visible.isEmpty()) "没有匹配的${filter.label}，请更换关键词"
                                else "${visible.size} 个结果",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                                itemsIndexed(visible) { _, option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                        expanded = false; onSelect(option.value)
                                    })
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { expanded = false }) { Text("取消") } },
                )
            }
        } else {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option.value) })
                }
            }
        }
    }
}
