package cn.jxnu.nvzhuanban.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.jxnu.nvzhuanban.data.storage.AlertPrefs
import cn.jxnu.nvzhuanban.data.storage.ExamReminderStore
import java.time.LocalDate

/**
 * 「考前一天提醒」的闹钟接收器，一个类干两件事：
 *
 *  1. `ACTION_FIRE` —— 闹钟到点了，把存档里那天的考试拼成一条通知发出去。
 *  2. 其它（BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / MY_PACKAGE_REPLACED /
 *     精确闹钟权限变化）—— 系统把我们排的闹钟全弄丢了或时区变了，按 [ExamReminderStore] 重排。
 *
 * 为什么发通知要绕一圈到接收器、而不是直接把通知塞进 `PendingIntent.getActivity`：
 * 闹钟响的时候 App 通常没在运行，接收器是唯一会被系统拉起来的入口；顺带能在发之前再核一次
 * 开关状态和存档内容。
 */
class ExamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ExamReminderScheduler.ACTION_FIRE -> fire(context, intent)
            else -> runCatching { ExamReminderScheduler.rearm(context) }
        }
    }

    private fun fire(context: Context, intent: Intent) {
        // Application.onCreate 已经 init 过，这里是兜底：没 init 时开关读出来是 false，
        // 闹钟会被静默吞掉 —— 提醒类的失效必须是显式的，不能靠"反正有人先调了"。
        AlertPrefs.init(context)
        // 用户可能在排完闹钟之后关掉了开关：这时不该再弹（cancelAll 是异步的，留一道兜底）
        if (!AlertPrefs.examReminderEnabled.value) return
        val epochDay = intent.getLongExtra(ExamReminderScheduler.EXTRA_EPOCH_DAY, Long.MIN_VALUE)
        if (epochDay == Long.MIN_VALUE) return

        val date = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return
        val day = ExamReminderStore.load(context).firstOrNull { it.date == date } ?: return
        // 先摘存档再发：同一闹钟被重复投递（或用户手动重排）时不会弹第二次
        ExamReminderStore.remove(context, date)
        AlertNotifier.notifyExamReminder(context, day)
    }
}
