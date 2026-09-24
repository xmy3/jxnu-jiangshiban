package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
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

    Scaffold(topBar = {
        TopAppBar(title = { Text("查询培养方案") }, navigationIcon = { BackNavigationIcon(onBack) })
    }) { padding ->
        StateScaffold(state = state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { page ->
            val groups = remember(page.tables) { page.tables.toPlanCourseGroups() }
            var filtersExpanded by rememberSaveable { mutableStateOf(true) }
            var expandedGroups by rememberSaveable { mutableStateOf(setOf(0)) }
            LaunchedEffect(searched) { if (searched) filtersExpanded = false }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "filters") {
                    FilterPanel(
                        page = page,
                        expanded = filtersExpanded,
                        busy = busy,
                        onToggle = { filtersExpanded = !filtersExpanded },
                        onSelect = viewModel::select,
                        onSearch = viewModel::search,
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
                            Text(
                                "共 ${groups.sumOf { it.courses.size }} 门课程 · ${groups.size} 个模块",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        groups.forEachIndexed { groupIndex, group ->
                            val expanded = groupIndex in expandedGroups
                            item(key = "group-$groupIndex") {
                                GroupHeader(group, expanded) {
                                    expandedGroups = if (expanded) expandedGroups - groupIndex else expandedGroups + groupIndex
                                }
                            }
                            if (expanded) {
                                itemsIndexed(group.courses, key = { courseIndex, _ -> "course-$groupIndex-$courseIndex" }) { _, course ->
                                    CourseCard(course)
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
private fun FilterPanel(
    page: TrainingPlanSearchPage.Parsed,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onSelect: (String, String) -> Unit,
    onSearch: () -> Unit,
) {
    val selection = page.filters.mapNotNull { filter ->
        filter.options.firstOrNull { it.value == filter.selectedValue }?.label
    }.joinToString(" · ")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("筛选条件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!expanded) Text(selection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起筛选" else "展开筛选")
            }
            if (expanded) {
                page.filters.forEach { filter ->
                    FilterMenu(filter, enabled = !busy, onSelect = { onSelect(filter.name, it) })
                }
                Button(onClick = onSearch, enabled = !busy && page.submitName != null, modifier = Modifier.fillMaxWidth()) {
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${group.courses.size} 门", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun CourseCard(course: PlanCourseRow) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val canExpand = course.prerequisite != null
    Card(
        modifier = Modifier.fillMaxWidth().then(if (canExpand) Modifier.clickable { detailsExpanded = !detailsExpanded } else Modifier),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(course.name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                course.credit?.let {
                    Text("$it 学分", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (course.semester != null || course.isDegreeCourse || canExpand) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    course.semester?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (course.isDegreeCourse) Text("学位课", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (canExpand) Text(if (detailsExpanded) "收起先修课" else "查看先修课",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            AnimatedVisibility(visible = detailsExpanded && canExpand) {
                Text("先修课程：${course.prerequisite}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun FilterMenu(filter: TrainingPlanSearchPage.Filter, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    Column {
        Text(filter.label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { keyword = ""; expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(filter.options.firstOrNull { it.value == filter.selectedValue }?.label ?: "请选择")
        }
        if (filter.options.size > 60) {
            if (expanded) AlertDialog(
                onDismissRequest = { expanded = false }, title = { Text("选择${filter.label}") },
                text = {
                    Column {
                        OutlinedTextField(value = keyword, onValueChange = { keyword = it },
                            label = { Text("搜索专业") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val visible = remember(keyword, filter.options) {
                            filter.options.filter { it.label.contains(keyword.trim(), ignoreCase = true) }
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
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
        } else {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option.value) })
                }
            }
        }
    }
}
