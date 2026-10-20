package cn.jxnu.nvzhuanban.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import java.time.LocalDate
import java.time.ZoneId

/**
 * 给小部件安排"下一次自动重绘"。
 *
 * 思路：每次 widget 渲染完，根据当前时刻和今日剩余节次算出下一个有意义的时间点
 *   （某节开始 / 某节结束 / 明日 00:01 切日），注册一次性广播。广播被
 *   [TodayScheduleWidgetReceiver] 接收后强制 `updateAll`，再次进入
 *   [TodayScheduleWidget.provideGlance] 时又会接着排下一次，形成自循环。
 *
 * 准点策略（两级）：
 *  1. **精确闹钟优先**：[AlarmManager.setExactAndAllowWhileIdle] 会正好在节次边界触发。
 *     API ≤ 30 无需任何权限；Android 12+ 需要 `SCHEDULE_EXACT_ALARM`（系统里叫「闹钟与提醒」），
 *     Android 14+ 起该权限默认不授予，必须由用户在系统设置里打开 —— App 内「我的 →
 *     小组件准点刷新」提供入口，授予后立刻重绘一次。
 *  2. **没有精确权限时退化为 inexact**：[AlarmManager.setAndAllowWhileIdle] 仍能穿透 Doze，
 *     但会被系统合并推迟（Doze 下同一 App 大约 9 分钟才允许一次），实测可迟到几分钟到十几分钟。
 *     所以这时额外排一个 [+BACKUP_DELAY_MILLIS] 的兜底槽：主槽迟到时由它把"进行中 / 下一节"
 *     纠正回来。主槽按时触发时，本次渲染会连带取消兜底槽，不会白唤醒。
 *
 * 需要注意的是：无论精确与否，单次广播都不保证 100% 送达（国产 ROM 的后台限制会把闹钟整个吞掉）。
 * 因此 appwidget 的 `updatePeriodMillis`（30 分钟）、[Intent.ACTION_DATE_CHANGED] 等系统广播、
 * 开机重启（[Intent.ACTION_BOOT_COMPLETED]）和 App 冷启动主动重绘都是必要的兜底层。
 */
internal object WidgetUpdateScheduler {

    /** PendingIntent 的自定义 action；和 system 广播区分开。 */
    const val ACTION_TICK = "cn.jxnu.nvzhuanban.widget.action.TICK"

    /** 主唤醒槽：触发时刻就是目标节次边界。 */
    private const val REQUEST_PRIMARY = 0xC2B0

    /** 兜底唤醒槽：只在拿不到精确闹钟权限时启用，用来兜住 inexact 的迟到。 */
    private const val REQUEST_BACKUP = 0xC2B1

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * 兜底槽比目标边界晚多少毫秒。
     *
     * 取 4 分钟：小于 Doze 下 inexact 唤醒的典型延迟（约 9 分钟），迟到时能及时纠正；
     * 又小于「上一节结束 → 下一节开始」的 10 分钟间隔，不会跟下一次主唤醒撞在一起。
     */
    internal const val BACKUP_DELAY_MILLIS = 4 * 60 * 1000L

    fun scheduleNext(
        context: Context,
        today: LocalDate,
        nowMins: Int,
        todayCourses: List<SnapshotCourse>,
    ) {
        val plan = planNext(
            zone = ZoneId.systemDefault(),
            today = today,
            nowMins = nowMins,
            todayCourses = todayCourses,
            backupDelayMillis = BACKUP_DELAY_MILLIS,
        )
        val exact = canScheduleExactAlarms(context)
        // 两个槽都要先撤：主槽按时触发后重排时，上一次的兜底槽可能还挂在"过去"。
        cancelSlots(context)
        scheduleAt(context, REQUEST_PRIMARY, plan.primaryAtMillis, exact = exact)
        // 精确闹钟准点触发，兜底槽纯属浪费唤醒；只有退化到 inexact 时才需要它。
        if (!exact) scheduleAt(context, REQUEST_BACKUP, plan.backupAtMillis, exact = false)
    }

