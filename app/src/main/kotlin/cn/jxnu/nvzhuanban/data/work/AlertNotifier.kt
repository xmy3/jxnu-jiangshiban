package cn.jxnu.nvzhuanban.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.jxnu.nvzhuanban.MainActivity
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.AlertChange
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamReminderDay
import cn.jxnu.nvzhuanban.data.model.ExamReminderItem
import cn.jxnu.nvzhuanban.data.model.Grade
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 出分 / 考试变更的本地通知。
 *
 * 没有任何服务端推送，能做的只是「后台拉到新数据 → 发一条本地通知」。文案刻意写成
 * 「新增 / 变更」而不是「刚刚」，因为它最多可能迟到几小时（见 [AlertScheduler] 的说明）。
 *
 * 锁屏隐私：成绩属于敏感信息，通知本体设为 `VISIBILITY_PRIVATE`，锁屏上只显示
 * [publicHint] 的笼统文案，解锁后才展开课程名和分数。
 */
object AlertNotifier {

    const val CHANNEL_ID = "grade_exam_alerts"

    /**
     * 考前一天提醒单独一个渠道：用户可能想留考试提醒、但嫌出分通知烦（或者反过来）。
     * 合成一个渠道就没法分开关了。
     */
    const val CHANNEL_ID_EXAM_REMINDER = "exam_reminder_alerts"

    /** 通知点击带的路由；[cn.jxnu.nvzhuanban.ui.navigation.AlertNavigation] 消费它。 */
    const val EXTRA_ROUTE = "cn.jxnu.nvzhuanban.extra.ALERT_ROUTE"

    // 与 ui/navigation/AppNav.kt 的 Routes 保持一致（AlertNotifierRouteTest 会守护这个契约）
    const val ROUTE_GRADES = "grades"
    const val ROUTE_EXAMS = "exams"

    private const val NOTIFICATION_ID_GRADES = 4101
    private const val NOTIFICATION_ID_EXAMS = 4102
    private const val NOTIFICATION_ID_EXAM_REMINDER = 4103
    private const val MAX_LINES = 3

    private val examDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    fun ensureChannel(context: Context) {
        // minSdk 26 = O，通知渠道总是可用（不需要 SDK_INT 判断）
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "成绩与考试提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "有新成绩、或考试时间考场发生变化时提醒"
                    setShowBadge(true)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ID_EXAM_REMINDER) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_EXAM_REMINDER,
                    "考前提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "考试前一天晚上提醒考场与座位"
                    setShowBadge(true)
                },
            )
        }
    }

    /** 通知开关是否真的能发出去（系统总开关 + Android 13 运行时权限）。 */
    fun areAlertsAllowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    fun notifyGrades(context: Context, change: AlertChange<Grade>) {
        if (change.isEmpty) return
        post(
            context = context,
            notificationId = NOTIFICATION_ID_GRADES,
            route = ROUTE_GRADES,
            title = gradeTitle(change),
            body = gradeBody(change),
            publicHint = "有新的成绩提醒",
        )
    }

    fun notifyExams(context: Context, change: AlertChange<Exam>) {
        if (change.isEmpty) return
        post(
            context = context,
            notificationId = NOTIFICATION_ID_EXAMS,
            route = ROUTE_EXAMS,
            title = examTitle(change),
            body = examBody(change),
            publicHint = "考试安排有变动",
        )
    }

    /**
     * 考前一天晚上的定点提醒（走 [ExamReminderScheduler] 的闹钟，不是轮询）。
     *
     * 只说「明天」和考场/座位，不说几点 —— 教务系统返回的考试时间不可靠，写个假时间比不写更害人。
     */
    fun notifyExamReminder(context: Context, day: ExamReminderDay) {
        if (day.items.isEmpty()) return
        post(
            context = context,
            notificationId = NOTIFICATION_ID_EXAM_REMINDER,
            channelId = CHANNEL_ID_EXAM_REMINDER,
            route = ROUTE_EXAMS,
            title = examReminderTitle(day),
            body = examReminderBody(day),
            publicHint = "明天有考试安排",
        )
    }

    // region 文案（internal 以便单测覆盖：这是用户唯一能看到的东西）

    internal fun gradeTitle(change: AlertChange<Grade>): String = when {
        change.added.isNotEmpty() && change.changed.isNotEmpty() ->
            "${change.added.size} 门新课出分，${change.changed.size} 门有更新"
        change.added.isNotEmpty() -> "${change.added.size} 门新课出分"
        else -> "成绩有更新"
    }

    internal fun gradeBody(change: AlertChange<Grade>): String {
        val lines = buildList {
            change.added.forEach { add(it.courseName + " " + it.score.ifBlank { "已出分" }) }
            change.changed.forEach { add(it.courseName + " 更新为 " + it.score.ifBlank { "—" }) }
        }
        return joinCapped(lines)
    }

    internal fun examTitle(change: AlertChange<Exam>): String = when {
        change.added.isNotEmpty() && change.changed.isEmpty() -> "新增考试安排"
        change.changed.isNotEmpty() && change.added.isEmpty() -> "考试安排有变"
        else -> "考试安排有更新"
    }

    internal fun examBody(change: AlertChange<Exam>): String {
        val lines = buildList {
            change.added.forEach { add(it.describe()) }
            change.changed.forEach { add(it.describe()) }
        }
        return joinCapped(lines)
    }

    internal fun examReminderTitle(day: ExamReminderDay): String =
        if (day.items.size == 1) "明天有考试" else "明天有 ${day.items.size} 门考试"

    internal fun examReminderBody(day: ExamReminderDay): String =
        joinCapped(day.items.map(::examReminderLine))

    internal fun examReminderLine(item: ExamReminderItem): String = buildString {
        append(item.courseName)
        if (item.location.isNotBlank()) append(" · ").append(item.location)
        if (item.seat != null) append(" · 座位 ").append(item.seat)
    }

    private fun Exam.describe(): String = buildString {
        append(courseName)
        append(' ')
        append(startTime.toLocalDate().format(examDateFormatter))
        if (location.isNotBlank()) append(' ').append(location)
    }

    private fun joinCapped(lines: List<String>): String {
        if (lines.size <= MAX_LINES) return lines.joinToString("、")
        return lines.take(MAX_LINES).joinToString("、") + " 等 ${lines.size} 项"
    }

    // endregion

    private fun post(
        context: Context,
        notificationId: Int,
        route: String,
        title: String,
        body: String,
        publicHint: String,
        channelId: String = CHANNEL_ID,
    ) {
        if (!areAlertsAllowed(context)) return
        ensureChannel(context)

        val intent = contentIntent(context, route)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_notification_alert)
                    .setContentTitle("江师办")
                    .setContentText("$publicHint，点按查看")
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build(),
            )

        // 通知被系统设置挡住时 notify 不抛异常，但个别 ROM 会；失败不该让整个后台检查失败
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    private fun contentIntent(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ROUTE, route)
            // 已经开着 App 时回到同一个实例（MainActivity 是 singleTop），走 onNewIntent，
            // 不会在回退栈上再叠一层
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
