package cn.jxnu.nvzhuanban.ui.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
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
 *
 * 这些都是 protected / 白名单 implicit broadcast，manifest 注册即可工作，
 * 不需要 RECEIVE_BOOT_COMPLETED 等额外权限。
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
                val mgr = GlanceAppWidgetManager(context)
                if (mgr.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                    TodayScheduleWidget().updateAll(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
