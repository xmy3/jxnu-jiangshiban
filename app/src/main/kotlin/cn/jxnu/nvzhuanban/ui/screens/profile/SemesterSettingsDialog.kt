package cn.jxnu.nvzhuanban.ui.screens.profile

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.storage.SemesterSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「学期与周次」设置弹窗。
 *
 * 解决的痛点：教务网学期下拉的 value 是**名义日期**（3/1、9/1），与星期无关。真实校历
 * 与名义日错开一周时（延期开学、补课周、校历调整），App 里的「第 N 周」会整体偏一周。
 * 这里把「第 1 周周一」和「总周数」交给用户校正。
 *
 * 数据由调用方传入（本组件不直接读仓库/存储），便于预览与测试。
 *
 * @param semesterLabel 当前学期标签（如 "25-26第2学期"），null = 课表还没加载出来。
 * @param nominalStart 教务给的名义开学日；null 时禁用保存（没有归属键，覆盖会无处生效）。
 * @param schoolTotalWeeks 教务解析出的总周数，不包含用户覆盖。
 * @param savedOverride 已保存的覆盖（且必须与 [nominalStart] 同一学期）；null = 未设置。
 */
@Composable
internal fun SemesterSettingsDialog(
    semesterLabel: String?,
    nominalStart: LocalDate?,
    schoolTotalWeeks: Int,
    savedOverride: SemesterSettings.Override?,
    onDismiss: () -> Unit,
    onSave: (weekOneMonday: LocalDate, totalWeeks: Int?) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val defaultMonday = remember(nominalStart) {
        nominalStart?.let { SemesterPhase.weekOneMonday(it) }
    }
    // 弹窗内的编辑态：初始值 = 已保存覆盖，否则 = 当前生效值（等价「原样保存」）。
    var monday by remember(nominalStart, savedOverride) {
        mutableStateOf(savedOverride?.weekOneMonday ?: defaultMonday)
    }
    var followSchool by remember(savedOverride) {
        mutableStateOf(savedOverride?.totalWeeks == null)
    }
    var weeks by remember(schoolTotalWeeks, savedOverride) {
        mutableIntStateOf(savedOverride?.totalWeeks ?: schoolTotalWeeks)
    }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val range = SemesterSettings.MIN_TOTAL_WEEKS..SemesterSettings.MAX_TOTAL_WEEKS
    val isMonday = monday?.dayOfWeek == DayOfWeek.MONDAY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期与周次") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "教务只提供“名义开学日”（每年 3/1、9/1），与星期无关。" +
                        "真实校历错开一周时，这里可以校正「第 1 周从哪天算起」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                InfoRow("当前学期", semesterLabel ?: "尚未加载课表")
                InfoRow("教务开学日", nominalStart?.format(dateFormatter) ?: "—")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("第 1 周周一", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = monday?.format(dateFormatter) ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val base = monday ?: defaultMonday ?: LocalDate.now()
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    monday = LocalDate.of(year, month + 1, day)
                                },
                                base.year,
                                base.monthValue - 1,
                                base.dayOfMonth,
                            ).show()
                        },
                        enabled = nominalStart != null,
                    ) { Text("选择日期") }
                }

                if (monday != null && !isMonday) {
                    Text("请选择周一作为第 1 周的起点", color = MaterialTheme.colorScheme.error)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column {
                        Text("学期总周数", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (followSchool) "跟随教务（当前 $schoolTotalWeeks 周）"
                            else "$weeks 周",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 「跟随教务」= 不写 totalWeeks 覆盖（null），步进器随之禁用，
                        // 避免出现「显示跟随但实际存了某个数」的自相矛盾状态。
                        TextButton(onClick = { followSchool = !followSchool }) {
                            Text(if (followSchool) "改为手动" else "跟随教务")
                        }
                        TextButton(
                            onClick = { weeks = (weeks - 1).coerceIn(range) },
                            enabled = !followSchool && weeks > range.first,
                        ) { Text("−") }
                        TextButton(
                            onClick = { weeks = (weeks + 1).coerceIn(range) },
                            enabled = !followSchool && weeks < range.last,
                        ) { Text("+") }
                    }
                }

                TextButton(
                    onClick = onReset,
                    enabled = nominalStart != null,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text("恢复默认（按名义日对齐最近周一）") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = monday ?: return@TextButton
                    onSave(picked, if (followSchool) null else weeks)
                },
                enabled = nominalStart != null && isMonday,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
