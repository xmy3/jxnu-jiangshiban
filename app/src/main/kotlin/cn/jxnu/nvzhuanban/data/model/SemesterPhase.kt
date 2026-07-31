package cn.jxnu.nvzhuanban.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 「今天」相对某个学期的相位。课表页的「本周 / 今」高亮、假期横幅、开学倒计时都由它派生。
 *
 * 背景：教务网学期 option 的 value 是**名义固定日期**（每年 3 月 1 日 / 9 月 1 日，见
 * samples 里的 ddlSterm），与星期无关，不是真实的"第一周周一"。因此周次只能按
 * [weekOneMonday]（名义日期对齐到最近的周一）近似推算，这也是全 app 统一的周坐标系
 * （课表列头日期、widget 的"第 N 周"都必须用同一套，否则「今」列高亮会落在错误的日期上）。
 * 开学日的对外展示（「距开学 X 天」倒计时、「X月X日 开学」、"本学期"切换点）同样锚定
 * 对齐后的周一——全 app 不把名义日期当作开学日示人（名义 9/1 落在周二时真实开学是 8/31）。
 */
sealed interface SemesterPhase {

    /** 学期进行中，[week] 是 1-based 当前教学周（保证 1..totalWeeks）。 */
    data class InProgress(val week: Int) : SemesterPhase

    /**
     * 学期尚未开始；[weekOneMonday] 是该学期第 1 周的周一（名义开学日对齐最近周一后的
     * **真实上课首日**），「距开学 X 天」倒计时和「X月X日 开学」文案都锚定它。
     * 不要改回名义日期：名义 9/1 落在周二时整个假期倒计时会多算 1 天，名义 3/1 落在
     * 周日时会在开学前一天就误显「即将开学」。
     */
    data class NotStarted(val weekOneMonday: LocalDate) : SemesterPhase

    /** 学期已结束（今天已越过第 totalWeeks 周的周日）——寒暑假。 */
    data object Ended : SemesterPhase

    companion object {

        /**
         * 学期第 1 周的周一：把名义开学日对齐到**最近的**周一。
         * 周一~周四 → 回退到本周周一；周五~周日 → 推进到下周一。
         *
         * 两个真实样本的验证：`2026/3/1`（周日）→ 3/2（周一开始上课，符合校历直觉）；
         * `2026/9/1`（周二）→ 8/31（9/1 已在第 1 周内）。之前 UI 里 header 用
         * previousOrSame、周次推算不对齐，两套坐标在开学日非周一的学期会错开一周，
         * 统一收口到这里。
         */
        fun weekOneMonday(semesterStart: LocalDate): LocalDate {
            val prev = semesterStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return if (ChronoUnit.DAYS.between(prev, semesterStart) <= 3) prev else prev.plusWeeks(1)
        }

        /**
         * 判定 [today] 相对学期（开学日 [semesterStart]、共 [totalWeeks] 周）的相位。
         * [semesterStart] 为 null（教务没给 / 数据未加载）时返回 null，调用方自行兜底。
         */
        fun at(semesterStart: LocalDate?, totalWeeks: Int, today: LocalDate): SemesterPhase? {
            if (semesterStart == null) return null
            val monday = weekOneMonday(semesterStart)
            if (today.isBefore(monday)) return NotStarted(monday)
            val week = (ChronoUnit.DAYS.between(monday, today) / 7).toInt() + 1
            return if (week > totalWeeks.coerceAtLeast(1)) Ended else InProgress(week)
        }
    }
}
