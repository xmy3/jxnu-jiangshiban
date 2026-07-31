package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补缓考考试安排页解析回归测试。
 *
 * 关键覆盖：
 *   - `_ctl6_dgContent` 13 列结构
 *   - 表头跳过、`&nbsp;` → null
 *   - 「考试方式」承载真实考试通知文案
 */
class MakeupExamPageTest {

    @Test
    fun `parses a make-up exam row`() {
        val items = MakeupExamPage.parse(FIXTURE)
        assertEquals(1, items.size)
        val it = items.single()
        assertEquals("瑶湖", it.campus)
        assertEquals("人工智能学院", it.college)
        assertEquals("24级计算机科学与技术（师范）1班", it.className)
        assertEquals("张三", it.studentName)
        assertEquals("2024050001", it.studentId)
        assertEquals("056001", it.courseCode)
        assertEquals("大学体育Ⅰ", it.courseName)
        assertEquals("普通", it.courseType)
        assertEquals("体育学院", it.managingDept)
        assertNull("教室号为 &nbsp; → null", it.location)
        assertNull("考试时间为 &nbsp; → null", it.examTime)
        assertNotNull(it.examMethod)
        assertTrue(
            "考试方式应包含时间/地点说明",
            it.examMethod!!.contains("瑶湖校区C田径场"),
        )
        assertEquals("补考", it.remark)
    }

    @Test
    fun `skips header row and wrong column count`() {
        val items = MakeupExamPage.parse(FIXTURE_WRONG_COLS)
        assertEquals(0, items.size)
    }

    @Test
    fun `parses real buhuankao sample without throwing`() {
        val html = javaClass.getResourceAsStream("/samples/buhuankao.html")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val items = MakeupExamPage.parse(html)
        // 真实样本里恰好 1 条
        assertTrue("至少 1 条记录", items.isNotEmpty())
        items.forEach { exam ->
            assertTrue("课程名非空", exam.courseName.isNotBlank())
            assertTrue("课程号非空", exam.courseCode.isNotBlank())
        }
    }

    private companion object {
        val FIXTURE = """
            <html><body>
            <table id="_ctl6_dgContent">
              <tr>
                <td>教学区名称</td><td>学院</td><td>班级</td><td>姓名</td><td>学号</td>
                <td>课程号</td><td>课程名称标识</td><td>课程类型</td><td>课程管理单位</td>
                <td>教室号</td><td>考试时间</td><td>考试方式</td><td>备注</td>
              </tr>
              <tr>
                <td>瑶湖</td><td>人工智能学院</td><td>24级计算机科学与技术（师范）1班</td>
                <td>张三</td><td>2024050001</td><td>056001</td><td>大学体育Ⅰ</td>
                <td>普通</td><td>体育学院</td>
                <td>&nbsp;</td><td>&nbsp;</td>
                <td>请于3月10日下午15:00在瑶湖校区C田径场参加考试，联系电话：88880000</td>
                <td>补考</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val FIXTURE_WRONG_COLS = """
            <html><body>
            <table id="_ctl6_dgContent">
              <tr><td>1</td><td>2</td><td>3</td></tr>
              <tr><td>a</td><td>b</td><td>c</td><td>d</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
