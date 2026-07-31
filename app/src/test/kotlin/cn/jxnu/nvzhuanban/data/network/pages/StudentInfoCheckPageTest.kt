package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentInfoCheckPageTest {

    @Test
    fun `parses basic info from saved sample`() {
        val info = StudentInfoCheckPage.parse(sampleHtml("student_info_check.html"))

        assertEquals("2024050001", info.studentId)
        assertEquals("张三", info.name)
        assertEquals("25350102150001", info.examId)
        assertEquals("男", info.gender)
        assertEquals("汉族", info.ethnicity)
        // 出生日期 yyyyMMdd -> yyyy-MM-dd
        assertEquals("2007-01-01", info.birthDate)
        assertTrue(
            "班级应含「计算机科学与技术」: ${info.className}",
            info.className.contains("计算机科学与技术"),
        )
    }

    @Test
    fun `keeps raw birth date when not eight digits`() {
        val html = """<html><body><span id="_ctl6_lblCSRQ">2007年</span></body></html>"""
        assertEquals("2007年", StudentInfoCheckPage.parse(html).birthDate)
    }

    @Test
    fun `tolerates missing spans gracefully`() {
        val info = StudentInfoCheckPage.parse("<html><body></body></html>")
        assertEquals("", info.studentId)
        assertEquals("", info.name)
        assertEquals("", info.birthDate)
    }

    @Test
    fun `matches spans by id suffix regardless of prefix`() {
        val html = """
            <html><body>
              <span id="_ctl6_lblXH">2024050099</span>
              <span id="_ctl6_lblKSH">25360000150099</span>
            </body></html>
        """.trimIndent()
        val info = StudentInfoCheckPage.parse(html)
        assertEquals("2024050099", info.studentId)
        assertEquals("25360000150099", info.examId)
    }
}
