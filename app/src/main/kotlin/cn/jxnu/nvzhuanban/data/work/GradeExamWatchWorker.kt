package cn.jxnu.nvzhuanban.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.jxnu.nvzhuanban.NvzhuanbanApp
import cn.jxnu.nvzhuanban.data.model.AlertDiff
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamReminderPlan
import cn.jxnu.nvzhuanban.data.model.Grade
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.repository.ExamRepository
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.storage.AlertCheckResult
import cn.jxnu.nvzhuanban.data.storage.AlertKind
import cn.jxnu.nvzhuanban.data.storage.AlertPrefs
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 后台周期检查「有没有新出分 / 考试有没有变」，顺带把「考前一天提醒」的闹钟排好。
 *
 * **它不是推送**，能力边界要诚实对待：
 *  - 没有自建服务端，也没有教务系统的推送接口，只能客户端自己定时拉。
 *  - WorkManager 的周期任务在 Doze / 国产 ROM 省电策略下会被合并推迟，实际延迟从几分钟
 *    到几小时不等。所以通知文案写「新增 / 变更」，绝不写「刚刚」。
 *  - 系统最低周期是 15 分钟；这里用 [AlertScheduler.INTERVAL_HOURS] 小时 —— 出分本来就是
 *    低频事件，把周期压到 15 分钟只是白白耗电和增加教务网的请求。
 *
 * 两种开关共用这一个任务（见 [AlertPrefs.anyEnabled]）：只开「考前一天提醒」时，仍然需要
 * 定期把考试列表拉到本地，否则闹钟无从排期 —— 但那条路径不拉成绩。
 *
 * 会话：**复用冷启动那一次 [NvzhuanbanApp.sessionRestore]**，不在后台另起一遍完整 CAS。
 * 后台静默重提密码是危险动作（连续失败会触发 CAS 风控验证码，而这个 App 没有验证码输入
 * 界面，见 CasLoginClient.classifyCasRejection），所以会话恢复失败时只记状态、不弹通知、
 * 也不重试密码。
 */
class GradeExamWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 用户关掉开关后可能还有一次已在队列里的唤醒，这里再判一次（cancel 是异步的）
        if (!AlertPrefs.anyEnabled) return Result.success()
        val app = applicationContext as? NvzhuanbanApp ?: return Result.success()

        if (!canRunAlertCheck(app.sessionRestore) { AuthRepository.instance.state.value }) {
            AlertPrefs.markChecked(System.currentTimeMillis(), AlertCheckResult.LOGIN_REQUIRED)
            return Result.success()
        }

        val watchChanges = AlertPrefs.enabled.value
        val remindExams = AlertPrefs.examReminderEnabled.value
        // 注意：类体内裸写 `Result` 指的是 ListenableWorker.Result（`doWork()` 的返回类型），
        // kotlin.Result 必须写全名
        val outcomes = mutableListOf<kotlin.Result<*>>()

        // 只开考前一天提醒时不必拉成绩：少一个请求，也少一次"成绩被别人的刷新覆盖"的可能
        if (watchChanges) {
            val grades = runCatching { GradeRepository.instance.refresh() }.also(outcomes::add)
            grades.getOrNull()?.let { applyGrades(it.semesters.flatMap { semester -> semester.grades }) }
        }

        if (watchChanges || remindExams) {
            val exams = runCatching { ExamRepository.instance.refresh() }.also(outcomes::add)
            exams.getOrNull()?.let { all ->
                if (watchChanges) applyExamChanges(all)
                if (remindExams) syncExamReminders(all)
            }
        }

        AlertPrefs.markChecked(System.currentTimeMillis(), classify(outcomes))
        // 失败也返回 success：周期任务靠下一轮自然重试，用 retry() 反而会把任务重排成退避序列
        return Result.success()
    }

    private fun applyGrades(grades: List<Grade>) {
        val fingerprints = AlertDiff.gradeFingerprints(grades)
        if (!AlertPrefs.isBaselined(AlertKind.GRADES)) {
            // 第一次只学基线：否则开启开关的瞬间会把整学期历史成绩当成"新出分"全弹一遍
            AlertPrefs.saveKnown(AlertKind.GRADES, fingerprints)
            AlertPrefs.markBaselined(AlertKind.GRADES)
            return
        }
        val change = AlertDiff.grades(AlertPrefs.known(AlertKind.GRADES), grades)
        AlertPrefs.saveKnown(AlertKind.GRADES, fingerprints)
        AlertNotifier.notifyGrades(applicationContext, change)
    }

    private fun applyExamChanges(exams: List<Exam>) {
        // 只盯还没考的。给上周考完的科目报"考场变了"纯属噪音，而且考完的会自然退出已知集合
        val today = LocalDate.now()
        val upcoming = exams.filter { !it.startTime.toLocalDate().isBefore(today) }
        val fingerprints = AlertDiff.examFingerprints(upcoming)
        if (!AlertPrefs.isBaselined(AlertKind.EXAMS)) {
            AlertPrefs.saveKnown(AlertKind.EXAMS, fingerprints)
            AlertPrefs.markBaselined(AlertKind.EXAMS)
            return
        }
        val change = AlertDiff.exams(AlertPrefs.known(AlertKind.EXAMS), upcoming)
        AlertPrefs.saveKnown(AlertKind.EXAMS, fingerprints)
        AlertNotifier.notifyExams(applicationContext, change)
    }

    /** 每次拉到考试列表都整表重排闹钟：考试改期/撤销后旧闹钟必须跟着消失。 */
    private fun syncExamReminders(exams: List<Exam>) {
        val days = ExamReminderPlan.plan(exams, LocalDateTime.now())
        ExamReminderScheduler.sync(applicationContext, days)
    }

    private fun classify(outcomes: List<kotlin.Result<*>>): AlertCheckResult {
        if (outcomes.isEmpty()) return AlertCheckResult.OK
        // 一路成功就算 OK：另一路失败会在下一轮自愈，副标题没必要吓人
        if (outcomes.any { it.isSuccess }) return AlertCheckResult.OK
        val errors = outcomes.mapNotNull { it.exceptionOrNull() }
        return if (errors.any(::isSessionProblem)) {
            AlertCheckResult.LOGIN_REQUIRED
        } else {
            AlertCheckResult.OFFLINE
        }
    }

    /** 会话被拒（不是网络问题）：说明自动重登也没成，需要用户自己去登录页。 */
    private fun isSessionProblem(error: Throwable): Boolean {
        val jwc = error as? JwcException ?: return false
        return jwc.error is JwcError.SessionExpired
    }
}
