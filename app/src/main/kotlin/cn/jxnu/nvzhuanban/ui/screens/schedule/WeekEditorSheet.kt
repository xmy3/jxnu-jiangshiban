package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.data.model.Course

/**
 * 编辑某门课实际上课的周次。教务网原始数据所有课都是 1..18 周，学生实际情况可能只上某些周，
 * 这里给一个本地覆盖入口。保存后 [onSave] 收到的 List：
 * - null → 用户选回了 1..[totalWeeks] 全部，等同于"恢复默认"，调用方应清掉 override
 * - 非空 → 用户的实际选择
 *
 * 0 周（全部取消勾选）保存按钮会被禁用 —— 那是"删掉这门课"的语义，不在本编辑器范围。
 */
@Composable
internal fun WeekEditorSheet(
    course: Course,
    totalWeeks: Int,
    onCancel: () -> Unit,
    onSave: (List<Int>?) -> Unit,
) {
    // 选中集合用 mutableStateOf<Set<Int>>，比 mutableStateListOf 更适合"无序集合且要整存整取"的场景
    var selected by remember(course.id, totalWeeks) {
        mutableStateOf(course.weeks.toSet())
    }
    val defaultAll = remember(totalWeeks) { (1..totalWeeks).toSet() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = "编辑周次",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = course.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))

        // 快捷预设：覆盖最常见的几种模式。点了立即应用到 selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetChip("全选") { selected = defaultAll }
            PresetChip("单周") { selected = (1..totalWeeks step 2).toSet() }
            PresetChip("双周") { selected = (2..totalWeeks step 2).toSet() }
            PresetChip("1-8") { selected = (1..minOf(8, totalWeeks)).toSet() }
            // 慕课：江师大慕课课程排在第 1-2 周与第 14-15 周，共 4 周
            PresetChip("慕课") {
                selected = ((1..2) + (14..15)).filter { it in 1..totalWeeks }.toSet()
            }
        }
        Spacer(Modifier.height(14.dp))

        // 周次方块网格：6 列固定，行数随 totalWeeks 增长。点击切换
        val columns = 6
        val rows = (totalWeeks + columns - 1) / columns
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowIdx in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (colIdx in 0 until columns) {
                        val week = rowIdx * columns + colIdx + 1
                        if (week <= totalWeeks) {
                            WeekCell(
                                week = week,
                                checked = week in selected,
                                onToggle = {
                                    selected = if (week in selected) selected - week else selected + week
                                },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            // 占位让最后一行也保持等分宽
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "已选 ${selected.size} 周 · ${formatWeeks(selected.sorted(), totalWeeks)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) { Text("取消") }
            Button(
                onClick = {
                    // 选择回到 1..N → null（清掉本地 override，回到教务网默认）
                    onSave(if (selected == defaultAll) null else selected.sorted())
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun WeekCell(
    week: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            // 48dp 满足无障碍最小触摸目标；toggleable(Checkbox) 让 TalkBack 播报"已选中/未选中"
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = week.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
    }
}
