package cn.jxnu.nvzhuanban.data.model

import cn.jxnu.nvzhuanban.data.network.pages.GraduationAuditPage
import cn.jxnu.nvzhuanban.data.network.pages.sampleHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CreditFormattingTest {
    @Test
    fun `fractional credits from the training plan sample survive display formatting`() {
        val courses = GraduationAuditPage.parse(sampleHtml("xueji.html"))
            .trainingPlan.sections.flatMap { it.courses }
            .filter { it.credit == 0.5f }
        assertTrue(courses.isNotEmpty())
        courses.forEach { assertEquals(it.courseName, "0.5", it.credit!!.formatCredit()) }
    }

    @Test
    fun `keep fractional cumulative credits and suppress integer decimal suffixes`() {
        mapOf(0f to "0", 1f to "1", 1.25f to "1.25", 171.5f to "171.5", 172f to "172")
            .forEach { (credit, expected) -> assertEquals(expected, credit.formatCredit()) }
    }

    @Test
    fun `floating point noise and locale do not leak into credit labels`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("63", 63.000004f.formatCredit())
            assertEquals("0.3", (0.1f + 0.2f).formatCredit())
            assertEquals("1.5", 1.5f.formatCredit())
        } finally {
            Locale.setDefault(original)
        }
    }
}
