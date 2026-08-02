package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.EveningStudy
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.model.isEveningStudy
import cn.jxnu.nvzhuanban.data.storage.ScheduleHeightPrefs
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.abs

private val SECTIONS = SectionTimetable.SECTION_COUNT

/**
 * 布局期才读取节次高度的定高修饰符。捏合缩放逐帧改高度时只重跑 measure/place，
 * 组合阶段零失效 —— 否则整张网格（7 列 + 全部课程卡 + 84 个分隔格 + 12 个节次标签）会每帧全量重组。
 */
private fun Modifier.sectionHeight(sections: Int, sectionHeightDp: () -> Float): Modifier =
    layout { measurable, constraints ->
        val h = (sectionHeightDp() * sections).dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.placeRelative(0, 0) }
    }

@Composable
internal fun ScheduleGrid(
    courses: List<Course>,
    todayWeekday: Int,
    foldedDays: Set<Int>,
    onCourseClick: (Course) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    /**
     * 「设置晚自习」引导占位卡所在列（1=周一 … 7=周日）；null = 不显示。
     * 仅大一学期且晚自习尚未设置时非 null，由上层用 [EveningStudy.placeholderDay] 算好传入。
     */
    eveningPlaceholderDay: Int? = null,
    onEveningPlaceholderClick: () -> Unit = {},
) {
    // 阈值用 px：滑动距离超过 ~80dp 才认为是切周，避免误触
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // pointerInput 的 key 是 Unit（手势协程不重启），而 onSwipeLeft/Right 闭包了 selectedWeek 快照。
    // 用 rememberUpdatedState 让手势回调始终读到最新一帧的 lambda，否则切周后横滑会按进入时的旧周次切。
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)

    // 格高：prefs 是真值，手势期间用 liveDp 驱动 UI 实时跟手，松手时一次性落盘。
    // LaunchedEffect 处理"外部把 prefs 改了"的极端路径（目前没有别处会改，但留着便宜）。
    val storedDp by ScheduleHeightPrefs.sectionHeightDp.collectAsState()
    val liveDp = remember { mutableFloatStateOf(storedDp) }
    LaunchedEffect(storedDp) { liveDp.floatValue = storedDp }
    // 显示高度按系统字体缩放补偿（大字模式下节次标签/卡内文字才放得下），上限 1.5x。
    // 只影响显示：捏合手势与 prefs 落盘仍以原始 dp 为准。
    val fontScale = LocalDensity.current.fontScale.coerceAtMost(1.5f)
    // 组合期不读 liveDp（读了会让 ScheduleGrid 整个作用域逐帧失效），叶子在 layout/offset lambda 里现取
    val sectionHeightDp: () -> Float = remember(fontScale) { { liveDp.floatValue * fontScale } }

    // 按 weekday 预分组：每次重组前 DayColumn 都要 7 次 filter（每天一次）。courses 改动频率远低于重组频率
    // （拖动 / hover / 高亮分钟级 tick 都会触发重组），用 remember 缓存能省掉绝大多数 O(N*7) 扫描。
    val coursesByDay = remember(courses) { courses.groupBy { it.weekday } }

    // 当前在本周时，每分钟轮询一次"下节/上课中"课程做高亮。
    // todayWeekday<=0 表示不是本周，此时不计算（state 始终 null，无开销）
    var highlightState by remember(courses, todayWeekday) { mutableStateOf<HighlightedCourse?>(null) }
    LaunchedEffect(courses, todayWeekday) {
        if (todayWeekday <= 0) {
            highlightState = null
            return@LaunchedEffect
        }
        while (true) {
            highlightState = computeHighlight(
                coursesToday = coursesByDay[todayWeekday].orEmpty(),
                now = LocalTime.now(),
            )
            delay(60_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 自管理的手势循环：开头允许"无第二指 → 横向切周"或"出现第二指 → 切到捏合"，
                // 二选一，避免两个 pointerInput 并存时 detectHorizontalDragGestures 已经吃掉首指针造成误切周。
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startedDp = liveDp.floatValue
                    var sawSecondPointer = false
                    var horizontalAccum = 0f
                    var horizontalConsumed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (!sawSecondPointer && pressedCount >= 2) sawSecondPointer = true

                        if (sawSecondPointer) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val next = (liveDp.floatValue * zoom)
                                    .coerceIn(ScheduleHeightPrefs.MIN_DP, ScheduleHeightPrefs.MAX_DP)
                                // 边界饱和后不写：否则手指继续张开/合拢后再反向时要"先空走"一段
                                if (next != liveDp.floatValue) {
                                    liveDp.floatValue = next
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } else {
                            // 单指水平累计，复刻原 detectHorizontalDragGestures 行为
                            val change = event.changes.firstOrNull { it.id == first.id }
                                ?: event.changes.first()
                            val dx = change.positionChange().x
                            if (dx != 0f) {
                                horizontalAccum += dx
                                if (!horizontalConsumed && abs(horizontalAccum) > touchSlop) {
                                    horizontalConsumed = true
                                }
                                if (horizontalConsumed) change.consume()
                            }
                        }

                        if (event.changes.all { !it.pressed }) break
                    }

                    if (sawSecondPointer) {
                        // 全松开 commit：仅当确实变化过才写 prefs
                        if (liveDp.floatValue != startedDp) {
                            ScheduleHeightPrefs.setSectionHeightDp(liveDp.floatValue)
                        }
                    } else {
                        when {
                            horizontalAccum <= -swipeThresholdPx -> currentSwipeLeft()
                            horizontalAccum >= swipeThresholdPx -> currentSwipeRight()
                        }
                    }
                }
            }
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            SectionLabels(sectionHeightDp = sectionHeightDp)
            // weekday 列。周六整学期无课时收起周六（周日也无课则一起收，foldedDays={6}或{6,7}），
            // 横向空间由剩余列 weight(1f) 平分 → 列变宽、课程名与教室号字号更大。
            for (day in 1..7) {
                if (day in foldedDays) continue
                DayColumn(
                    courses = coursesByDay[day].orEmpty(),
                    isToday = day == todayWeekday,
                    highlight = if (day == todayWeekday) highlightState else null,
                    onCourseClick = onCourseClick,
                    sectionHeightDp = sectionHeightDp,
                    showEveningPlaceholder = day == eveningPlaceholderDay,
                    onEveningPlaceholderClick = onEveningPlaceholderClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SectionLabels(sectionHeightDp: () -> Float) {
    // 纯装饰列：清空语义，避免 TalkBack 焦点在 12 个孤立的数字/时间上空走
    // （节次信息已并入每张课程卡的 contentDescription）
    Column(
        modifier = Modifier
            .width(LEFT_LABEL_WIDTH)
            .clearAndSetSemantics {},
    ) {
        for (i in 1..SECTIONS) {
            Box(
                modifier = Modifier
                    .sectionHeight(1, sectionHeightDp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = i.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = SectionTimetable.startTimeLabel(i),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    courses: List<Course>,
    isToday: Boolean,
    highlight: HighlightedCourse?,
    onCourseClick: (Course) -> Unit,
    sectionHeightDp: () -> Float,
    showEveningPlaceholder: Boolean,
    onEveningPlaceholderClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .sectionHeight(SECTIONS, sectionHeightDp)
            // 今列整列淡色高亮 + 圆角：视觉上像一列微微浮起的卡片，比纯直角色块柔和
            .background(
                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 2.dp),
    ) {
        // 背景节次分隔线
        Column(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until SECTIONS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sectionHeight(1, sectionHeightDp)
                        .background(
                            if (i == 4 || i == 6 || i == 9) {
                                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
                            } else Color.Transparent,
                        ),
                )
            }
        }
        // 课程卡片层
        courses.forEach { course ->
            CourseCard(
                course = course,
                highlightTag = highlight?.takeIf { it.courseId == course.id }?.tag,
                sectionHeightDp = sectionHeightDp,
                onClick = { onCourseClick(course) },
            )
        }
        if (showEveningPlaceholder) {
            EveningStudyPlaceholderCard(
                sectionHeightDp = sectionHeightDp,
                onClick = onEveningPlaceholderClick,
            )
        }
    }
}

/**
 * 大一学期晚自习尚未设置时，晚间第 10–12 节位置的引导占位卡：虚线描边幽灵样式，
 * 点击弹晚自习编辑面板。定位/定高与 [CourseCard] 同一套布局期读高度纪律。
 */
@Composable
private fun EveningStudyPlaceholderCard(
    sectionHeightDp: () -> Float,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .offset {
                IntOffset(0, (sectionHeightDp() * (EveningStudy.START_SECTION - 1)).dp.roundToPx())
            }
            .sectionHeight(EveningStudy.END_SECTION - EveningStudy.START_SECTION + 1, sectionHeightDp)
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2f),
                )
            }
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription =
                    "设置晚自习，${EveningStudy.START_LABEL}到${EveningStudy.END_LABEL}"
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "＋\n晚自习",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CourseCard(
    course: Course,
    highlightTag: String?,
    sectionHeightDp: () -> Float,
    onClick: () -> Unit,
) {
    // 亮暗自适应：courseSwatch 内部按当前生效主题选色板（见 SchedulePalettes.kt）
    val swatch = courseSwatch(course)
    val teacherMaxLines = if (course.sectionCount >= 2) 1 else 0
    // "周几 / 第几节"只体现在卡片的视觉排布（列位置 + y offset）上，语义树里读不到，
    // 这里显式并进描述，让 TalkBack 把卡片当一个整体播报
    val cardDescription = buildString {
        append("周")
        append(WEEKDAY_LABELS.getOrElse(course.weekday - 1) { "?" })
        append(" 第${course.startSection}-${course.endSection}节 ")
        append(course.name)
        if (course.location.isNotBlank()) append("，${course.location}")
        if (course.teacher.isNotBlank()) append("，${course.teacher}")
        if (highlightTag != null) append("，$highlightTag")
    }

    // 「上课中/下节」白描边做呼吸动画：动画值只在 drawBehind（draw 阶段）里读取，
    // 逐帧只重绘这一张卡，不触发任何重组——与捏合缩放的布局期读高度是同一套纪律。
    val highlightPulse = if (highlightTag != null) {
        val transition = rememberInfiniteTransition(label = "coursePulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "coursePulseAlpha",
        )
    } else null

    Box(
        modifier = Modifier
            .offset { IntOffset(0, (sectionHeightDp() * (course.startSection - 1)).dp.roundToPx()) }
            .sectionHeight(course.sectionCount, sectionHeightDp)
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(swatch.container)
            .let { base ->
                if (highlightPulse != null) {
                    base.drawBehind {
                        // clip 会裁掉边界以外的绘制，Stroke 又是沿路径居中——不内缩半个线宽
                        // 的话外侧 1dp 会被裁掉，实际只剩 1dp。按半线宽内缩画满 2dp。
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            color = swatch.onContainer.copy(alpha = highlightPulse.value),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                            cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2f),
                        )
                    }
                } else base
            }
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = cardDescription
            }
            .padding(6.dp),
    ) {
        Column {
            // 标签塞进顶行而不是 align(TopEnd) 叠在课程名上：课表列只有 ~50dp 宽，
            // 3 字标签会把课程名整行盖掉
            if (highlightTag != null) {
                Text(
                    text = highlightTag,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.Bold,
                    color = swatch.container,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(swatch.onContainer)
                        .padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = swatch.onContainer,
                maxLines = when {
                    highlightTag != null -> 1
                    course.sectionCount >= 3 -> 3
                    else -> 2
                },
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // 教室号必须完整且单行：宽度不够时自动缩小字号（9sp → 最低 5sp）而不是截断。
            // 上限放到 11sp——周末列折叠后工作日列变宽，短教室号能显示得更大更清楚。
            // BasicText 的 autoSize 在 measure 期逐级试字号，不会引起额外重组。
            // 晚自习卡：填了教室（W 楼）走地点行，时间移到下方 teacher 槽位；没填教室则这一行直接显示时段。
            val subLine = when {
                course.location.isNotBlank() -> "@${course.location}"
                course.isEveningStudy -> "${EveningStudy.START_LABEL}-${EveningStudy.END_LABEL}"
                else -> null
            }
            if (subLine != null) {
                BasicText(
                    text = subLine,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = swatch.onContainer,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 5.sp,
                        maxFontSize = 11.sp,
                        stepSize = 0.5.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 第三行：正课显示教师；晚自习在填了教室后把时段挪到这里（teacher 恒空，槽位空闲）
            val thirdLine = when {
                course.teacher.isNotBlank() -> course.teacher
                course.isEveningStudy && course.location.isNotBlank() ->
                    "${EveningStudy.START_LABEL}-${EveningStudy.END_LABEL}"
                else -> null
            }
            if (teacherMaxLines > 0 && thirdLine != null) {
                Text(
                    text = thirdLine,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = swatch.onContainer,
                    maxLines = teacherMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 今日要高亮的课程：上课中 > 下节 > 无。
 *
 * - **上课中**：当前时间介于 startSection 起始 与 endSection 结束 之间
 * - **下节**：今天未开始的课程中开始时间最近的一节
 *
 * 没课 / 今日已结束 都返回 null（不高亮）。
 */
private data class HighlightedCourse(val courseId: String, val tag: String)

/** 结束时刻（当日分钟）：晚自习按实际的 21:00，正课按节次表的下课时间。 */
private fun endMinutesOf(course: Course): Int =
    if (course.isEveningStudy) EveningStudy.END_MINUTES
    else SectionTimetable.endMinutes(course.endSection)

private fun computeHighlight(coursesToday: List<Course>, now: LocalTime): HighlightedCourse? {
    if (coursesToday.isEmpty()) return null
    val nowMin = now.hour * 60 + now.minute

    val ongoing = coursesToday.firstOrNull { c ->
        nowMin in SectionTimetable.startMinutes(c.startSection)..endMinutesOf(c)
    }
    if (ongoing != null) return HighlightedCourse(ongoing.id, "上课中")

    val next = coursesToday
        .filter { SectionTimetable.startMinutes(it.startSection) > nowMin }
        .minByOrNull { SectionTimetable.startMinutes(it.startSection) }
    return next?.let { HighlightedCourse(it.id, "下节") }
}
