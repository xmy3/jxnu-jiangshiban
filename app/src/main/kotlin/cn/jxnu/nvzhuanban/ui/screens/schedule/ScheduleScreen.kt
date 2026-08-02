package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.EveningStudy
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.model.isEveningStudy
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal val LEFT_LABEL_WIDTH = 36.dp
internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 计算需要整列隐藏的星期几。规则（周六是主导，绝大多数学期周六无课）：
 * - 周六整学期无课 → 收起周六；此时周日若也无课 → 一起收起（{6,7}），周日有课则只收周六（{6}）。
 * - 周六有课 → 什么都不收——**周日绝不单独收起**（即使周日整学期无课）。
 * 收起的列直接从表头和网格中移除，剩余列 weight 平分变宽。
 * 判断依据是**全学期**课表（非当前周），避免逐周切换时列宽反复跳动。
 * 空课表（加载中/无数据）返回空集，不收起。
 */
internal fun computeFoldedDays(courses: List<Course>): Set<Int> {
    if (courses.isEmpty()) return emptySet()
    val satEmpty = courses.none { it.weekday == 6 }
    if (!satEmpty) return emptySet()
    val sunEmpty = courses.none { it.weekday == 7 }
    return if (sunEmpty) setOf(6, 7) else setOf(6)
}

/**
 * 离线提示条：当 [ScheduleScreenState.isOffline] 为真（本次拉取失败、展示的是磁盘缓存）时，
 * 在表头下方显示一条说明，告诉用户这是上次的课表、下拉可重试，避免误以为是实时数据。
 */
@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "无网络 · 显示上次缓存的课表，下拉可重试",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 假期横幅：今天不在正在查看的学期的任何教学周内时显示（[ScheduleScreenState.vacation] 非 null）。
 * - 看已结束的本学期（寒暑假打开课表的默认态）→「假期中 · 距开学还有 X 天」+「看下学期」入口；
 *   教务还没放出下学期选项时只显示「本学期已结束 · 假期中」。
 * - 看尚未开学的学期 → 该学期自己的开学倒计时。
 * [today] 是可观察状态（跨零点自刷新），倒计时天数会自动走。
 */
