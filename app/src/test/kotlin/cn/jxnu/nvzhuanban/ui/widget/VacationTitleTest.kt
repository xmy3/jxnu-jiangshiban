package cn.jxnu.nvzhuanban.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** widget 假期态标题的月份分档（见 [vacationTitle] 注释）。 */
class VacationTitleTest {

    @Test
    fun winterMonthsMapToWinterVacation() {
        for (m in listOf(12, 1, 2, 3)) assertEquals("寒假中 ❄️", vacationTitle(m))
    }

    @Test
    fun summerMonthsMapToSummerVacation() {
        for (m in listOf(6, 7, 8, 9)) assertEquals("暑假中 ⛱️", vacationTitle(m))
    }

    @Test
    fun midSemesterMonthsFallBackToNeutral() {
        for (m in listOf(4, 5, 10, 11)) assertEquals("假期中", vacationTitle(m))
    }
}
