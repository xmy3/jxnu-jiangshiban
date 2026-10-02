package cn.jxnu.nvzhuanban.ui.widget

import cn.jxnu.nvzhuanban.data.model.CalendarAdjustment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetCalendarLabelTest {
    @Test
    fun holidayExplainsWhyThereAreNoCourses() {
        val holiday = CalendarAdjustment(LocalDate.of(2026, 10, 2), "国庆")

        assertEquals("国庆放假，今日停课", widgetCalendarLabel(holiday))
    }

    @Test
    fun makeupShowsWeekdayWithoutClaimingAnOriginalDate() {
        val makeup = CalendarAdjustment(
            LocalDate.of(2026, 10, 10), "国庆调课", LocalDate.of(2026, 10, 9),
        )

        assertEquals("国庆调课，补周五的课", widgetCalendarLabel(makeup))
    }
}