@Composable
private fun VacationBanner(
    info: VacationInfo,
    today: LocalDate,
    onViewNext: (String) -> Unit,
) {
    val days = info.nextStartDate?.let { ChronoUnit.DAYS.between(today, it) }
    val text = when {
        !info.semesterEnded -> when {
            days == null -> "该学期尚未开学"
            days <= 0L -> "即将开学"
            else -> "距开学还有 $days 天"
        }
        days != null && days > 0L -> "假期中 · 距开学还有 $days 天"
        else -> "本学期已结束 · 假期中"
    }
    // 纵向 4.dp（行）+ 4.dp（正文自带）= 8.dp，与「看下学期」按钮的 4.dp 内边距互相抵消，
    // 有无该按钮两种形态的横幅总高度保持一致。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.BeachAccess,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        val nextValue = info.nextSemesterValue
        if (info.semesterEnded && nextValue != null) {
            Text(
                text = "看下学期 ›",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onViewNext(nextValue) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenExams: () -> Unit = {},
    /**
     * 跳开课查询：教师名 / 教室号至多给一个（另一个传 null），用于自动查这位老师的课或这间教室
     * 的占用；semesterIsoDate 是当前查看学期的开学日 ISO 串（如 `2026-03-01`，离线兜底时为 null），
     * 让开课查询对齐到同一学期——其表单默认学期是「最新」，学期末教务放出下学期后两者会岔开。
     */
    onOpenCourseOffering: (teacher: String?, classroom: String?, semesterIsoDate: String?) -> Unit = { _, _, _ -> },
    viewModel: ScheduleViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    var editingWeeksFor by remember { mutableStateOf<Course?>(null) }
    var showSemesterSheet by remember { mutableStateOf(false) }
    var showEveningSheet by remember { mutableStateOf(false) }

    // 课程详情 / 学期选择 / 周次编辑 / 晚自习 sheet 开着时拦截系统返回键 → 先关 sheet 而不是退出 App。
    // ModalBottomSheet 自身在新版会响应返回键调 onDismissRequest，这里显式拦截做兜底
    BackHandler(enabled = editingWeeksFor != null) {
        editingWeeksFor = null
    }
    BackHandler(enabled = selectedCourse != null && editingWeeksFor == null) {
        selectedCourse = null
    }
    BackHandler(enabled = showSemesterSheet) {
        showSemesterSheet = false
    }
    BackHandler(enabled = showEveningSheet) {
        showEveningSheet = false
    }

    // 「今天」做成可观察状态：页面驻留跨过 00:00（尤其周日→周一）后，「今」列头、今日列底色
    // 和"上课中/下节"高亮都要跟着走。到点校准一次而不是每分钟轮询。
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())
            delay(untilMidnight.toMillis() + 1_000L)
            today = LocalDate.now()
            // currentWeek / 假期横幅只在数据落点重算，这里补一次跨零点自愈：
            // 周日→周一跨周时不重算的话「今」列高亮会落在上一周的网格上
            viewModel.onDayChanged()
        }
    }
    // 仅当用户当前选中的是"本周"时，今天列才高亮。否则切到别的周看课表时不该有"今天"概念。
    // currentWeek 来自 VM state，refresh/loadWeek 推进真实周后这里会自动重算。
    val todayWeekday: Int =
        if (state.currentWeek != null && state.currentWeek == state.selectedWeek) {
            today.dayOfWeek.value
        } else -1

    Scaffold(
        snackbarHost = {
            SnackbarHost(rememberTransientErrorSnackbar(viewModel.transientError))
        },
        topBar = {
            ScheduleTopBar(
                weekText = stringResource(R.string.schedule_week_template, state.selectedWeek),
                semester = state.semester,
                clickable = state.semesters.size > 1,
                onTitleClick = { showSemesterSheet = true },
                onJumpToday = viewModel::jumpToToday,
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
            )
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
            UpcomingExamBanner(onClick = onOpenExams)
            state.vacation?.let { info ->
                VacationBanner(info, today) { value -> viewModel.selectSemester(value) }
            }
            // 整学期无课的末尾周末列折叠成窄占位，把横向空间让给工作日（列变宽→课程/教室字号更大）。
            // 取自 state.data 的全量课表，不随选中周变化，列宽跨周稳定。
            val foldedDays = remember(state.data) {
                (state.data as? UiState.Success)?.let { computeFoldedDays(it.data) } ?: emptySet()
            }
            WeekdayHeader(
                todayWeekday = todayWeekday,
                semesterStart = state.semesterStart,
                selectedWeek = state.selectedWeek,
                foldedDays = foldedDays,
            )
            if (state.isOffline) OfflineBanner()
            StateScaffold(
                state = state.data,
                onRetry = viewModel::refresh,
                loading = { m -> cn.jxnu.nvzhuanban.ui.components.ScheduleSkeleton(modifier = m) },
            ) { courses ->
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                ) {
                    // 课程的 weeks 字段：教务网原始数据都是 1..18，用户改过的课用 CourseOverridesStore
                    // 的覆盖（仓库层已经替换好了）。这里按选中的周次筛掉不上的课。
                    // remember 缓存：courses 大小常态 20-40，但切周次按钮、拖动手势都会触发上游重组，
                    // 不缓存的话每次都要走一遍 List.filter，下游 ScheduleGrid 还会再 groupBy 一次。
                    val visibleCourses = remember(courses, state.selectedWeek) {
                        courses.filter { it.isInWeek(state.selectedWeek) }
                    }
                    val onSwipeLeft = {
                        if (state.selectedWeek < state.totalWeeks) {
                            viewModel.selectWeek(state.selectedWeek + 1)
                        }
                    }
                    val onSwipeRight = {
                        if (state.selectedWeek > 1) {
                            viewModel.selectWeek(state.selectedWeek - 1)
                        }
                    }
                    if (visibleCourses.isEmpty()) {
                        EmptyWeek(
                            onSwipeLeft = onSwipeLeft,
                            onSwipeRight = onSwipeRight,
                            // 整学期空课表（军训期/教务未排课）时网格不渲染、占位卡无宿主，
                            // 这里给大一未设置态留一个文字入口，否则功能整体不可达
                            onSetupEveningStudy = if (state.eveningStudyDays?.isEmpty() == true) {
                                { showEveningSheet = true }
                            } else null,
                        )
                    } else {
                        // 大一学期晚自习待设置 → 网格晚间区域放一张「＋晚自习」引导占位卡；
                        // 落点选第一个当周晚间无正课且未被折叠的列（全占则不显示）。
                        // 兜底分支：已设置但所选天整学期全被正课占满（synthesize 零卡）时也显示，
                        // 否则改/清设置的入口会随卡片一起消失（设置死角）。
                        // eveningStudyDays 离线态恒 null，占位卡与编辑入口自然隐藏。
                        val eveningPlaceholderDay = remember(state.eveningStudyDays, courses, visibleCourses, foldedDays) {
                            val days = state.eveningStudyDays
                            val showPlaceholder = days != null &&
                                (days.isEmpty() || courses.none { it.isEveningStudy })
                            if (showPlaceholder) EveningStudy.placeholderDay(visibleCourses, foldedDays) else null
                        }
                        ScheduleGrid(
                            courses = visibleCourses,
                            todayWeekday = todayWeekday,
                            foldedDays = foldedDays,
                            onCourseClick = { course ->
                                if (course.isEveningStudy) {
                                    // 晚自习卡不进课程详情 Sheet；离线态（快照还原的卡）不可编辑
                                    if (!state.isOffline) showEveningSheet = true
                                } else {
                                    selectedCourse = course
                                }
                            },
                            onSwipeLeft = onSwipeLeft,
                            onSwipeRight = onSwipeRight,
                            eveningPlaceholderDay = eveningPlaceholderDay,
                            onEveningPlaceholderClick = { showEveningSheet = true },
                        )
                    }
                }
            }
        }
    }

    if (selectedCourse != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedCourse = null },
            sheetState = sheetState,
        ) {
            CourseDetailSheet(
                course = selectedCourse!!,
                weekTotal = state.totalWeeks,
                onEditWeeks = { editingWeeksFor = selectedCourse },
                // 点教师 / 教室 → 关详情 sheet 再跳开课查询自动查。先清 selectedCourse 收起 sheet，
                // 避免返回时 sheet 还盖在开课查询页上。学期传当前查看学期的开学日（ISO，
                // LocalDate.toString() 即 yyyy-MM-dd），开课查询端按开学日对齐自家学期下拉；
                // 离线兜底态取到 null 则开课查询用默认学期。
                onQueryTeacher = { name ->
                    val iso = state.semesterStart?.toString()
                    selectedCourse = null
                    onOpenCourseOffering(name, null, iso)
                },
                onQueryClassroom = { room ->
                    val iso = state.semesterStart?.toString()
                    selectedCourse = null
                    onOpenCourseOffering(null, room, iso)
                },
            )
        }
    }

    if (editingWeeksFor != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingWeeksFor = null },
            sheetState = sheetState,
        ) {
            WeekEditorSheet(
                course = editingWeeksFor!!,
                totalWeeks = state.totalWeeks,
                onCancel = { editingWeeksFor = null },
                onSave = { newWeeks ->
                    // null → 恢复默认；非空列表 → 用户自定义；空列表用户主动选了"没有任何周"也算 override
                    viewModel.updateCourseWeeks(editingWeeksFor!!.name, newWeeks)
                    editingWeeksFor = null
                    selectedCourse = null
                },
            )
        }
    }

    if (showSemesterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSemesterSheet = false },
            sheetState = sheetState,
        ) {
            SemesterPickerSheet(
                semesters = state.semesters,
                selectedValue = state.selectedSemesterValue,
                onSelect = { value ->
                    showSemesterSheet = false
                    viewModel.selectSemester(value)
                },
            )
        }
    }

    if (showEveningSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEveningSheet = false },
            sheetState = sheetState,
        ) {
            // 整学期晚间被正课占死的天在面板里禁用（勾了也合成不出卡，还会造成设置死角）。
            // 按全学期课表算（含晚自习合成卡，fullyBlockedDays 内部会排除它们）。
            val allCourses = (state.data as? UiState.Success)?.data.orEmpty()
            val blocked = remember(allCourses, state.totalWeeks) {
                EveningStudy.fullyBlockedDays(state.totalWeeks, allCourses)
            }
            EveningStudySheet(
                initialDays = state.eveningStudyDays.orEmpty(),
                initialRoomDigits = state.eveningStudyRoom,
                fullyBlockedDays = blocked,
                onCancel = { showEveningSheet = false },
                onSave = { days, roomDigits ->
                    viewModel.updateEveningStudy(days, roomDigits)
                    showEveningSheet = false
                },
            )
        }
    }
}

