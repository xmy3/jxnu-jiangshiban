package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Grade
import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.model.formatCredit
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.ListSkeleton
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar
import cn.jxnu.nvzhuanban.ui.theme.AppShape

private enum class GradesTab(val labelRes: Int) {
    Semester(R.string.grades_tab_semester),
    Test(R.string.grades_tab_test),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    onBack: () -> Unit = {},
    semesterViewModel: GradesViewModel = viewModel(),
    testViewModel: TestGradesViewModel = viewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { GradesTab.entries.toList() }
    val currentTab = tabs[selectedTab]

    // 顶部刷新按钮按当前 Tab 联动；两个 Tab 的下拉刷新独立，互不影响转圈。
    val semesterRefreshing by semesterViewModel.isRefreshing.collectAsStateWithLifecycle()
    val testRefreshing by testViewModel.isRefreshing.collectAsStateWithLifecycle()
    val activeRefreshing = if (currentTab == GradesTab.Semester) semesterRefreshing else testRefreshing
    val onActiveRefresh: () -> Unit = {
        if (currentTab == GradesTab.Semester) semesterViewModel.refresh() else testViewModel.refresh()
    }

    Scaffold(
        // 两个 Tab 的刷新失败提示共用一个 host——出分季反复下拉「考试出分」时，
        // 网络失败必须可见，否则会被误读成「还没出分」
        snackbarHost = {
            SnackbarHost(
                rememberTransientErrorSnackbar(semesterViewModel.refreshFailed, testViewModel.refreshFailed),
            )
        },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.grades_title)) },
                    navigationIcon = { BackNavigationIcon(onBack) },
                    actions = {
                        RefreshIconButton(isRefreshing = activeRefreshing, onClick = onActiveRefresh)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 按 Tab 保存/恢复子树状态，互切时两个列表的滚动位置不丢
        val stateHolder = rememberSaveableStateHolder()
        Box(modifier = Modifier.padding(padding)) {
            stateHolder.SaveableStateProvider(currentTab.name) {
                when (currentTab) {
                    GradesTab.Semester -> SemesterGradesContent(viewModel = semesterViewModel)
                    GradesTab.Test -> TestGradesContent(viewModel = testViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterGradesContent(viewModel: GradesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        StateScaffold(
            state = state,
            onRetry = viewModel::load,
            loading = { m -> ListSkeleton(modifier = m, showHeroCard = true) },
        ) { data ->
            val semesters = data.semesters
            if (semesters.isEmpty()) {
                EmptyState(stringResource(R.string.grades_empty))
            } else {
                GradesList(
                    semesters = semesters,
                    graduationMinimumCredits = data.graduationMinimumCredits,
                )
            }
        }
    }
}

@Composable
private fun GradesList(
    semesters: List<SemesterSummary>,
    graduationMinimumCredits: Float?,
) {
    // 聚合统计放 remember 里 —— LazyColumn 滚动时 GradesList 频繁重组，
    // semesters 通常 6-8 个学期、几十门课，每次重算 sumOf/flatMap 没什么压力但完全没必要。
    val aggregates = remember(semesters) {
        val allGrades = semesters.flatMap { it.grades }
        val totalCredit = allGrades.sumOf { it.credit.toDouble() }.toFloat()
        val totalWeighted = allGrades.mapNotNull { it.weightedGpa }.sum()
        val gpaCredit = allGrades.filter { it.gpa != null }.sumOf { it.credit.toDouble() }.toFloat()
        val cumGpa = if (gpaCredit > 0) totalWeighted / gpaCredit else 0f
        Triple(totalCredit, cumGpa, allGrades.size)
    }
    val (totalCredit, cumGpa, courseCount) = aggregates

    // 数字进场动画的 started 标志必须放在 LazyColumn 外：header item 无 key 且列表很长，
    // 滚出视口后 item 被销毁、item 内的 remember 会丢，滚回顶部就会重播一遍 0 起动画。
    // 提到这里后生命周期跟随整个列表，动画只在首次进屏播一次。
    var statsStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { statsStarted = true }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GpaHeaderCard(
                gpa = cumGpa,
                totalCredit = totalCredit,
                graduationMinimumCredits = graduationMinimumCredits,
                courseCount = courseCount,
                started = statsStarted,
            )
        }
        // 至少两个学期才构成趋势；单学期时不渲染（组件内部也会再判一次）
        if (semesters.size >= 2) {
            item(key = "trend", contentType = "trend") {
                SemesterTrendCard(semesters = semesters)
            }
        }
        semesters.forEach { sem ->
            item(key = "header_${sem.semester}", contentType = "semHeader") { SemesterHeader(sem) }
            items(sem.grades, key = { it.id }, contentType = { "grade" }) { g ->
                GradeRow(g, modifier = Modifier.animateItem())
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun GpaHeaderCard(
    gpa: Float,
    totalCredit: Float,
    graduationMinimumCredits: Float?,
    courseCount: Int,
    started: Boolean,
) {
    // 数字进场滚动：首帧从 0 起动画到真值（animate*AsState 首次组合直接落在 target，
    // 需要靠调用方传入的 started 标志把首帧钉在 0——标志活在 LazyColumn 外，见 GradesList）。
    // 之后 refresh 带来的数值变化也会平滑过渡。
    val numberSpec = tween<Float>(durationMillis = 700)
    val animatedGpa by animateFloatAsState(
        targetValue = if (started) gpa else 0f, animationSpec = numberSpec, label = "gpa",
    )
    val animatedCredit by animateFloatAsState(
        targetValue = if (started) totalCredit else 0f, animationSpec = numberSpec, label = "credit",
    )
    val animatedCount by animateIntAsState(
        targetValue = if (started) courseCount else 0,
        animationSpec = tween(durationMillis = 700), label = "count",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.heroCard)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    ),
                ),
            )
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatCol(
                    label = stringResource(R.string.grades_gpa_label),
                    value = "%.2f".format(animatedGpa),
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider()
                StatCol(
                    label = stringResource(R.string.grades_credit_label),
                    value = animatedCredit.formatCredit(),
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider()
                StatCol(
                    label = stringResource(R.string.grades_count_label),
                    value = animatedCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            graduationMinimumCredits?.takeIf { it > 0f }?.let { minimum ->
                val percent = ((totalCredit / minimum) * 100).toInt().coerceIn(0, 999)
                Text(
                    text = "毕业最低 ${minimum.formatCredit()} 学分 · 已完成 $percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun StatCol(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 36.dp)
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)),
    )
}

@Composable
private fun SemesterHeader(sem: SemesterSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sem.semester,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("加权平均标准分 %.2f".format(sem.gpa)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            AssistChip(
                onClick = {},
                label = { Text("${sem.totalCredit.formatCredit()} 学分") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun GradeRow(grade: Grade, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = grade.courseName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                grade.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = remark,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(text = "${grade.credit.formatCredit()} 学分", primary = false)
                    grade.makeupScore?.takeIf { it.isNotBlank() }?.let { m ->
                        Tag(text = "补考 $m", primary = false, warning = true)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = grade.score,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(grade.score),
                )
                grade.gpa?.let { g ->
                    Text(
                        text = "标准分 %.1f".format(g),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun Tag(text: String, primary: Boolean, warning: Boolean = false) {
    val containerColor = when {
        warning -> MaterialTheme.colorScheme.errorContainer
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        warning -> MaterialTheme.colorScheme.onErrorContainer
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier
            .clip(AppShape.tag)
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
