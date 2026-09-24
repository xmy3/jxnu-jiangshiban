package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanSearchScreen(
    onBack: () -> Unit,
    viewModel: TrainingPlanSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("查询培养方案") }, navigationIcon = { BackNavigationIcon(onBack) })
    }) { padding ->
        StateScaffold(state = state, onRetry = viewModel::load, modifier = Modifier.padding(padding)) { page ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("按年级、专业筛选", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(page.filters, key = { _, filter -> filter.name }) { _, filter ->
                    FilterMenu(filter, enabled = !busy, onSelect = { viewModel.select(filter.name, it) })
                }
                item {
                    Button(onClick = viewModel::search, enabled = !busy && page.submitName != null) {
                        Text(if (busy) "查询中…" else "查询")
                    }
                }
                if (page.submitName == null) item { Text("教务系统未提供查询按钮", color = MaterialTheme.colorScheme.error) }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (searched) {
                    if (page.tables.isEmpty()) item {
                        Text(page.message ?: "没有查到培养方案", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    page.tables.forEachIndexed { tableIndex, table ->
                        item(key = "table-$tableIndex") {
                            Text(table.columns.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                        }
                        itemsIndexed(table.rows, key = { rowIndex, _ -> "$tableIndex-$rowIndex" }) { _, row ->
                            ResultRow(table, row)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterMenu(
    filter: TrainingPlanSearchPage.Filter,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    Column {
        Text(filter.label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { keyword = ""; expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(filter.options.firstOrNull { it.value == filter.selectedValue }?.label ?: "请选择")
        }
        if (filter.options.size > 60) {
            if (expanded) AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text("选择${filter.label}") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("搜索专业") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val visible = remember(keyword, filter.options) {
                            filter.options.filter { it.label.contains(keyword.trim(), ignoreCase = true) }
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            itemsIndexed(visible) { _, option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    expanded = false
                                    onSelect(option.value)
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
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        expanded = false
                        onSelect(option.value)
                    })
                }
            }
        }
    }
}

@Composable
private fun ResultRow(table: TrainingPlanSearchPage.Table, row: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEachIndexed { index, value ->
                if (value.isNotBlank()) Text("${table.columns.getOrElse(index) { "" }}：$value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
