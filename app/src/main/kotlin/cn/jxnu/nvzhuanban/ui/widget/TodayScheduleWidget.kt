package cn.jxnu.nvzhuanban.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cn.jxnu.nvzhuanban.MainActivity
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.widget.ScheduleSnapshot
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 桌面小部件：今日课表 + 下一节倒计时。
 *
 * 数据策略：只读 [WidgetSnapshotStore] 写下的本地 JSON，不发起网络请求。
 * 渲染时按"此刻"现场算 weekday / week / 今日课程，因此即使 App 几天没开，
 * snapshot 不变也能正确切日、推进"下一节"高亮（前提是同一学期）。
 *
 * 刷新触发（详见 [WidgetUpdateScheduler] 和 [TodayScheduleWidgetReceiver]）：
 *  - 每次渲染结束自我注册下一节切换 / 跨日 alarm（inexact，不要权限）
 *  - 系统 ACTION_DATE_CHANGED / TIMEZONE_CHANGED / TIME_SET 兜底
 *  - widget xml 里 updatePeriodMillis=1800000（30 分钟）二级兜底
 *  - App 在课表数据更新后主动 updateAll
 *
 * 点击 widget 任意位置直接打开 App 主页。
 */
class TodayScheduleWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Glance provideGlance 在 Dispatchers.Default 上执行；磁盘 IO 需要切到 IO 调度器，
        // 避免阻塞 Glance worker 线程池影响其它 widget 的渲染。
        // runCatching 兜底：WidgetSnapshotStore.load 内部已 getOrDefault(empty)，但 IO 调度本身
        // 仍可能抛（罕见 OOM / 调度异常），失败时退化到空 snapshot 仍能渲染 EmptyHint，并继续往下
        // 排 alarm 让小部件下一次有机会自我恢复。
        val snapshot = runCatching {
            withContext(Dispatchers.IO) { WidgetSnapshotStore.load(context) }
        }.getOrDefault(ScheduleSnapshot.empty())
        val today = LocalDate.now()
        val nowMins = LocalTime.now().let { it.hour * 60 + it.minute }
        // 按节次排序，保证 nextCourseFromNow 的 firstOrNull 拿到的真是"最早还没结束"那节。
        // coursesOn 内部本来就 sortedBy { startSection }，这里防御性再保证一次。
        val courses = snapshot.coursesOn(today).sortedBy { it.startSection }
        val week = snapshot.weekAt(today)
        val weekday = today.dayOfWeek.value

        // 给自己排下一次唤醒（节次切换 / 跨日）。无 widget 实例时 PendingIntent 还在但不会重绘。
        // runCatching：MIUI / HyperOS 等定制 ROM 在后台严苛模式下偶尔会让
        // setAndAllowWhileIdle 抛 SecurityException —— 不能让一次 alarm 失败传染到 provideContent，
        // 否则用户看到的是 "Can't show content"，而本来应该至少看到一次"今天的课"。
        runCatching {
            WidgetUpdateScheduler.scheduleNext(context.applicationContext, today, nowMins, courses)
        }

        provideContent {
            GlanceTheme {
                Content(snapshot, courses, week, weekday, today, nowMins)
            }
        }
    }

    @Composable
    private fun Content(
        snapshot: ScheduleSnapshot,
        courses: List<SnapshotCourse>,
        week: Int,
        weekday: Int,
        today: LocalDate,
        nowMins: Int,
    ) {
        val openApp = actionStartActivity<MainActivity>()
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .cornerRadius(20.dp)
                .clickable(openApp)
                .padding(12.dp),
        ) {
            if (snapshot.updatedAt == 0L) {
                EmptyHint()
            } else {
                // 假期态：今天不在本学期任何教学周内（寒暑假 / 未开学）时不再显示
                // 「今天没课」，改为假期问候 + 开学倒计时（下学期开学日存在快照里）。
                val phase = if (snapshot.hasSemesterStart) {
                    SemesterPhase.at(
                        LocalDate.ofEpochDay(snapshot.semesterStartEpochDay),
                        if (snapshot.totalWeeks > 0) snapshot.totalWeeks else 18,
                        today,
                    )
                } else null
                when (phase) {
                    is SemesterPhase.Ended ->
                        VacationContent(today, snapshot.nextSemesterStart)
                    is SemesterPhase.NotStarted ->
                        VacationContent(today, phase.weekOneMonday)
                    else -> Column(modifier = GlanceModifier.fillMaxSize()) {
                        Header(week, weekday, today)
                        Spacer(GlanceModifier.height(8.dp))
                        Body(courses, nowMins)
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyHint() {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "江师办",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "打开 App 加载课表",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
        }
    }

    @Composable
    private fun Header(week: Int, weekday: Int, today: LocalDate) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = todayLabel(weekday),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            if (week > 0) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = "第 $week 周",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = today.format(DateTimeFormatter.ofPattern("M月d日")),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
        }
    }

    @Composable
    private fun Body(courses: List<SnapshotCourse>, nowMins: Int) {
        if (courses.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "今天没课，开心去玩 🎉",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                    ),
                )
            }
            return
        }

        val next = nextCourseFromNow(courses, nowMins)
        val isOngoing = next != null &&
            nowMins in SectionTimetable.startMinutes(next.startSection)..SectionTimetable.endMinutes(next.endSection)
        Column(modifier = GlanceModifier.fillMaxSize()) {
            if (next != null) {
                NextHighlight(next, isOngoing)
                Spacer(GlanceModifier.height(8.dp))
            }
            LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                // 稳定 key：同一节课每次重绘可以复用 RemoteViews，避免列表抖动。
                items(items = courses, itemId = { c -> stableId(c) }) { c ->
                    CourseRow(c, isNext = c === next)
                }
            }
        }
    }

    @Composable
    private fun NextHighlight(c: SnapshotCourse, isOngoing: Boolean) {
        val prefix = if (isOngoing) "进行中" else "下一节"
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column {
                Text(
                    text = "$prefix · ${sectionLabel(c.startSection, c.endSection)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 11.sp,
                    ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = c.name,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
                if (c.location.isNotBlank()) {
                    Text(
                        text = c.location,
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun CourseRow(c: SnapshotCourse, isNext: Boolean) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .width(36.dp)
                    .background(
                        if (isNext) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant
                    )
                    .cornerRadius(6.dp)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${c.startSection}-${c.endSection}",
                    style = TextStyle(
                        color = if (isNext) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = c.name,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
                if (c.location.isNotBlank()) {
                    Text(
                        text = c.location,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /**
     * 假期态内容：寒/暑假问候 + 开学倒计时。
     * [nextStart] 为下一个学期的开学日（第 1 周周一，即真实上课首日，不是教务的名义日期）；
     * 教务还没放出下学期选项时为 null，只显示问候。
     */
    @Composable
    private fun VacationContent(today: LocalDate, nextStart: LocalDate?) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = vacationTitle(today.monthValue),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("M月d日")),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
                )
            }
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val days = nextStart?.let { ChronoUnit.DAYS.between(today, it) }
                    when {
                        days == null -> Text(
                            text = "好好休息，开学再见 😴",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 13.sp,
                            ),
                        )
                        days <= 0L -> Text(
                            text = "今天开学啦，打开 App 看看新课表 🎒",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 13.sp,
                            ),
                        )
                        else -> {
                            Text(
                                text = "距开学还有",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 12.sp,
                                ),
                            )
                            Spacer(GlanceModifier.height(2.dp))
                            Text(
                                text = "$days 天",
                                style = TextStyle(
                                    color = GlanceTheme.colors.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                ),
                            )
                            Spacer(GlanceModifier.height(2.dp))
                            Text(
                                text = nextStart.format(DateTimeFormatter.ofPattern("M月d日")) + " 开学",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun todayLabel(weekday: Int): String = when (weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "今天"
    }

    private fun sectionLabel(start: Int, end: Int): String =
        if (start == end) "第 $start 节" else "第 $start-$end 节"

    /**
     * 找出"下一节"——当前时间还没结束的最近一节课。
     * 节次时间来自 [SectionTimetable]（江师大《本科教学作息时间表》），跟主 App 课表共用同一份。
     */
    private fun nextCourseFromNow(courses: List<SnapshotCourse>, nowMins: Int): SnapshotCourse? =
        courses.firstOrNull { c -> SectionTimetable.endMinutes(c.endSection) >= nowMins }

    /**
     * Glance items 用的稳定标识。课程名 + 节次 + weekday 足够标识"当天这一节课"，
     * 不会跨课程冲突。Long 是因为 items(itemId) 要求 Long 返回。
     *
     * 委托给顶级 [snapshotCourseItemId]，方便 JVM 单测覆盖；规则细节见那里的注释。
     */
    private fun stableId(c: SnapshotCourse): Long = snapshotCourseItemId(c)
}

/**
 * 把一节 [SnapshotCourse] 映射到 Glance `LazyColumn.items(itemId = ...)` 用的稳定 Long。
 *
 * 关键约束：Glance 把 `[Long.MIN_VALUE, Long.MIN_VALUE / 2]`（即 -4611686018427387904
 * 及以下）整段留给自己用，传入这个区间的 itemId 会抛 IllegalArgumentException、整个
 * widget 显示 "Can't show content"。所以多项式 hash 之后用 `and Long.MAX_VALUE`
 * 把符号位清零，把结果钳到 `[0, Long.MAX_VALUE]`，永远跳出保留段。
 */
internal fun snapshotCourseItemId(c: SnapshotCourse): Long {
    var h = 1125899906842597L
    for (ch in c.name) h = 31L * h + ch.code
    h = 31L * h + c.weekday
    h = 31L * h + c.startSection
    h = 31L * h + c.endSection
    return h and Long.MAX_VALUE
}

/**
 * 假期态标题：按当前月份粗分寒/暑假。江师大寒假约 1~2 月、暑假约 7~8 月，
 * 边缘月份（6 月末考完 / 9 月初未开学、12 月末提前放假）也归入相邻假期；
 * 其余月份（学期中途因故不在教学周内）用中性的「假期中」。
 */
internal fun vacationTitle(month: Int): String = when (month) {
    12, 1, 2, 3 -> "寒假中 ❄️"
    6, 7, 8, 9 -> "暑假中 ⛱️"
    else -> "假期中"
}
