package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherSearchPageTest {

    @Test
    fun `parses single teacher from saved sample`() {
        val html = sampleHtml("search_teacher.html")

        val parsed = TeacherSearchPage.parse(html)

        assertEquals(1, parsed.teachers.size)
        val t = parsed.teachers.first()
        assertEquals("李四", t.name)
        assertEquals("020001", t.teacherId)        // 教号尾部空格应被 trim
        assertEquals("计算机信息工程学院", t.department)
        assertEquals("男", t.gender)
        assertEquals("MDIwMDAx", t.userNum)        // base64('020001') 原样保留
    }

    @Test
    fun `parses count message from lblMsg span`() {
        val html = sampleHtml("search_teacher.html")

        val parsed = TeacherSearchPage.parse(html)

        assertNotNull(parsed.message)
        assertTrue("应含「1 条记录」: ${parsed.message}", parsed.message!!.contains("1 条记录"))
        assertEquals(1, TeacherSearchPage.extractCount(parsed.message))
    }

    @Test
    fun `extracts ASP_NET hidden fields`() {
        val html = sampleHtml("search_teacher.html")

        val parsed = TeacherSearchPage.parse(html)

        assertTrue("__VIEWSTATE 不能为空", parsed.viewState.isNotBlank())
        assertEquals("70142F31", parsed.viewStateGenerator)
        assertTrue("__EVENTVALIDATION 不能为空", parsed.eventValidation.isNotBlank())
    }

    @Test
    fun `returns empty list when content table absent`() {
        val parsed = TeacherSearchPage.parse("<html><body><span id='_ctl1_lblMsg'></span></body></html>")

        assertTrue(parsed.teachers.isEmpty())
        assertNull(parsed.message)
    }

    @Test
    fun `skips header row by first cell text`() {
        val html = """
            <html><body>
              <table id="_ctl1_dgContent">
                <tr><td>所在单位</td><td>姓名</td><td>教号</td><td>性别</td><td>操作</td><td>操作2</td></tr>
                <tr>
                  <td>计算机信息工程学院</td><td>测试教师</td><td>020099</td><td>女</td>
                  <td><a href="javascript:OpenWindow('All_TeacherInfor.ascx&amp;UserType=Teacher&amp;UserNum=MDIwMDk5')">基本信息</a></td>
                  <td><a href="javascript:OpenWindow('Xfz_Kcb.ascx&amp;UserType=Teacher&amp;UserNum=MDIwMDk5')">课表</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = TeacherSearchPage.parse(html)

        assertEquals(1, parsed.teachers.size)
        assertEquals("测试教师", parsed.teachers.first().name)
        assertEquals("MDIwMDk5", parsed.teachers.first().userNum)
    }

    @Test
    fun `drops rows without UserNum`() {
        // 完全没有 UserNum 的行应被丢弃，避免下游用空 base64 构造 URL
        val html = """
            <html><body>
              <table id="_ctl1_dgContent">
                <tr><td>所在单位</td><td>姓名</td><td>教号</td><td>性别</td><td>操作</td><td>操作2</td></tr>
                <tr><td>X</td><td>无名</td><td>000000</td><td>女</td><td>—</td><td>—</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = TeacherSearchPage.parse(html)

        assertTrue(parsed.teachers.isEmpty())
    }
}
