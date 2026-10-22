package cn.jxnu.nvzhuanban.ui.screens.schedule

import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import java.time.LocalDate

internal data class ScheduleDayUpdate(val state: ScheduleScreenState, val baselineWeek: Int)

/** 离线课表只依赖快照日期推进，保持用户主动选择的非本周页面。 */
internal fun advanceOfflineScheduleDay(
    previous: ScheduleScreenState,
    baselineWeek: Int,
    today: LocalDate,
): ScheduleDayUpdate {
    val total = previous.totalWeeks.coerceAtLeast(1)
    val wasOnCurrentWeek = previous.currentWeek != null && previous.selectedWeek == previous.currentWeek
    // 周坐标取"生效的第 1 周周一"（用户校正优先），与顶栏/快照同源；无该字段时退回名义日。
    val phase = SemesterPhase.at(previous.weekOneMonday ?: previous.semesterStart, total, today)
    val state = when (phase) {
        is SemesterPhase.InProgress -> {
            val week = phase.week.coerceIn(1, total)
            previous.copy(
                currentWeek = week,
                vacation = null,
                selectedWeek = if (wasOnCurrentWeek) week else previous.selectedWeek,
            )
        }
        is SemesterPhase.Ended -> previous.copy(
            currentWeek = null,
            vacation = VacationInfo(semesterEnded = true, nextStartDate = previous.vacation?.nextStartDate),
        )
        is SemesterPhase.NotStarted -> previous.copy(
            currentWeek = null,
            vacation = VacationInfo(semesterEnded = false, nextStartDate = phase.weekOneMonday),
        )
        null -> previous
    }
    val todayWeek = when (phase) {
        is SemesterPhase.InProgress -> phase.week.coerceIn(1, total)
        is SemesterPhase.Ended -> total
        is SemesterPhase.NotStarted -> 1
        null -> baselineWeek
    }
    return ScheduleDayUpdate(state, todayWeek)
}
