package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cn.jxnu.nvzhuanban.data.model.Course

/**
 * 课表课程卡配色：固定亮 / 暗两套 12 组，随应用实际生效的主题自动切换，无用户手选。
 *
 * 每组是一对 [CourseSwatch]（container 卡底 + onContainer 卡上文字/描边）：
 * - 亮色 = **马卡龙浅底 + 同色相深字**，浅色主题下整页轻盈不糊眼；
 * - 暗色 = 压暗降饱和深底 + 白字，卡片在近黑背景上不刺眼、又保有色相区分度。
 *
 * **硬约束：每组 container ↔ onContainer 对比度 ≥ 4.5:1（WCAG AA，9sp 小字）**——
 * 课程卡文字用 onContainer，「上课中/下节」角标把两色反转用（onContainer 当底、
 * container 当字），对比度是同一个比值，一条断言覆盖两个方向。调整任何色值前先过
 * SchedulePaletteContrastTest（JVM 单测，CI 强制）。
 * 两套色板长度必须一致且同下标同色相（同测试断言长度）：亮暗切换时同名课程保持
 * 同一色相，「哪两门课撞色」的相对关系不变。
 */
internal data class CourseSwatch(
    val container: Color,
    val onContainer: Color,
) {
    /**
     * 携带色相的强色，给详情 Sheet 色条这类小面积点缀用：
     * 亮套取深字色（淡底在白 Sheet 上显不出色），暗套取卡底色本身（onContainer 是白，无色相）。
     */
    val accent: Color
        get() = if (container.luminance() > 0.5f) onContainer else container
}

internal val LightCourseColors: List<CourseSwatch> = listOf(
    CourseSwatch(Color(0xFFFFD9D4), Color(0xFF8C1D18)), // 珊瑚红（← Red）
    CourseSwatch(Color(0xFFF1DAF6), Color(0xFF6A1B9A)), // 藕紫（← Purple）
    CourseSwatch(Color(0xFFDEE1FB), Color(0xFF303F9F)), // 长春花（← Indigo）
    CourseSwatch(Color(0xFFCBE6F8), Color(0xFF01579B)), // 天蓝（← Light Blue）
    CourseSwatch(Color(0xFFBEE8E0), Color(0xFF00584C)), // 薄荷（← Teal）
    CourseSwatch(Color(0xFFC9E7C6), Color(0xFF1E5E24)), // 抹茶（← Green）
    CourseSwatch(Color(0xFFFFDCC9), Color(0xFF8A2F0B)), // 蜜桃（← Deep Orange）
    CourseSwatch(Color(0xFFE9DBD3), Color(0xFF4E342E)), // 奶咖（← Brown）
    CourseSwatch(Color(0xFFD7E2E9), Color(0xFF37474F)), // 青灰（← Blue Grey）
    CourseSwatch(Color(0xFFFFD7E5), Color(0xFF8E1D53)), // 樱粉（← Pink）
    CourseSwatch(Color(0xFFE5DCF9), Color(0xFF4527A0)), // 香芋（← Deep Purple）
    CourseSwatch(Color(0xFFD3E3FD), Color(0xFF0D47A1)), // 雾蓝（← Blue）
)

internal val DarkCourseColors: List<CourseSwatch> = listOf(
    CourseSwatch(Color(0xFFA6423E), Color.White), // 砖红（← Red）
    CourseSwatch(Color(0xFF8A4E9E), Color.White), // 雾紫（← Purple）
    CourseSwatch(Color(0xFF4F5AA8), Color.White), // 暮靛（← Indigo）
    CourseSwatch(Color(0xFF2F6E96), Color.White), // 钢蓝（← Light Blue）
    CourseSwatch(Color(0xFF26766B), Color.White), // 墨青（← Teal）
    CourseSwatch(Color(0xFF3E7A44), Color.White), // 松绿（← Green）
    CourseSwatch(Color(0xFF9E5030), Color.White), // 赭橙（← Deep Orange）
    CourseSwatch(Color(0xFF755A50), Color.White), // 暖褐（← Brown）
    CourseSwatch(Color(0xFF5B7280), Color.White), // 蓝灰（← Blue Grey）
    CourseSwatch(Color(0xFFA83A66), Color.White), // 干玫瑰（← Pink）
    CourseSwatch(Color(0xFF6650A4), Color.White), // 鸢尾紫（← Deep Purple）
    CourseSwatch(Color(0xFF3A6EA8), Color.White), // 雾海蓝（← Blue）
)

/**
 * 通过课程名称的稳定 hash 派生配色，让同名课程颜色一致；亮暗两套同下标同色相，
 * 主题切换不会打乱"哪两门课撞色"的相对关系。
 *
 * 暗色判定读**已应用的 MaterialTheme 背景亮度**，而不是复刻 Theme.kt 里 ThemeMode 的解析——
 * 与实际生效主题（SYSTEM 跟随 / LIGHT / DARK 强制、Material You 动态取色）天然一致，不会漂移。
 */
@Composable
@ReadOnlyComposable
internal fun courseSwatch(course: Course): CourseSwatch {
    val palette =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkCourseColors
        else LightCourseColors
    // 用 and Int.MAX_VALUE 取非负：避免 hashCode == Int.MIN_VALUE 时 `-it` 仍为负 → % 出负 index → 越界
    val idx = (course.name.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}
