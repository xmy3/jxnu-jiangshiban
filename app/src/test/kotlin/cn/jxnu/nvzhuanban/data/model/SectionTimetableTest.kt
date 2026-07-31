package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionTimetableTest {

    @Test
    fun endTimeLabelIsStartPlusDuration() {
        // 第 1 节 08:00 开始 → 08:40 结束；第 3 节 09:40 开始 → 10:20 结束（曾误显示为区间终点 09:40）
        assertEquals("08:40", SectionTimetable.endTimeLabel(1))
        assertEquals("10:20", SectionTimetable.endTimeLabel(3))
        assertEquals("12:00", SectionTimetable.endTimeLabel(5))
        assertEquals("21:20", SectionTimetable.endTimeLabel(12))
    }

    @Test
    fun endTimeLabelMatchesEndMinutesForEverySection() {
        for (section in 1..SectionTimetable.SECTION_COUNT) {
            val mins = SectionTimetable.endMinutes(section)
            val expected = "%02d:%02d".format(mins / 60, mins % 60)
            assertEquals(expected, SectionTimetable.endTimeLabel(section))
        }
    }

    @Test
    fun outOfRangeSectionClampsInsteadOfThrowing() {
        assertEquals(SectionTimetable.endTimeLabel(1), SectionTimetable.endTimeLabel(0))
        assertEquals(
            SectionTimetable.endTimeLabel(SectionTimetable.SECTION_COUNT),
            SectionTimetable.endTimeLabel(99),
        )
    }
}
