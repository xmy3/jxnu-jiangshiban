package cn.jxnu.nvzhuanban.ui.screens.exams

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.School
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamStatus
import cn.jxnu.nvzhuanban.data.model.MakeupExam
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EXAM_DATE_FMT = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(
    onBack: () -> Unit = {},
    viewModel: ExamsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    // statusAt/daysLeftFrom 只按日期推理（教务返回的具体时间不可靠），
    // "当前时间"只在跨过 00:00 时才产生可见变化，睡到下个零点再刷新即可
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            val nextMidnight = LocalDateTime.now().toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(LocalDateTime.now(), nextMidnight).toMillis().coerceAtLeast(1_000L))
            value = LocalDateTime.now()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(rememberTransientErrorSnackbar(viewModel.refreshFailed)) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exams_title)) },
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
            ) { bundle ->
                if (bundle.regular.isEmpty() && bundle.makeup.isEmpty()) {
                    EmptyState(stringResource(R.string.exams_empty))
                } else {
                    ExamsList(bundle = bundle, now = now)
                }
            }
        }
    }
}

@Composable
private fun ExamsList(bundle: ExamsBundle, now: LocalDateTime) {
    // exams 列表本身基本不变（除非用户主动刷新），now 由分钟级 tick 推上来；
    // 把 partition 缓在 (exams, now) 上，分钟内的多次重组就不再走两遍线性扫描。
    // 已结束分组倒序（最近考完的在最前）：学期末查昨天考试的座位号/成绩核对最常见，
    // 不该让它沉在十几条开学初的旧记录底下。
    val (upcoming, finished) = remember(bundle.regular, now) {
        val (up, fin) = bundle.regular.partition { it.statusAt(now) != ExamStatus.FINISHED }
        up to fin.asReversed()
    }
    val makeup = bundle.makeup
    // 补缓考列表默认折叠：常规情况下用户更关心的是即将到来的考试，补缓考通常零星出现，
    // 把它压到一个可点击的标题里既保留入口又不抢焦点。rememberSaveable 让 Compose 重建后保持状态。
    var makeupExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 补缓考无可靠日期、且通常即将发生 / 高优先级，置于即将到来之前突出显示
        if (makeup.isNotEmpty()) {
            item(contentType = "sectionHeader") {
                MakeupSectionHeader(
                    count = makeup.size,
                    expanded = makeupExpanded,
                    onToggle = { makeupExpanded = !makeupExpanded },
                )
            }
            if (makeupExpanded) {
                items(makeup, key = { "mk-${it.id}" }, contentType = { "makeup" }) { MakeupExamCard(exam = it) }
            }
        }
        if (upcoming.isNotEmpty()) {
            item(contentType = "sectionLabel") {
                if (makeup.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                SectionLabel(text = "即将到来 (${upcoming.size})")
            }
            items(upcoming, key = { "ex-${it.id}" }, contentType = { "exam" }) { ExamCard(exam = it, now = now) }
        }
        if (finished.isNotEmpty()) {
            item(contentType = "sectionLabel") {
                Spacer(modifier = Modifier.height(8.dp))
                SectionLabel(text = "已结束 (${finished.size})")
            }
            items(finished, key = { "ex-${it.id}" }, contentType = { "exam" }) { ExamCard(exam = it, now = now) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun MakeupSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.makeup_exam_section_title, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.makeup_exam_collapse else R.string.makeup_exam_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ExamCard(exam: Exam, now: LocalDateTime) {
    val status = exam.statusAt(now)
    val isFinished = status == ExamStatus.FINISHED

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFinished)
                MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountdownBadge(exam = exam, now = now)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFinished) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 教务系统返回的具体时间不可靠（往往是 00:00），UI 只显示日期
                ExamMetaRow(
                    icon = Icons.Outlined.AccessTime,
                    text = exam.startTime.format(EXAM_DATE_FMT),
                )
                ExamMetaRow(icon = Icons.Outlined.Place, text = exam.location)
                exam.seat?.let { seat -> ExamMetaRow(icon = Icons.Outlined.EventSeat, text = seat) }
                exam.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                    ExamMetaRow(icon = Icons.AutoMirrored.Outlined.Notes, text = remark)
                }
            }
        }
    }
}

@Composable
private fun ExamMetaRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.height(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountdownBadge(exam: Exam, now: LocalDateTime) {
    val status = exam.statusAt(now)
    val days = exam.daysLeftFrom(now)
    val (label, bg, fg) = when (status) {
        ExamStatus.FINISHED -> Triple(stringResource(R.string.exams_finished),
            MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        ExamStatus.TODAY -> Triple(stringResource(R.string.exams_today),
            MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        ExamStatus.UPCOMING -> Triple(stringResource(R.string.exams_countdown_days, days),
            MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
    }
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (status == ExamStatus.UPCOMING) {
                Text(
                    text = days.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                )
                Text(
                    text = "天后",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun MakeupExamCard(exam: MakeupExam) {
    // 补缓考没有可靠的具体时间字段（教务网常留空），真正的时间地点在「考试方式」自然语言段里；
    // UI 不做倒计时徽章，转而用一个左侧色条 + 顶部"补考/缓考"标签突出性质。
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                MakeupKindBadge(remark = exam.remark)
            }
            Spacer(Modifier.height(8.dp))
            // 「考试方式」一列实际是完整的考试通知文案（时间 + 地点 + 联系电话），权重最高
            exam.examMethod?.let { ExamMetaRow(icon = Icons.Outlined.AccessTime, text = it) }
            // 教务网"考试时间"/"教室号"列经常为空，有值才显示
            exam.examTime?.let { ExamMetaRow(icon = Icons.Outlined.AccessTime, text = it) }
            exam.location?.let { ExamMetaRow(icon = Icons.Outlined.Place, text = it) }

            val managing = buildString {
                if (exam.courseType.isNotBlank()) append(exam.courseType)
                if (exam.managingDept.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(exam.managingDept)
                }
            }
            if (managing.isNotBlank()) ExamMetaRow(icon = Icons.Outlined.School, text = managing)
            if (exam.courseCode.isNotBlank()) {
                ExamMetaRow(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    text = "课程号 ${exam.courseCode}",
                )
            }
        }
    }
}

@Composable
private fun MakeupKindBadge(remark: String?) {
    val label = remark?.takeIf { it.isNotBlank() } ?: stringResource(R.string.makeup_exam_kind_default)
    // 缓考通常是合理事由暂缓，跟"补考"的色彩区分一下：补考用 primary，缓考用 tertiary
    val isMakeup = label.contains("补")
    val bg = if (isMakeup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val fg = if (isMakeup) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}
