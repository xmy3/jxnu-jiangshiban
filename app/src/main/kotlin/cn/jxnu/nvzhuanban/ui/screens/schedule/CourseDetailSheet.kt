package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.model.formatCredit

/**
 * 课程详情 Sheet。
 *
 * @param onQueryTeacher 点「教师」行 → 去开课查询看这位老师本学期开的所有课。教师名为空时该行不可点。
 * @param onQueryClassroom 点「地点」行 → 去开课查询看这间教室本学期的占用。教室为空时该行不可点。
 *   两个回调都由上层负责关闭本 Sheet 再导航。
 */
@Composable
internal fun CourseDetailSheet(
    course: Course,
    weekTotal: Int,
    onEditWeeks: () -> Unit,
    onQueryTeacher: (String) -> Unit = {},
    onQueryClassroom: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        // 标题：色块 + 课程名。色块取这门课的色相强色（亮=深字色、暗=卡底色，见 CourseSwatch.accent），
        // 与网格里卡片同色相
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(courseSwatch(course).accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = course.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        // 详情行
        // 「教师」「地点」可点 → 去开课查询看这位老师 / 这间教室本学期的开课；值为空则退回不可点普通行。
        val teacher = course.teacher.trim()
        if (teacher.isNotBlank()) {
            ActionableDetailRow(
                icon = Icons.Outlined.Person,
                label = "教师",
                value = teacher,
                actionHint = "查 TA 的课",
                onClick = { onQueryTeacher(primaryTeacherName(teacher)) },
            )
        } else {
            DetailRow(icon = Icons.Outlined.Person, label = "教师", value = "—")
        }
        val location = course.location.trim()
        if (location.isNotBlank()) {
            ActionableDetailRow(
                icon = Icons.Outlined.Place,
                label = "地点",
                value = location,
                actionHint = "查教室占用",
                onClick = { onQueryClassroom(location) },
            )
        } else {
            DetailRow(icon = Icons.Outlined.Place, label = "地点", value = "—")
        }
        DetailRow(
            icon = Icons.Outlined.AccessTime,
            label = "节次",
            value = "周${WEEKDAY_LABELS.getOrElse(course.weekday - 1) { "?" }} · 第 ${course.startSection}-${course.endSection} 节 (${sectionTimeRange(course.startSection, course.endSection)})",
        )
        DetailRow(
            icon = Icons.Outlined.CalendarMonth,
            label = "周次",
            value = formatWeeks(course.weeks, weekTotal),
        )
        // 学分由 ScheduleRepository.enrichWithCredits 异步从成绩页补上；
        // 首次进 App 还没补全 / 历史从未修过这门课时 credit 仍为 0，此时不显示该标签
        if (course.credit > 0f) {
            Spacer(Modifier.height(12.dp))
            MetaTag(text = "${course.credit.formatCredit()} 学分")
        }
        Spacer(Modifier.height(20.dp))
        // 教务网 HTML 不给周次细节，所有课默认 1..18 周；这里给用户一个本地编辑入口
        OutlinedButton(
            onClick = onEditWeeks,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("编辑周次")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * 可点的详情行：右侧带「查 TA 的课 ›」类提示 chip，点击整行触发 [onClick]。
 * 用于「教师」「地点」——跳转开课查询。视觉上与 [DetailRow] 对齐，多一个圆角高亮背景 + 前进箭头暗示可点。
 */
@Composable
private fun ActionableDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    actionHint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Role.Button + onClickLabel：与打开本 Sheet 的课程卡（ScheduleGrid）的语义声明对齐，
            // TalkBack 播报「按钮 · 双击即可<actionHint>」而不是裸文本
            .clickable(onClickLabel = actionHint, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = actionHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 从课表教师字段里取**首个**教师名，喂给开课查询的「教师姓名」模糊框。
 *
 * 合班课的 teacher 常是多名拼接（「张三 李四」「张三,李四」「张三、李四」），
 * 而开课查询按单个姓名模糊匹配，整串塞进去会查不到。取首名即可命中——开课查询本就
 * 会把这位老师参与的所有开课都列出来（含该合班课）。单名场景原样返回，不受影响。
 */
internal fun primaryTeacherName(raw: String): String =
    raw.trim().split(' ', '\t', ',', '，', '、', '/', ';', '；', '　')
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()


@Composable
private fun MetaTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun sectionTimeRange(startSection: Int, endSection: Int): String =
    "${SectionTimetable.startTimeLabel(startSection)}–${SectionTimetable.endTimeLabel(endSection)}"

/**
 * 把上课周列表压缩成可读字符串：
 *   [1,2,3,4,5,6,7,8] → "1-8 周"
 *   [1,3,5,7,9,11,13,15] → "1-15 周(单)"
 *   [2,4,6,8,10] → "2-10 周(双)"
 *   [1,2,3,7,8,9] → "1-3,7-9 周"
 */
internal fun formatWeeks(weeks: List<Int>, totalWeeks: Int): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val first = sorted.first()
    val last = sorted.last()
    val expected = (first..last).toList()
    // 全部连续
    if (sorted == expected) return "$first-$last 周"
    // 全部奇数
    val odds = expected.filter { it % 2 == 1 }
    if (sorted == odds) return "$first-$last 周(单)"
    // 全部偶数
    val evens = expected.filter { it % 2 == 0 }
    if (sorted == evens) return "$first-$last 周(双)"
    // 通用：按连续段聚合
    val segments = mutableListOf<IntRange>()
    var segStart = sorted.first()
    var prev = segStart
    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            segments += segStart..prev
            segStart = w
            prev = w
        }
    }
    segments += segStart..prev
    return segments.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" } + " 周"
}
