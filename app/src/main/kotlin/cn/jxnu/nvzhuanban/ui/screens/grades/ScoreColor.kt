package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 「学期成绩」与「考试出分」两个 Tab 共用的分数配色阶——两者同屏共存，
 * 阈值/颜色必须保持视觉一致，改动只在这里做一次。
 *
 * [zeroMeansUnfilled] 为 true 时把 0 分当作"教师未填"而非不及格（考试出分页的语义）。
 */
@Composable
internal fun scoreColor(score: String, zeroMeansUnfilled: Boolean = false): Color {
    val num = score.toFloatOrNull()
    return when {
        // 等级制挂科（不及格 / 不合格 / 不通过）必须与低分同档警示，不能混进"良好"色
        num == null && score.trim().startsWith("不") -> MaterialTheme.colorScheme.error
        num == null -> MaterialTheme.colorScheme.tertiary  // "良好"、"通过" 等
        zeroMeansUnfilled && num == 0f -> MaterialTheme.colorScheme.onSurfaceVariant
        num >= 90 -> MaterialTheme.colorScheme.primary
        num >= 80 -> MaterialTheme.colorScheme.tertiary
        num >= 60 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
}