    /** 最后一个 widget 被移除时调用，避免无谓唤醒。 */
    fun cancel(context: Context) {
        cancelSlots(context)
    }

    /**
     * 纯函数：算出下一次主唤醒 / 兜底唤醒的触发时刻（epoch millis）。
     *
     * 今日还有更晚的节次边界就用它；否则排到明日 00:01 让 widget 切日。
     * 抽成纯函数是为了让 JVM 单测能覆盖"跨日 / 空课表"这些边界（见 `WidgetUpdateSchedulerTest`）。
     */
    internal fun planNext(
        zone: ZoneId,
        today: LocalDate,
        nowMins: Int,
        todayCourses: List<SnapshotCourse>,
        backupDelayMillis: Long,
    ): WidgetAlarmPlan {
        val nextMinutesOfDay = nextSignificantMinute(nowMins, todayCourses)
        val primaryAtMillis = if (nextMinutesOfDay != null && nextMinutesOfDay < MINUTES_PER_DAY) {
            // 1440 == 次日 00:00。LocalTime.of(24,0) 会抛 DateTimeException，所以等于 1440 时走"明日 00:01"分支。
            today.atTime(nextMinutesOfDay / 60, nextMinutesOfDay % 60)
                .atZone(zone).toInstant().toEpochMilli()
        } else {
            // 今日没有更晚的节次了（或恰好落在 00:00） → 安排到明日 00:01，让 widget 切日
            today.plusDays(1).atTime(0, 1)
                .atZone(zone).toInstant().toEpochMilli()
        }
        return WidgetAlarmPlan(
            primaryAtMillis = primaryAtMillis,
            backupAtMillis = primaryAtMillis + backupDelayMillis,
        )
    }

    /**
     * 本机当前能否排**精确**闹钟。
     *
     * API ≤ 30 恒为 true（`setExactAndAllowWhileIdle` 自 API 23 起无需权限）；
     * Android 12+ 读 `canScheduleExactAlarms()`——用户在系统设置里关掉「闹钟与提醒」后
     * 这里会变 false，于是自动退回 inexact，不会崩。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarmManager(context) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * 打开「闹钟与提醒」特殊权限页；打不开时退回应用详情页。
     * 返回 false 表示两条路都没走通（极少数精简 ROM），调用方给个提示即可。
     */
    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val request = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(request) }.isSuccess) return true
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(appDetails) }.isSuccess
    }

    private fun scheduleAt(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        exact: Boolean,
    ) {
        val am = alarmManager(context) ?: return
        val pi = pendingIntent(context, requestCode)
        am.cancel(pi)
        val setInexact = {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
        }
        if (!exact) {
            setInexact()
            return
        }
        // MIUI / HyperOS 在后台严苛模式下偶尔让精确闹钟也抛 SecurityException（权限态与
        // canScheduleExactAlarms() 不一致）。一次失败只降级成 inexact，不能让自循环断掉。
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }.onFailure { setInexact() }
    }

    private fun cancelSlots(context: Context) {
        val am = alarmManager(context) ?: return
        runCatching { am.cancel(pendingIntent(context, REQUEST_PRIMARY)) }
        runCatching { am.cancel(pendingIntent(context, REQUEST_BACKUP)) }
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context.applicationContext, TodayScheduleWidgetReceiver::class.java)
            .setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode,
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

/** [WidgetUpdateScheduler.planNext] 的结果：一次渲染要排的两个唤醒槽。 */
internal data class WidgetAlarmPlan(
    /** 目标边界本身（节次开始 / 结束 / 次日 00:01 切日）。 */
    val primaryAtMillis: Long,
    /** 迟到兜底时刻 = [primaryAtMillis] + 延迟；只有拿不到精确闹钟权限时才会真的注册。 */
    val backupAtMillis: Long,
)
