package cn.jxnu.nvzhuanban.ui.screens.userschedule

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScheduleScreen(
    scheduleUrl: String,
    name: String,
    onBack: () -> Unit,
    viewModel: UserScheduleViewModel = viewModel(
        key = scheduleUrl,
        factory = UserScheduleViewModel.factory(scheduleUrl),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSwitching by viewModel.isSwitching.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 切学期失败保留旧课表，只用 Toast 一次性提示（跟 LoginScreen 的告警同款）
    LaunchedEffect(Unit) {
        viewModel.switchError.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (name.isBlank()) stringResource(R.string.user_schedule_title_fallback)
                        else stringResource(R.string.user_schedule_title_template, name)
                    )
                },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::load,
            loading = { m -> cn.jxnu.nvzhuanban.ui.components.ScheduleSkeleton(modifier = m) },
        ) { parsed ->
            ScheduleBody(
                parsed = parsed,
                isSwitching = isSwitching,
                onSelectSemester = viewModel::selectSemester,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBody(
    parsed: SchedulePage.Parsed,
    isSwitching: Boolean,
    onSelectSemester: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (parsed.semesters.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                parsed.semesters.forEach { sem ->
                    val selected = sem.label == parsed.semester
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected && !isSwitching) onSelectSemester(sem.value) },
                        label = { Text(sem.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            // 切学期是真实的 ASP.NET POST（秒级），chip 行下给个细进度条反馈
            if (isSwitching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (parsed.courses.isEmpty()) {
            EmptyState(message = stringResource(R.string.user_schedule_empty))
            return
        }

        val byDay = parsed.courses.groupBy { it.weekday }.toSortedMap()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            byDay.forEach { (weekday, dayCourses) ->
                item(key = "day-$weekday") {
                    DaySection(
                        weekday = weekday,
                        courses = dayCourses.sortedBy { it.startSection },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DaySection(weekday: Int, courses: List<Course>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = WEEKDAY_LABELS.getOrNull(weekday - 1) ?: "周$weekday",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                courses.forEach { course -> CourseRow(course = course) }
            }
        }
    }
}

@Composable
private fun CourseRow(course: Course) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${course.startSection}-${course.endSection} 节",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = SectionTimetable.startTimeLabel(course.startSection),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            // 教师视角下不显示 teacher（看的就是 ta 自己），改显示班级。
            // teacher 字段在外部课表（教师 / 学生）上是 dgStudentLesson 缺失时由正则
            // 从班级文本反推出来的，大概率是 "级计算机" 这种碎片，不能直接拿出来用。
            val meta = listOfNotNull(
                course.location.takeIf { it.isNotBlank() },
                course.className.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
