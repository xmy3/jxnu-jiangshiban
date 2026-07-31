package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentSearchPageTest {

    @Test
    fun `parses 10 students from saved sample`() {
        val html = sampleHtml("search_student.html")

        val parsed = StudentSearchPage.parse(html)

        assertEquals(10, parsed.students.size)
        val first = parsed.students.first()
        assertEquals("张三", first.name)
        assertEquals("2024050001", first.studentId)
        assertEquals("计算机信息工程学院", first.department)
        // 班级名称按原文 trim 即可（末尾会带教务网拼的多余空格，trim 后保留有意义部分）
        assertTrue(
            "班级名称里应包含「计算机」: ${first.className}",
            first.className.contains("计算机"),
        )
        assertEquals("男", first.gender)
        assertEquals("MjAyNDA1MDAwMQ==", first.userNum)
    }

    @Test
    fun `parses message and count from lblMsg`() {
        val html = sampleHtml("search_student.html")

        val parsed = StudentSearchPage.parse(html)

        assertNotNull(parsed.message)
        assertTrue(
            "学生检索 message 应保留原文「只显示前」: ${parsed.message}",
            parsed.message!!.contains("只显示前") && parsed.message!!.contains("10 条记录"),
        )
        assertEquals(10, StudentSearchPage.extractCount(parsed.message))
    }

    @Test
    fun `extracts ASP_NET hidden fields`() {
        val html = sampleHtml("search_student.html")

        val parsed = StudentSearchPage.parse(html)

        assertTrue("__VIEWSTATE 不能为空", parsed.viewState.isNotBlank())
        assertEquals("70142F31", parsed.viewStateGenerator)
        assertTrue("__EVENTVALIDATION 不能为空", parsed.eventValidation.isNotBlank())
    }

    @Test
    fun `skips header row and drops rows without UserNum`() {
        val html = """
            <html><body>
              <table id="_ctl1_dgContent">
                <tr><td>所在单位</td><td>班级名称</td><td>姓名</td><td>学号</td><td>性别</td><td>操作</td></tr>
                <tr>
                  <td>计算机信息工程学院</td>
                  <td>24级计算机科学与技术（师范）1班</td>
                  <td>测试学生</td><td>2024050099</td><td>女</td>
                  <td>
                    <a href="javascript:OpenWindow('All_StudentInfor.ascx&amp;UserType=Student&amp;UserNum=MjAyNDA1MDA5OQ==')">基本信息</a>
                  </td>
                </tr>
                <tr>
                  <td>X</td><td>X</td><td>无UserNum</td><td>X</td><td>女</td><td>—</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = StudentSearchPage.parse(html)

        assertEquals(1, parsed.students.size)
        val s = parsed.students.first()
        assertEquals("测试学生", s.name)
        assertEquals("MjAyNDA1MDA5OQ==", s.userNum)
    }
}
