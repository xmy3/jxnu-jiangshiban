package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.TestGrade
import cn.jxnu.nvzhuanban.data.model.TestGradeGroup
import cn.jxnu.nvzhuanban.data.model.TestGradeReport
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

/**
 * 「考试出分」子 Tab 内容。
 *
 * 注意：不在这里放 TopAppBar / Scaffold —— 父 [GradesScreen] 已经提供了 Scaffold 与 TabRow，
 * 这里只负责填充 padding 之后的内容区。下拉刷新条独立。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestGradesContent(
    modifier: Modifier = Modifier,
    viewModel: TestGradesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    ) {
        StateScaffold(
            state = state,
            onRetry = viewModel::load,
            loading = { m -> cn.jxnu.nvzhuanban.ui.components.ListSkeleton(modifier = m) },
        ) { report ->
            if (report.groups.isEmpty()) {
                EmptyState(stringResource(R.string.test_grades_empty))
            } else {
                TestGradesList(report = report)
            }
        }
    }
}

@Composable
private fun TestGradesList(report: TestGradeReport) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ReportHeader(report) }
        report.groups.forEach { group ->
            item(key = "group_${group.title}", contentType = "groupHeader") { GroupHeader(group) }
            items(group.grades, key = { "${group.title}_${it.id}" }, contentType = { "testGrade" }) { g -> TestGradeRow(g) }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ReportHeader(report: TestGradeReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        if (report.pageTitle.isNotBlank()) {
            Text(
                text = report.pageTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        val subtitleParts = buildList {
            report.studentName?.let { add(it) }
            report.semesterDate?.let { add("考试学期 $it") }
        }
        if (subtitleParts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitleParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
        report.disclaimer?.takeIf { it.isNotBlank() }?.let { tip ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .height(14.dp),
                )
                Spacer(Modifier.padding(start = 6.dp))
                Text(
                    text = tip,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: TestGradeGroup) {
    Text(
        text = "${group.title} (${group.grades.size})",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun TestGradeRow(grade: TestGrade) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = grade.courseName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = grade.courseCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                grade.totalScore?.let { total ->
                    ScoreBadge(
                        label = "总评",
                        value = total,
                        emphasize = true,
                    )
                }
            }

            val secondaryScores = listOfNotNull(
                grade.regularScore?.let { "平时" to it },
                grade.midtermScore?.let { "期中" to it },
                grade.practiceScore?.let { "实践" to it },
                grade.finalExamScore?.let { "卷面" to it },
            )
            if (secondaryScores.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    secondaryScores.forEach { (label, value) ->
                        ScoreCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                }
            }

            val notes = listOfNotNull(
                grade.algorithmName?.takeIf { it.isNotBlank() }?.let { "算法 $it" },
                grade.examStatus?.takeIf { it.isNotBlank() }?.let { "考试情况 $it" },
                grade.remark?.takeIf { it.isNotBlank() },
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = notes.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScoreBadge(label: String, value: String, emphasize: Boolean) {
    // 总评徽章底色跟随分数语义：挂科（<60 或「不及格」等等级制挂科）用 error 容器——
    // 否则挂科总评与 95 分视觉完全相同，出分季翻列表毫无警示。
    // 0 分沿用「教师未填」语义（zeroMeansUnfilled），不算挂科。
    val num = value.toFloatOrNull()
    val failing = (num != null && num > 0f && num < 60f) ||
        (num == null && value.trim().startsWith("不"))
    val containerColor = when {
        emphasize && failing -> MaterialTheme.colorScheme.error
        emphasize -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val labelColor = when {
        emphasize && failing -> MaterialTheme.colorScheme.onError.copy(alpha = 0.85f)
        emphasize -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val valueColor = when {
        emphasize && failing -> MaterialTheme.colorScheme.onError
        emphasize -> MaterialTheme.colorScheme.onPrimary
        else -> scoreColor(value, zeroMeansUnfilled = true)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun ScoreCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scoreColor(value, zeroMeansUnfilled = true),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
