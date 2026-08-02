package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.data.model.EveningStudy

/**
 * 大一晚自习「选周几 + 填教室」编辑面板（样式对齐 [WeekEditorSheet]）。
 *
 * 可选 周一~周五 + 周日（[EveningStudy.ALLOWED_DAYS]，周六无晚自习），至多勾
 * [EveningStudy.MAX_DAYS] 天——选满后其余天格禁用。[fullyBlockedDays]（整学期晚间都被
 * 正课占满的天）也被禁用：勾了也合成不出卡，还会让「已设置但网格无卡」的状态丢失编辑入口。
 * 教室锁死 W 楼（[EveningStudy.ROOM_PREFIX]），选填、只输 [EveningStudy.ROOM_DIGITS] 位
 * 数字号；输了但不足位时禁止保存。
 *
 * [onSave] 收到 (days, roomDigits)：
 * - days 非空 → 用户的选择，保存后课表注入晚自习卡（roomDigits 非 null 时卡片带教室）；
 * - days 空集 → 清除设置（教室一并清），回到「未设置」占位态（按钮文案此时变「清除晚自习」）。
 */
@Composable
internal fun EveningStudySheet(
    initialDays: Set<Int>,
    initialRoomDigits: String?,
    fullyBlockedDays: Set<Int>,
    onCancel: () -> Unit,
    onSave: (days: Set<Int>, roomDigits: String?) -> Unit,
) {
    var selected by remember(initialDays) { mutableStateOf(initialDays) }
    var roomDigits by remember(initialRoomDigits) { mutableStateOf(initialRoomDigits.orEmpty()) }
    val roomIncomplete = roomDigits.isNotEmpty() && !EveningStudy.isValidRoomDigits(roomDigits)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = "晚自习",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${EveningStudy.START_LABEL} – ${EveningStudy.END_LABEL} · " +
                "周六没有晚自习，每周至多 ${EveningStudy.MAX_DAYS} 天",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EveningStudy.ALLOWED_DAYS.forEach { day ->
                val checked = day in selected
                DayCell(
                    label = WEEKDAY_LABELS.getOrElse(day - 1) { "?" },
                    checked = checked,
                    // 已勾的天永远可取消（含历史脏状态里的全占天）；未勾的天要求
                    // 未选满且整学期晚间没被正课占死
                    enabled = checked ||
                        (day !in fullyBlockedDays && selected.size < EveningStudy.MAX_DAYS),
                    onToggle = { selected = if (checked) selected - day else selected + day },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (fullyBlockedDays.isNotEmpty()) {
            Text(
                text = "灰色的天整学期晚上都有课，排不进晚自习",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = if (selected.isEmpty()) {
                "未选择 · 保存后课表不显示晚自习"
            } else {
                "已选 ${selected.size} 天 · " +
                    selected.sorted().joinToString("、") { "周${WEEKDAY_LABELS.getOrElse(it - 1) { "?" }}" }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        // 教室（选填）：楼栋锁死 W 楼，只输 4 位数字号，如 1203 → W1203。
        // 全角数字（粘贴常见）归一化为半角再过滤，而不是静默丢弃——用户粘「１２０３」时清空更困惑
        OutlinedTextField(
            value = roomDigits,
            onValueChange = { input ->
                roomDigits = input
                    .map { if (it in '０'..'９') it - 0xFEE0 else it }
                    .filter { it in '0'..'9' }
                    .joinToString("")
                    .take(EveningStudy.ROOM_DIGITS)
            },
            label = { Text("教室（选填）") },
            prefix = { Text(EveningStudy.ROOM_PREFIX) },
            placeholder = { Text("如 1203") },
            supportingText = if (roomIncomplete) {
                { Text("教室号需 ${EveningStudy.ROOM_DIGITS} 位数字") }
            } else null,
            isError = roomIncomplete,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
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
                    onSave(selected, roomDigits.takeIf { EveningStudy.isValidRoomDigits(it) })
                },
                // 未设置 + 0 选 = 无事可做；已设置时 0 选是合法的「清除」操作（清除连教室
                // 一起删，教室输入不完整也不阻塞）。保留天数时教室号输了但不足 4 位 → 禁存
                //（防静默丢弃用户输入）
                enabled = if (selected.isEmpty()) initialDays.isNotEmpty() else !roomIncomplete,
                modifier = Modifier.weight(1f),
            ) { Text(if (selected.isEmpty() && initialDays.isNotEmpty()) "清除晚自习" else "保存") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DayCell(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = when {
        checked -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            // 48dp 满足无障碍最小触摸目标；toggleable(Checkbox) 让 TalkBack 播报"已选中/未选中"
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
    }
}