/**
 * 紧凑顶栏：周次 + 学期并成一行，替代原来的 M3 [androidx.compose.material3.TopAppBar]——
 * 双行标题只占 ~48dp 却撑着 64dp 的栏高，上下各余一截空白。改成单行后栏高由 48dp 的
 * 图标按钮撑起，省出的纵向空间全部让给课表网格。高度只定下限（heightIn），系统大字模式下
 * 随内容自增，不裁字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTopBar(
    weekText: String,
    semester: String,
    clickable: Boolean,
    onTitleClick: () -> Unit,
    onJumpToday: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .heightIn(min = 48.dp)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Box 撑满剩余宽度，点击区（ripple）仍只包住标题内容本身，不随空白拉宽
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = if (clickable) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTitleClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = weekText,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = semester,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill=false：学期名短时行随内容收窄，窄屏放不下时才压缩出省略号
                    modifier = Modifier
                        .alignByBaseline()
                        .weight(1f, fill = false),
                )
                if (clickable) {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = "切换学期",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        IconButton(onClick = onJumpToday) {
            Icon(Icons.Outlined.Today, contentDescription = stringResource(R.string.schedule_today))
        }
        RefreshIconButton(isRefreshing = isRefreshing, onClick = onRefresh)
    }
}

@Composable
private fun SemesterPickerSheet(
    semesters: List<SchedulePage.SemesterOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = "选择学期",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // 学期可能有十多个（包含历史 + 未来），用 LazyColumn 避免一次性 measure 全部行造成卡顿，
        // 且能在 ModalBottomSheet 内部正确滚动到选中的学期附近
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(semesters, key = { it.value }) { option ->
                val isSelected = option.value == selectedValue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.value) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.isCurrent) {
                            Text(
                                text = "本学期",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WeekdayHeader(
    todayWeekday: Int,
    semesterStart: LocalDate?,
    selectedWeek: Int,
    foldedDays: Set<Int>,
) {
    // 当前 week 的周一 = 第 1 周的周一（SemesterPhase.weekOneMonday，开学名义日对齐最近周一）
    // 加 (week-1)*7 天。必须与 SemesterPhase.at 的周坐标同源，「今」列高亮那格的日期才恰好是今天
    //（旧实现 header 用 previousOrSame、周次推算不对齐，开学日非周一的学期两者会错开一周）。
    val weekMonday: LocalDate? = remember(semesterStart, selectedWeek) {
        semesterStart
            ?.let { SemesterPhase.weekOneMonday(it) }
            ?.plusDays(((selectedWeek - 1) * 7).toLong())
    }
    val dateFormatter = remember { java.time.format.DateTimeFormatter.ofPattern("M/d") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(LEFT_LABEL_WIDTH))
        WEEKDAY_LABELS.forEachIndexed { idx, label ->
            val weekday = idx + 1
            // 收起的周末列整列不渲染，剩余列 weight 平分变宽
            if (weekday in foldedDays) return@forEachIndexed
            val isToday = weekday == todayWeekday
            val date = weekMonday?.plusDays((weekday - 1).toLong())
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isToday) "$label·今" else label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (date != null) {
                    Text(
                        text = date.format(dateFormatter),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWeek(
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    /** 非 null = 大一学期且晚自习未设置：空课表页也要给一个设置入口（网格占位卡在此态无宿主）。 */
    onSetupEveningStudy: (() -> Unit)? = null,
) {
    // 阈值与 ScheduleGrid 保持一致：80dp 才触发；避免下拉刷新被误判成切周
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val dragAccum = remember { FloatArray(1) }
    // 同 ScheduleGrid：pointerInput(Unit) 持有首帧闭包，rememberUpdatedState 读最新切周回调
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)
    // verticalScroll + 上下 Spacer 撑空间：保证 PullToRefreshBox 在空数据时也能识别下拉手势
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum[0] = 0f },
                    onDragEnd = {
                        when {
                            dragAccum[0] <= -swipeThresholdPx -> currentSwipeLeft()
                            dragAccum[0] >= swipeThresholdPx -> currentSwipeRight()
                        }
                        dragAccum[0] = 0f
                    },
                    onDragCancel = { dragAccum[0] = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragAccum[0] += dragAmount },
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(160.dp))
        Text(
            text = stringResource(R.string.schedule_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onSetupEveningStudy != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "＋ 设置晚自习",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSetupEveningStudy)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(160.dp))
    }
}
