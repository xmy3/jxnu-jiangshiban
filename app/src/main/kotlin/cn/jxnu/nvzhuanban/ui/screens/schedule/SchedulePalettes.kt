package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cn.jxnu.nvzhuanban.data.model.Course

/**
 * 课表课程卡配色：固定亮 / 暗两套 12 色，随应用实际生效的主题自动切换，无用户手选
 * （早期曾提供五套手选方案，现已移除，旧 prefs key `schedule_palette` 残留无害）。
 *
 * - 亮色 = Material 700/800 档经典多色（即最初唯一的一套 COURSE_PALETTE，经典课表软件的
 *   高饱和明快风，浅色主题下升级零变化）；
 * - 暗色 = **同下标同色相**的压暗降饱和版——经典课表软件的暗色处理方式：卡片在近黑背景上
 *   不刺眼、又保有色相区分度。
 *
 * **硬约束：两套色板的所有颜色都必须保证其上白色 9sp 小字对比度 ≥ 4.5:1（WCAG AA）**——
 * 课程卡文字始终是白色（[CourseCard]），"上课中/下节"角标又反过来把色值当白底上的文字色用，
 * 两个方向的对比度是同一个比值。调整任何色值前先过 SchedulePaletteContrastTest
 * （JVM 单测，CI 强制），浅色 300 系 + 白字曾低至 1.7:1，别回去。
 * 两套色板长度必须一致且同下标同色相（同测试断言长度）：亮暗切换时同名课程保持
 * 同一色相，"哪两门课撞色"的相对关系不变。
 */
internal val LightCourseColors: List<Color> = listOf(
    Color(0xFFD32F2F), // Red 700
    Color(0xFF7B1FA2), // Purple 700
    Color(0xFF303F9F), // Indigo 700
    Color(0xFF0277BD), // Light Blue 800
    Color(0xFF00796B), // Teal 700
    Color(0xFF2E7D32), // Green 800
    Color(0xFFBF360C), // Deep Orange 900
    Color(0xFF6D4C41), // Brown 600
    Color(0xFF546E7A), // Blue Grey 600
    Color(0xFFC2185B), // Pink 700
    Color(0xFF5E35B1), // Deep Purple 600
    Color(0xFF1976D2), // Blue 700
)

internal val DarkCourseColors: List<Color> = listOf(
    Color(0xFFA6423E), // 砖红（← Red 700）
    Color(0xFF8A4E9E), // 雾紫（← Purple 700）
    Color(0xFF4F5AA8), // 暮靛（← Indigo 700）
    Color(0xFF2F6E96), // 钢蓝（← Light Blue 800）
    Color(0xFF26766B), // 墨青（← Teal 700）
    Color(0xFF3E7A44), // 松绿（← Green 800）
    Color(0xFF9E5030), // 赭橙（← Deep Orange 900）
    Color(0xFF755A50), // 暖褐（← Brown 600）
    Color(0xFF5B7280), // 蓝灰（← Blue Grey 600）
    Color(0xFFA83A66), // 干玫瑰（← Pink 700）
    Color(0xFF6650A4), // 鸢尾紫（← Deep Purple 600）
    Color(0xFF3A6EA8), // 雾海蓝（← Blue 700）
)

/**
 * 通过课程名称的稳定 hash 派生颜色，让同名课程颜色一致；亮暗两套同下标同色相，
 * 主题切换不会打乱"哪两门课撞色"的相对关系。
 *
 * 暗色判定读**已应用的 MaterialTheme 背景亮度**，而不是复刻 Theme.kt 里 ThemeMode 的解析——
 * 与实际生效主题（SYSTEM 跟随 / LIGHT / DARK 强制、Material You 动态取色）天然一致，不会漂移。
 */
@Composable
@ReadOnlyComposable
internal fun courseColor(course: Course): Color {
    val colors =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkCourseColors
        else LightCourseColors
    // 用 and Int.MAX_VALUE 取非负：避免 hashCode == Int.MIN_VALUE 时 `-it` 仍为负 → % 出负 index → 越界
    val idx = (course.name.hashCode() and Int.MAX_VALUE) % colors.size
    return colors[idx]
}
