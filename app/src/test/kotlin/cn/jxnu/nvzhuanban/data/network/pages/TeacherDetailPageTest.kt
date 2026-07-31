package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherDetailPageTest {

    @Test
    fun `parses teacher info from saved sample`() {
        val html = sampleHtml("teacher_detail.html")

        val info = TeacherDetailPage.parse(html)

        assertEquals("李四", info.name)
        assertEquals("男", info.gender)
        assertEquals("教授", info.title)
        assertEquals("", info.email)       // 样本中该字段为空
        assertEquals("", info.intro)       // 样本中该字段为空
    }

    @Test
    fun `tolerates missing spans gracefully`() {
        val info = TeacherDetailPage.parse("<html><body></body></html>")

        assertEquals("", info.name)
        assertEquals("", info.gender)
        assertEquals("", info.email)
        assertEquals("", info.title)
        assertEquals("", info.intro)
    }

    @Test
    fun `matches spans by id suffix regardless of prefix`() {
        // 真实页面用 `_ctl6_lblName`，但教务网历史上也出现过 `_ctl1_lblName` —— suffix 匹配更稳
        val html = """
            <html><body>
              <span id="_ctl9_lblName">王五</span>
              <span id="_ctl9_lblSex">女</span>
              <span id="_ctl9_lblZC">副教授</span>
              <span id="_ctl9_lblEmail">ww@example.com</span>
              <span id="_ctl9_lblJJ">主讲 XX 课程</span>
            </body></html>
        """.trimIndent()

        val info = TeacherDetailPage.parse(html)

        assertEquals("王五", info.name)
        assertEquals("女", info.gender)
        assertEquals("副教授", info.title)
        assertEquals("ww@example.com", info.email)
        assertTrue(info.intro.contains("主讲"))
    }
}
