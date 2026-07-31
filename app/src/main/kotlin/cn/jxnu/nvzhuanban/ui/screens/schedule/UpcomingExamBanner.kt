package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamStatus
import cn.jxnu.nvzhuanban.data.repository.ExamRepository
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * 课表顶部的"即将考试"横幅。仅在 7 天内（含今天）有考试时显示，点击跳考试列表。
 *
 * 数据复用 [ExamRepository] 的内存缓存：第一次进 App 时是 best-effort 一次性拉网络，失败 / 暂未拉到都
 * 不显示横幅（不打扰）；后续切 tab 回来直接命中缓存，0 网络。
 *
 * 设计取舍：故意不在 ScheduleViewModel 里持有考试 state，免得课表 VM 耦合考试领域；
 * 横幅是纯 UI 层一次性副作用，组件销毁时自然 cancel。
 */
@Composable
internal fun UpcomingExamBanner(onClick: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        // 跨过 00:00 时倒计时要减 1 天；和 ExamsScreen 同样的 60s 节拍
        while (true) {
            delay(60_000L)
            now = LocalDateTime.now()
        }
    }
    var exams by remember { mutableStateOf<List<Exam>?>(null) }
    LaunchedEffect(Unit) {
        exams = runCatching { ExamRepository.instance.fetchAll() }.getOrNull()
    }
    val nearest = exams?.asSequence()
        ?.filter { it.statusAt(now) != ExamStatus.FINISHED }
        ?.filter { it.daysLeftFrom(now) in 0..7 }
        ?.minByOrNull { it.startTime }
        ?: return
    val daysLeft = nearest.daysLeftFrom(now)
    val countWithin7 = exams.orEmpty().count {
        it.statusAt(now) != ExamStatus.FINISHED && it.daysLeftFrom(now) in 0..7
    }
    val labelLeft = when {
        daysLeft <= 0L -> "今天有 ${nearest.courseName} 考试"
        else -> "距 ${nearest.courseName} 考试还有 $daysLeft 天"
    }
    val labelRight = if (countWithin7 > 1) "近 7 天 $countWithin7 场" else "查看全部"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = if (daysLeft <= 0L) 1f else 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = labelLeft,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = labelRight,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
    }
}
