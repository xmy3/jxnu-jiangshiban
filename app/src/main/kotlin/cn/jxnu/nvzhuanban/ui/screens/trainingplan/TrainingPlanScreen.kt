package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.TrainingPlan
import cn.jxnu.nvzhuanban.data.model.TrainingPlanCourse
import cn.jxnu.nvzhuanban.data.model.TrainingPlanSection
import cn.jxnu.nvzhuanban.data.model.formatCredit
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(
    onBack: () -> Unit,
    viewModel: TrainingPlanViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(rememberTransientErrorSnackbar(viewModel.refreshFailed)) },
        topBar = {
            TopAppBar(
                title = { Text("培养方案") },
                navigationIcon = { BackNavigationIcon(onBack) },
                actions = {
                    RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding),
        ) {
            StateScaffold(
                state = state,
                onRetry = viewModel::load,
                loading = { m -> cn.jxnu.nvzhuanban.ui.components.ListSkeleton(modifier = m) },
            ) { plan ->
                if (plan.sections.isEmpty()) {
                    EmptyState("暂无培养方案课程")
                } else {
                    TrainingPlanList(plan = plan)
                }
            }
        }
    }
}

@Composable
private fun TrainingPlanList(plan: TrainingPlan) {
    // 展开状态提升到列表层，课程行才能拍平成独立的 LazyColumn item——
    // 大 section（通识/专业模块常见几十门课）展开后仍按需组合，而不是塞进单个巨型 item。
    var expandedTitles by rememberSaveable { mutableStateOf(setOf<String>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item { TrainingPlanSummary(plan = plan) }
        plan.sections.forEach { section ->
            // 空 section 不算展开，避免 header 顶角圆、底角悬空
            val expanded = section.title in expandedTitles && section.courses.isNotEmpty()
            item(key = section.title, contentType = "sectionHeader") {
                TrainingPlanSectionHeader(
                    section = section,
                    expanded = expanded,
                    onToggle = {
                        expandedTitles =
                            if (expanded) expandedTitles - section.title else expandedTitles + section.title
                    },
                    modifier = Modifier
                        .animateItem()
                        .padding(top = 12.dp),
                )
            }
            if (expanded) {
                // courseCode 可能为空/重复，用 section 内序号保证 key 唯一（列表随加载整体替换，序号稳定）
                itemsIndexed(
                    section.courses,
                    key = { index, _ -> "${section.title}#$index" },
                    contentType = { _, _ -> "course" },
                ) { index, course ->
                    TrainingPlanCourseItem(
                        course = course,
                        isLast = index == section.courses.lastIndex,
                        // fadeOutSpec = null：收起时课程行瞬时移除。默认的淡出动画会让被移除的
                        // 行原地变成半透明「幽灵」，与正在上滑补位的下一个分组头重叠，观感很乱。
                        // 展开方向保留淡入 + 位移，仍然是平滑的。
                        modifier = Modifier.animateItem(fadeOutSpec = null),
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun TrainingPlanSummary(plan: TrainingPlan) {
    val current = plan.currentCredits
    val minimum = plan.minimumCredits
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasDetails = plan.overallStandardScore != null ||
        plan.degreeCourseTotal != null ||
        plan.creditSummaries.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        current != null && minimum != null -> "毕业进度 ${current.formatCredit()}/${minimum.formatCredit()} 学分"
                        minimum != null -> "毕业最低 ${minimum.formatCredit()} 学分"
                        else -> "毕业进度"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (hasDetails) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // 显式纵向展开/收起：AnimatedVisibility 的默认转场是 expandIn/shrinkOut，
            // 会从左上角斜向裁剪展开，观感像内容从角落挤出来。这里限定只在竖直方向
            // 展开（从顶部生长）+ 淡入淡出，才是「下拉抽屉」的正常观感。
            AnimatedVisibility(
                visible = expanded && hasDetails,
                enter = fadeIn(animationSpec = tween(200)) +
                    expandVertically(animationSpec = tween(250), expandFrom = Alignment.Top),
                exit = fadeOut(animationSpec = tween(150)) +
                    shrinkVertically(animationSpec = tween(250), shrinkTowards = Alignment.Top),
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        plan.overallStandardScore?.let {
                            SummaryChip(text = "标准分 ${"%.3f".format(it)}")
                        }
                        plan.degreeCourseTotal?.let { total ->
                            val retaken = plan.retakenDegreeCourseCount ?: 0
                            SummaryChip(text = "学位课 $total 门 / 重修 $retaken 门次")
                        }
                    }
                    if (plan.creditSummaries.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            plan.creditSummaries.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = "${item.earnedCredits.formatCredit()} 学分",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
        border = null,
    )
}

@Composable
private fun TrainingPlanSectionHeader(
    section: TrainingPlanSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completed = section.courses.count { it.isCompleted }
    val showProgressBadge = !section.title.contains("大学英语特色课")
    // 展开时下方紧贴同底色的课程行 item，底部圆角交给最后一行课程补齐
    val shape = if (expanded) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(16.dp)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                section.requirement?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showProgressBadge) {
                Text(
                    text = "$completed/${section.courses.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun TrainingPlanCourseItem(
    course: TrainingPlanCourse,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (isLast) {
        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RectangleShape
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = if (isLast) 14.dp else 0.dp),
    ) {
        TrainingPlanCourseRow(course = course)
    }
}

@Composable
private fun TrainingPlanCourseRow(course: TrainingPlanCourse) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (course.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (course.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = course.courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${course.credit?.formatCredit() ?: "-"} 学分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val meta = remember(course) {
                listOfNotNull(
                    course.courseCode.takeIf { it.isNotBlank() },
                    course.category,
                    course.openingOrder?.let { "学期 $it" },
                    if (course.isDegreeCourse) "学位课" else null,
                ).joinToString(" · ")
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val scoreLine = remember(course) {
                listOfNotNull(
                    course.examScore?.let { "成绩 $it" },
                    course.makeupScore?.let { "补考 $it" },
                    course.examTime,
                    course.remark,
                ).joinToString(" · ")
            }
            if (scoreLine.isNotBlank()) {
                Text(
                    text = scoreLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
