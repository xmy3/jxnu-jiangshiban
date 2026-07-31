package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开课查询页（Public_Kkap）解析回归测试。
 *
 * `kkap_form.html` 是 2026-07 实抓的 GET 表单页（公开、无 PII）。
 * 结果表因查询 POST 需要登录会话、开发期拿不到真实样张，用按 ASP.NET GridView
 * 渲染惯例构造的合成 HTML 锁定「表头驱动」解析行为——真实列名 / 列数变化不影响这些断言。
 */
class CourseOfferingPageTest {

    @Test
    fun `parses form dropdowns and tokens from real capture`() {
        val form = CourseOfferingPage.parseForm(sampleHtml("kkap_form.html"))

        // 学期：3 项，value 是 yyyy/M/1 0:00:00（与课表 ddlSterm 同口径），首项为最新学期
        assertEquals(3, form.semesters.size)
        assertEquals("26-27第1学期", form.semesters[0].label)
        assertEquals("2026/9/1 0:00:00", form.semesters[0].value)

        // 学院：首项「不限」，学院代码是 8 位定宽、尾随空格必须原样保留
        assertEquals("不限", form.colleges.first().value)
        val wenxueyuan = form.colleges.first { it.label == "文学院" }
        assertEquals("51000   ", wenxueyuan.value)
        assertEquals(8, wenxueyuan.value.length)
        assertTrue("学院数应超过 30", form.colleges.size > 30)

        // 星期 / 节次都带「不限」首项
        assertEquals("不限", form.weeks.first().value)
        assertEquals("不限", form.sections.first().value)
        assertTrue(form.weeks.any { it.label == "星期一" && it.value == "1" })
        assertTrue(form.sections.any { it.value == "1" })

        // ASP.NET 三件套
        assertTrue(form.viewState.isNotEmpty())
        assertEquals("E0C34098", form.viewStateGenerator)
        assertTrue(form.eventValidation.isNotEmpty())
    }

    @Test
    fun `form page without result table parses as empty result`() {
        val table = CourseOfferingPage.parseResult(sampleHtml("kkap_form.html"))
        assertTrue(table.isEmpty)
        assertTrue(table.columns.isEmpty())
        assertNull(table.message)
    }

    @Test
    fun `parses gridview result with th header`() {
        val table = CourseOfferingPage.parseResult(RESULT_FIXTURE)
        assertEquals(listOf("课程名称", "任课教师", "上课时间", "教室"), table.columns)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("大学英语Ⅲ", "张三", "星期一 第12节", "W5102"), table.rows[0])
        // 缺格行补空串对齐到列数
        assertEquals(listOf("高等数学", "李四", "星期二 第3节", ""), table.rows[1])
        assertNull(table.message)
    }

    @Test
    fun `single cell row becomes message not data`() {
        val table = CourseOfferingPage.parseResult(EMPTY_RESULT_FIXTURE)
        assertTrue(table.isEmpty)
        assertEquals("没有符合条件的记录", table.message)
    }

    @Test
    fun `headerless table keeps all rows as data`() {
        val table = CourseOfferingPage.parseResult(HEADERLESS_FIXTURE)
        assertTrue(table.columns.isEmpty())
        assertEquals(2, table.rows.size)
        assertEquals(listOf("大学英语Ⅲ", "张三"), table.rows[0])
    }

    @Test
    fun `detects jwc bare error body`() {
        assertTrue(CourseOfferingPage.isSystemError("Error:系统错误，请与系统管理员联系！"))
        assertTrue(CourseOfferingPage.isSystemError("  Error:系统错误，请与系统管理员联系！"))
        assertFalse(CourseOfferingPage.isSystemError(sampleHtml("kkap_form.html")))
    }

    @Test
    fun `parses real gvContent 10-column shape`() {
        // 真机实测：数学与统计学院查询返回 10 列。列名与真实一致（序号/单位名称/课程名称标识/
        // 班级名称/任课教师/教室/星期/节次/授课人数/课程讨论区），锁定表头驱动对真实结构的解析。
        val table = CourseOfferingPage.parseResult(REAL_SHAPE_FIXTURE)
        assertEquals(
            listOf("序号", "单位名称", "课程名称标识", "班级名称", "任课教师", "教室", "星期", "节次", "授课人数", "课程讨论区"),
            table.columns,
        )
        assertEquals(1, table.rows.size)
        assertEquals("Matlab语言程序设计", table.rows[0][2])
        assertEquals("王五", table.rows[0][4])
        assertEquals("X3415", table.rows[0][5])
        assertNull(table.message)
    }

    private companion object {
        val REAL_SHAPE_FIXTURE = """
            <html><body><form id="Form1">
            <table id="gvContent" border="1">
              <tr><th>序号</th><th>单位名称</th><th>课程名称标识</th><th>班级名称</th><th>任课教师</th><th>教室</th><th>星期</th><th>节次</th><th>授课人数</th><th>课程讨论区</th></tr>
              <tr><td>1</td><td>数学与统计学院</td><td>Matlab语言程序设计</td><td>教工张三#1班</td><td>王五</td><td>X3415</td><td>星期三</td><td>第67节</td><td>47</td><td>课程讨论区</td></tr>
            </table>
            </form></body></html>
        """.trimIndent()

        val RESULT_FIXTURE = """
            <html><body><form id="Form1">
            <table id="gvContent" border="1">
              <tr><th>课程名称</th><th>任课教师</th><th>上课时间</th><th>教室</th></tr>
              <tr><td>大学英语Ⅲ</td><td>张三</td><td>星期一 第12节</td><td>W5102</td></tr>
              <tr><td>高等数学</td><td>李四</td><td>星期二 第3节</td></tr>
            </table>
            </form></body></html>
        """.trimIndent()

        val EMPTY_RESULT_FIXTURE = """
            <html><body><form id="Form1">
            <table id="gvContent">
              <tr><th>课程名称</th><th>任课教师</th></tr>
              <tr><td colspan="2">没有符合条件的记录</td></tr>
            </table>
            </form></body></html>
        """.trimIndent()

        val HEADERLESS_FIXTURE = """
            <html><body><form id="Form1">
            <table id="gvContent">
              <tr><td>大学英语Ⅲ</td><td>张三</td></tr>
              <tr><td>高等数学</td><td>李四</td></tr>
            </table>
            </form></body></html>
        """.trimIndent()
    }
}
