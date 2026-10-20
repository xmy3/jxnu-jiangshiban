package cn.jxnu.nvzhuanban.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.jxnu.nvzhuanban.data.storage.AlertPrefs
import java.util.concurrent.TimeUnit

/**
 * 出分 / 考试变更后台检查的调度入口。
 *
 * 生命周期只有三种调用点：
 *  - 开关打开：[sync] 入队；关掉：[sync] 发现 `enabled == false` 就取消。
 *  - 冷启动且登录成功：补一次 [sync]（WorkManager 的持久化任务通常不需要，但被 ROM 清过
 *    数据/禁过自启后需要重排）。
 *  - 登出 / 换号：调用方显式 [cancel]，不再每 4 小时把进程叫起来做一次注定失败的检查。
 *
 * 用 [ExistingPeriodicWorkPolicy.KEEP] 而不是 UPDATE：UPDATE 会按新请求重算下次运行时间，
 * 而这个方法在每次冷启动都会调用 —— 频繁开 App 的用户会把检查窗口无限往后推，永远轮不到。
 */
object AlertScheduler {

    /** 检查周期（小时）。改小只是更及时，代价是更多电量和教务网请求。 */
    const val INTERVAL_HOURS = 4L

    private const val UNIQUE_WORK_NAME = "grade_exam_watch"

    /** 打开开关时补跑的一次性任务。刻意不复用周期任务的唯一名 —— 两者共用名字会互相顶掉。 */
    private const val ONE_SHOT_WORK_NAME = "grade_exam_watch_now"

    /**
     * @param runNow 用户刚打开开关时传 true：立刻补跑一次（[ONE_SHOT_WORK_NAME]），否则要等
     *   周期任务的下一次唤醒 —— 最多 4 小时。这个差别在「考前一天提醒」上是真实的：学生要是
     *   在考前那晚八点前才打开开关，等 4 小时就错过提醒了。
     *   `ExistingPeriodicWorkPolicy.KEEP` 意味着周期任务已存在时不会重排，所以必须另起一手。
     */
    fun sync(context: Context, runNow: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        // 出分轮询和考前一天提醒共用这一个任务：考试数据得先拉到本地，闹钟才知道排哪天。
        // 两个开关都关掉才停。
        if (!AlertPrefs.anyEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(ONE_SHOT_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<GradeExamWatchWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        if (runNow) {
            workManager.enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GradeExamWatchWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(ONE_SHOT_WORK_NAME)
    }
}
