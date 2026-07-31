package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 学期相位判定的边界回归。日期全部手工核对过星期：
 * 2025-09-01 = 周一、2026-03-01 = 周日、2026-09-01 = 周二（教务 option 是每年固定的
 * 3/1、9/1 名义日期，星期逐年漂移，最近周一对齐必须三种情况都吃得下）。
 */
class SemesterPhaseTest {

    @Test
    fun `weekOneMonday keeps a Monday start as-is`() {
        assertEquals(
            LocalDate.of(2025, 9, 1),
            SemesterPhase.weekOneMonday(LocalDate.of(2025, 9, 1)),
        )
    }

    @Test
    fun `weekOneMonday rolls Tue-Thu back to previous Monday`() {
        // 2026-09-01 周二 → 8/31；9/1 落在第 1 周内
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterPhase.weekOneMonday(LocalDate.of(2026, 9, 1)),
        )
        // 周四也回退
        assertEquals(
            LocalDate.of(2026, 8, 31),
            SemesterPhase.weekOneMonday(LocalDate.of(2026, 9, 3)),
        )
    }

    @Test
    fun `weekOneMonday rolls Fri-Sun forward to next Monday`() {
        // 2026-03-01 周日（25-26第2学期的真实 option 值）→ 3/2 周一开始第 1 周
        assertEquals(
            LocalDate.of(2026, 3, 2),
            SemesterPhase.weekOneMonday(LocalDate.of(2026, 3, 1)),
        )
        // 周五 → 下周一
        assertEquals(
            LocalDate.of(2026, 9, 7),
            SemesterPhase.weekOneMonday(LocalDate.of(2026, 9, 4)),
        )
    }

    @Test
    fun `phase boundaries for a Monday start`() {
        val start = LocalDate.of(2025, 9, 1) // 周一；18 周 → 最后一天 2026-01-04（周日）
        assertEquals(SemesterPhase.NotStarted(start), SemesterPhase.at(start, 18, LocalDate.of(2025, 8, 31)))
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, start))
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, LocalDate.of(2025, 9, 7)))
        assertEquals(SemesterPhase.InProgress(2), SemesterPhase.at(start, 18, LocalDate.of(2025, 9, 8)))
        assertEquals(SemesterPhase.InProgress(18), SemesterPhase.at(start, 18, LocalDate.of(2026, 1, 4)))
        assertEquals(SemesterPhase.Ended, SemesterPhase.at(start, 18, LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `Tuesday nominal start counts week one from the previous Monday`() {
        val start = LocalDate.of(2026, 9, 1) // 周二，26-27第1学期的真实 option 值
        // 8/30（周日）还在假期：NotStarted 携带对齐后的 8/31（真实上课首日）——
        // 倒计时「距开学还有 1 天」，不再锚定名义 9/1 多算一天
        assertEquals(
            SemesterPhase.NotStarted(LocalDate.of(2026, 8, 31)),
            SemesterPhase.at(start, 18, LocalDate.of(2026, 8, 30)),
        )
        // 真实开学日 8/31（周一）当天即第 1 周进行中，不用等到名义 9/1
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, LocalDate.of(2026, 8, 31)))
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, start))
        assertEquals(SemesterPhase.InProgress(2), SemesterPhase.at(start, 18, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `Sunday nominal start counts weeks from the next Monday`() {
        val start = LocalDate.of(2026, 3, 1) // 周日
        // 名义开学日当天还没到第 1 周周一：未开学。NotStarted 携带对齐后的 3/2（真实上课首日），
        // UI 如实倒计时「距开学还有 1 天」，而不是按名义日误显「即将开学」
        assertEquals(
            SemesterPhase.NotStarted(LocalDate.of(2026, 3, 2)),
            SemesterPhase.at(start, 18, start),
        )
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, LocalDate.of(2026, 3, 2)))
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 18, LocalDate.of(2026, 3, 8)))
        assertEquals(SemesterPhase.InProgress(2), SemesterPhase.at(start, 18, LocalDate.of(2026, 3, 9)))
        // 第 18 周最后一天 = 3/2 + 18*7 - 1 = 7/5（周日）；7/6 起进入暑假
        assertEquals(SemesterPhase.InProgress(18), SemesterPhase.at(start, 18, LocalDate.of(2026, 7, 5)))
        assertEquals(SemesterPhase.Ended, SemesterPhase.at(start, 18, LocalDate.of(2026, 7, 6)))
        // 本次改动的直接动机：暑假当天必须判为"已放假"，而不是旧算法的「第 20 周」
        assertEquals(SemesterPhase.Ended, SemesterPhase.at(start, 18, LocalDate.of(2026, 7, 17)))
    }

    @Test
    fun `null start yields null and non-positive totalWeeks is clamped to one week`() {
        assertNull(SemesterPhase.at(null, 18, LocalDate.of(2026, 7, 17)))
        val start = LocalDate.of(2025, 9, 1)
        assertEquals(SemesterPhase.InProgress(1), SemesterPhase.at(start, 0, LocalDate.of(2025, 9, 7)))
        assertEquals(SemesterPhase.Ended, SemesterPhase.at(start, 0, LocalDate.of(2025, 9, 8)))
    }
}
