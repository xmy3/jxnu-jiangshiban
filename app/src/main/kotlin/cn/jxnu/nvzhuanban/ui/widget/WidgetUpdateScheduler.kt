package cn.jxnu.nvzhuanban.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import java.time.LocalDate
import java.time.ZoneId

/**
 * 给小部件安排"下一次自动重绘"。
 *
 * 思路：每次 widget 渲染完，根据当前时刻和今日剩余节次算出下一个有意义的时间点
 *   （某节开始 / 某节结束 / 明日 00:01 切日），用 inexact [AlarmManager.set] 注册
 *   一次性广播。广播被 [TodayScheduleWidgetReceiver] 接收后强制 `updateAll`，
 *   再次进入 [TodayScheduleWidget.provideGlance] 时又会接着排下一次，形成自循环。
 *
 * 为什么是 [AlarmManager.setAndAllowWhileIdle]：
 *  - 仍然是 inexact，节次 40 分钟、~9 分钟级别的偏差对"下一节高亮"够用
 *  - 能穿透 Doze 维护窗口（普通 [AlarmManager.set] 会被合并推迟到几十分钟甚至几小时）
 *  - API 23+ 即可用，无需 SCHEDULE_EXACT_ALARM 权限（Android 12+ 新增）
 *  - 系统对同一 App 大约每 9 分钟才允许触发一次，节次粒度完全够用
 *
 * 系统层的 `updatePeriodMillis` + `ACTION_DATE_CHANGED` 广播作为兜底，但前者在 Doze 下
 * 同样会被暂停，不能完全依赖。
 */
internal object WidgetUpdateScheduler {

    /** PendingIntent 的自定义 action；和 system 广播区分开。 */
    const val ACTION_TICK = "cn.jxnu.nvzhuanban.widget.action.TICK"

    private const val REQUEST_CODE = 0xC2B0
    private const val MINUTES_PER_DAY = 24 * 60

    fun scheduleNext(
        context: Context,
        today: LocalDate,
        nowMins: Int,
        todayCourses: List<SnapshotCourse>,
    ) {
        val zone = ZoneId.systemDefault()
        val nextMinutesOfDay = nextSignificantMinute(nowMins, todayCourses)
        val triggerAtMillis = if (nextMinutesOfDay != null && nextMinutesOfDay < MINUTES_PER_DAY) {
            // 1440 == 次日 00:00。LocalTime.of(24,0) 会抛 DateTimeException，所以等于 1440 时走"明日 00:01"分支。
            today.atTime(nextMinutesOfDay / 60, nextMinutesOfDay % 60)
                .atZone(zone).toInstant().toEpochMilli()
        } else {
            // 今日没有更晚的节次了（或恰好落在 00:00） → 安排到明日 00:01，让 widget 切日
            today.plusDays(1).atTime(0, 1)
                .atZone(zone).toInstant().toEpochMilli()
        }
        scheduleAt(context, triggerAtMillis)
    }

    /** 最后一个 widget 被移除时调用，避免无谓唤醒。 */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        am.cancel(pi)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, TodayScheduleWidgetReceiver::class.java)
            .setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 今日"晚于现在"的最近一个节次起点或终点；都过了返回 null。 */
    internal fun nextSignificantMinute(nowMins: Int, todayCourses: List<SnapshotCourse>): Int? {
        if (todayCourses.isEmpty()) return null
        return todayCourses.asSequence()
            .flatMap {
                sequenceOf(
                    SectionTimetable.startMinutes(it.startSection),
                    SectionTimetable.endMinutes(it.endSection),
                )
            }
            .filter { it > nowMins }
            .minOrNull()
    }
}
