package cn.jxnu.nvzhuanban.data.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamReminderDay
import cn.jxnu.nvzhuanban.data.model.ExamReminderPlan
import cn.jxnu.nvzhuanban.data.storage.ExamReminderStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「考前一天提醒」的闹钟调度。
 *
 * 和出分轮询（[AlertScheduler]）是两套东西，别混：
 *  - 出分没有截止时间，晚几小时无所谓 → WorkManager 周期任务。
 *  - 考试有明确的截止时间（明早 8 点考），必须在**今晚**说到 → AlarmManager 定点闹钟。
 *
 * 精确性两级：有「闹钟与提醒」特殊权限（Android 12+，和桌面小组件共用同一个权限）时用
 * `setExactAndAllowWhileIdle`；没有时退化成 `setAndAllowWhileIdle`（穿透 Doze，但系统可能推迟
 * 十几分钟 —— 对「晚上提醒明天的考试」这种场景可以接受，所以不为此单独拦用户去授权）。
 *
 * 闹钟活不过重启，所以 [ExamReminderStore] 存档 + [ExamReminderReceiver] 开机重排是必需的一环。
 */
object ExamReminderScheduler {

    /** 闹钟响时发给自己广播的动作（显式组件 + 这个 action，外部伪造不了）。 */
    const val ACTION_FIRE = "cn.jxnu.nvzhuanban.action.EXAM_REMINDER"

    /** 广播里带的是 `LocalDate.toEpochDay()`：跨进程传字符串日期还得二次解析，不如直接传数字。 */
    const val EXTRA_EPOCH_DAY = "cn.jxnu.nvzhuanban.extra.EXAM_DAY"

    /** 与小组件（0/1 号槽）错开，避免两套闹钟互相 cancel 掉。 */
    private const val REQUEST_CODE_BASE = 7400

    /**
     * 用最新的考试列表重排全部提醒：消失的日子取消、剩下的重排。
     *
     * 每次都整表覆盖（而不是增量 add），因为考试会被改期甚至撤销，增量很容易留下幽灵闹钟。
     */
    fun sync(context: Context, exams: List<Exam>, now: LocalDateTime = LocalDateTime.now()) {
        val previous = ExamReminderStore.load(context)
        // 必须接收完整考试列表，再结合待送达存档规划；单看未来触发时间会误删延迟闹钟。
        val days = ExamReminderPlan.plan(exams, now, previous.map { it.date }.toSet())
        val keep = days.map { it.date }.toSet()
        previous.filterNot { it.date in keep }.forEach { cancelDay(context, it.date) }
        // 保留待送达提醒的最新内容；已撤销/改期/过期的考试仍会移除。
        ExamReminderStore.save(context, days)
        days.forEach { scheduleDay(context, it) }
    }

    /** 重启 / 换时区 / 改系统时间 / 覆盖安装后，按存档把闹钟原样排回去。 */
    fun rearm(context: Context) {
        ExamReminderStore.load(context).forEach { scheduleDay(context, it) }
    }

    /** 关掉开关 / 登出 / 换号：撤掉全部闹钟并清空存档。 */
    fun cancelAll(context: Context) {
        ExamReminderStore.load(context).forEach { cancelDay(context, it.date) }
        ExamReminderStore.clear(context)
    }

    private fun scheduleDay(context: Context, day: ExamReminderDay) {
        val am = alarmManager(context) ?: return
        val triggerAt = runCatching {
            ExamReminderPlan.reminderAt(day.date)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return
        // 已过点的待送达闹钟保持原样，不取消也不重排，避免刷新变成立即补发。
        if (triggerAt <= System.currentTimeMillis()) return

        val pi = pendingIntent(context, day.date)
        runCatching { am.cancel(pi) }
        val setInexact = {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
        if (!canScheduleExact(context)) {
            setInexact()
            return
        }
        // 权限在「检查」和「设置」之间被用户撤掉时 setExact 会抛 SecurityException（MIUI 上还见过
        // 权限态与 canScheduleExactAlarms() 不一致的情况）。一次失败降级成 inexact，别让提醒消失。
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }.onFailure { setInexact() }
    }

    private fun cancelDay(context: Context, date: LocalDate) {
        val am = alarmManager(context) ?: return
        runCatching { am.cancel(pendingIntent(context, date)) }
    }

    private fun pendingIntent(context: Context, date: LocalDate): PendingIntent {
        val intent = Intent(context.applicationContext, ExamReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE_BASE + date.toEpochDay().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** API ≤ 30 恒为 true（那时 `setExactAndAllowWhileIdle` 不需要权限）。 */
    private fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarmManager(context) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }
}
