package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentDetailPageTest {

    @Test
    fun `parses student info from saved sample`() {
        val html = sampleHtml("student_detail.html")

        val info = StudentDetailPage.parse(html)

        assertEquals("张三", info.name)
        assertEquals("男", info.gender)
        assertEquals("2024050001", info.studentId)
        assertTrue(
            "班级名称应包含「计算机科学与技术」: ${info.className}",
            info.className.contains("计算机科学与技术"),
        )
    }

    @Test
    fun `tolerates missing spans gracefully`() {
        val info = StudentDetailPage.parse("<html><body></body></html>")
        assertEquals("", info.name)
        assertEquals("", info.gender)
        assertEquals("", info.studentId)
        assertEquals("", info.className)
    }

    @Test
    fun `matches spans by id suffix regardless of prefix`() {
        val html = """
            <html><body>
              <span id="_ctl9_lblXM">李四</span>
              <span id="_ctl9_lblXB">女</span>
              <span id="_ctl9_lblXH">2024050099</span>
              <span id="_ctl9_lblBJ">25计科师范1班</span>
            </body></html>
        """.trimIndent()

        val info = StudentDetailPage.parse(html)

        assertEquals("李四", info.name)
        assertEquals("女", info.gender)
        assertEquals("2024050099", info.studentId)
        assertEquals("25计科师范1班", info.className)
    }
}
