package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.network.ParseWarnings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parsePage` 告警通道回归测试。
 *
 * 关键覆盖：
 *  - 列数不符 / 时间无法解析的行被丢弃时，[PageParse.warnings] 非空且给出可读摘要
 *  - `parse(html)` 与 `parsePage(html).value` 的结果**完全一致**（旧入口行为不被改动）
 *  - 真实的"空数据"页面（0 场考试 / 0 条补缓考 / 0 门成绩）不产生告警、不抛错
 *  - 壳页面（无任何页面锚点）走 [PageGuard] 抛 Decode，而不是静默返回空列表
 *  - [ParseWarnings.report] 忽略空白串，只投递真正的告警
 */
class ParseWarningsTest {

    @Test
    fun `exam page warns about skipped rows and keeps parse result unchanged`() {
        val page = ExamPage.parsePage(EXAM_PAGE_WITH_BAD_ROWS)

        // 只有 1 行是完整可解析的
        assertEquals(1, page.value.size)
        assertEquals("MA101", page.value.single().courseCode)

        // 旧入口行为与 parsePage().value 逐条一致
        assertEquals(page.value, ExamPage.parse(EXAM_PAGE_WITH_BAD_ROWS))

        // 6 列行 + 坏时间行 = 2 行被丢弃，汇总成一条告警
        assertEquals(1, page.warnings.size)
        assertTrue("告警文案应含丢弃行数: ${page.warnings}", page.warnings.single().contains("2 行"))
    }

    @Test
    fun `grade page warns about rows with unexpected column count`() {
        val page = GradePage.parsePage(GRADE_PAGE_WITH_BAD_ROW)

        val parsed = GradePage.parse(GRADE_PAGE_WITH_BAD_ROW)
        assertEquals(parsed.semesters.flatMap { it.grades }, page.value.semesters.flatMap { it.grades })
        assertEquals(1, page.value.semesters.single().grades.size)
        assertEquals("MA101", page.value.semesters.single().grades.single().courseCode)

        assertEquals(1, page.warnings.size)
        assertTrue("告警文案应含丢弃行数: ${page.warnings}", page.warnings.single().contains("1 行"))
    }

    @Test
    fun `legitimately empty pages produce no warnings and no throw`() {
        // 本学期没有考试：标题 + 空提示 span 仍在，表可能是空的
        val exam = ExamPage.parsePage(
            "<html><body><span id=\"_ctl1_lblTitle\">考试安排</span><span id=\"_ctl1_lblMsg\"></span></body></html>",
        )
        assertTrue(exam.value.isEmpty())
        assertTrue(exam.warnings.isEmpty())

        // 没有补缓考记录
        val makeup = MakeupExamPage.parsePage("<html><body><span id=\"_ctl6_lblMsg\">暂无补缓考安排</span></body></html>")
        assertTrue(makeup.value.isEmpty())
        assertTrue(makeup.warnings.isEmpty())

        // 大一新生还没有成绩：只有个人信息 span
        val grade = GradePage.parsePage("<html><body><span id=\"_ctl6_lblMsg\">学院：计算机信息工程学院　学号：2024050001</span></body></html>")
        assertTrue(grade.value.semesters.isEmpty())
        assertTrue(grade.warnings.isEmpty())
    }

    @Test
    fun `shell page without anchors throws decode instead of returning empty list`() {
        val thrown = assertThrows(JwcException::class.java) {
            ExamPage.parse("<html><body><div>页面框架</div></body></html>")
        }
        assertTrue("应为 Decode：${thrown.error}", thrown.error is JwcError.Decode)
    }

    @Test
    fun `report ignores blank messages and emits the rest`() = runTest {
        val received = async(start = CoroutineStart.UNDISPATCHED) { ParseWarnings.messages.first() }

        ParseWarnings.report("")
        ParseWarnings.report("   ")
        assertFalse("空白告警不应被投递", received.isCompleted)

        ParseWarnings.report("成绩页有 3 行未能解析，列表可能不完整")
        assertEquals("成绩页有 3 行未能解析，列表可能不完整", received.await())
    }

    private companion object {
        /** 1 行正常 + 1 行 6 列 + 1 行时间无法解析。 */
        val EXAM_PAGE_WITH_BAD_ROWS = """
            <html><body>
            <span id="_ctl1_lblTitle">考试安排</span>
            <table id="_ctl1_dgContent">
              <tr><td>课程号</td><td>课程名称</td><td>学号</td><td>考试时间</td><td>教室号</td><td>座位号</td><td>备注</td></tr>
              <tr><td>MA101</td><td>高等数学</td><td>2024050001</td><td>2026-06-20 08:30:00</td><td>文科楼A302</td><td>12 排 8 号</td><td></td></tr>
              <tr><td>EN101</td><td>大学英语</td><td>2024050001</td><td>2026-06-22 14:00:00</td><td>文科楼B210</td><td></td></tr>
              <tr><td>PH101</td><td>大学物理</td><td>2024050001</td><td>not-a-date</td><td>理科楼C101</td><td>3</td><td></td></tr>
            </table>
            </body></html>
        """.trimIndent()

        /** 表头 8 列 + 1 行 8 列（正常） + 1 行 9 列（列数不符）。 */
        val GRADE_PAGE_WITH_BAD_ROW = """
            <html><body>
            <span id="_ctl6_lblMsg">学院：计算机信息工程学院　专业名称：计算机科学与技术　学号：2024050001　姓名：张三　累计学分：4</span>
            <table id="_ctl6_dgContent">
              <tr><td>考试时间</td><td>课程号</td><td>课程名称</td><td>所得学分</td><td>课程成绩</td><td>补考成绩</td><td>标准分</td><td>备注</td></tr>
              <tr><td>25-26第1学期</td><td>MA101</td><td>高等数学</td><td>4.0</td><td>88</td><td></td><td>0.92</td><td></td></tr>
              <tr><td>25-26第1学期</td><td>PH101</td><td>大学物理</td><td>4.0</td><td>55</td><td>75</td><td>-0.30</td><td>补考通过</td><td>多出来的一列</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
