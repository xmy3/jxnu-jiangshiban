package cn.jxnu.nvzhuanban.ui.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget 系统接收器。除了 Glance 默认处理的 `APPWIDGET_UPDATE`，还监听几类"环境变化"
 * 来强制重绘：
 *  - [WidgetUpdateScheduler.ACTION_TICK]：自家调度的节次切换 / 跨日唤醒
 *  - [Intent.ACTION_DATE_CHANGED]：系统每天 0 点广播（兜底跨日）
 *  - [Intent.ACTION_TIMEZONE_CHANGED] / [Intent.ACTION_TIME_CHANGED]：用户改时区或手动调时
 *  - [Intent.ACTION_LOCALE_CHANGED]：周一 / 周二 等文案随系统语言变
 *  - [Intent.ACTION_MY_PACKAGE_REPLACED]：自升级后重新启动调度循环
 *  - [Intent.ACTION_BOOT_COMPLETED]：重启后立刻重新排唤醒链（否则最多等 30 分钟的
 *    `updatePeriodMillis` 才会恢复准点刷新）
 *  - [ACTION_EXACT_ALARM_PERMISSION_CHANGED]：用户在系统设置里放开「闹钟与提醒」后
 *    立刻按精确闹钟重排（系统在授予时发出；收不到也有「我的」页的 onResume 兜底）
 *
 * 这些都是 protected / 白名单 implicit broadcast，manifest 注册即可工作，
 * 不需要额外运行时权限（BOOT_COMPLETED 只需 [android.Manifest.permission.RECEIVE_BOOT_COMPLETED]
 * 这类普通权限）。
 *
 * 最后一个 widget 被移除时取消挂起的 alarm，避免无谓系统唤醒。
 */
class TodayScheduleWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TodayScheduleWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetUpdateScheduler.ACTION_TICK,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            -> requestUpdate(context.applicationContext)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateScheduler.cancel(context.applicationContext)
    }

    /**
     * 强制让所有已添加的 widget 重绘。用 [goAsync] 把回调从同步 onReceive 撑开到后台协程，
     * 之后 [TodayScheduleWidget.provideGlance] 会再排下一次 alarm，循环就能持续。
     */
    private fun requestUpdate(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                refreshTodayScheduleWidgets(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`（API 31+）。
         * 用字面量而不是引用框架常量：它是编译期常量会被内联，但直接引用会触发 lint 的
         * NewApi（字段标了 @RequiresApi(31)），而这里只是按 action 字符串比较。
         */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
