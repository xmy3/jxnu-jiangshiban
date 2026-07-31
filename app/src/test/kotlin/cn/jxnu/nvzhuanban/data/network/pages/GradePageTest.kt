package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩页解析回归测试。
 *
 * 关键覆盖：
 *   - `_ctl6_lblMsg` 的 StudentMeta 字段抽取（学院/专业/班级/学号/姓名/累计学分）
 *   - `_ctl6_dgContent` 的 8 列首行（含学期 rowspan）+ 7 列后续行的列偏移
 *   - 表头跳过、空 makeupScore/remark → null、标准分（Z-score 风格）的 toFloat
 *   - groupBy 保留遍历顺序 → semester 列表顺序与 HTML 一致
 */
class GradePageTest {

    @Test
    fun `parses student meta from lblMsg`() {
        val parsed = GradePage.parse(FIXTURE)
        val meta = parsed.meta
        assertEquals("计算机信息工程学院", meta.college)
        assertEquals("计算机科学与技术", meta.major)
        assertEquals("计科24-1班", meta.className)
        assertEquals("20240101", meta.studentId)
        assertEquals("张三", meta.name)
        assertEquals(7.5f, meta.totalCredit!!, 0.001f)
    }

    @Test
    fun `groups grades into semesters preserving order`() {
        val parsed = GradePage.parse(FIXTURE)
        // HTML 里学期出现顺序 = 24-25第1学期 → 24-25第2学期，groupBy 应保持
        assertEquals(2, parsed.semesters.size)
        assertEquals("24-25第1学期", parsed.semesters[0].semester)
        assertEquals("24-25第2学期", parsed.semesters[1].semester)
    }

    @Test
    fun `applies rowspan offset for first row of each semester`() {
        val parsed = GradePage.parse(FIXTURE)
        val sem1 = parsed.semesters.first { it.semester == "24-25第1学期" }
        assertEquals(2, sem1.grades.size)

        // 首行 8 列：学期占第 0 列，offset=1，课程号在 cells[1]
        val math = sem1.grades.first { it.courseCode == "MA101" }
        assertEquals("高等数学", math.courseName)
        assertEquals(4.0f, math.credit, 0.001f)
        assertEquals("88", math.score)
        assertEquals(0.92f, math.gpa!!, 0.001f)
        assertNull("无补考时 makeupScore 应为 null", math.makeupScore)
        assertNull("空备注应折为 null", math.remark)

        // 第二行 7 列（无 rowspan），currentSemester 继承自上一行
        val english = sem1.grades.first { it.courseCode == "EN101" }
        assertEquals("24-25第1学期", english.semester)
        assertEquals("良好", english.score)
    }

    @Test
    fun `keeps non-null makeup and remark when present`() {
        val parsed = GradePage.parse(FIXTURE)
        val sem2 = parsed.semesters.first { it.semester == "24-25第2学期" }
        val physics = sem2.grades.first { it.courseCode == "PH101" }
        assertEquals("55", physics.score)
        assertEquals("75", physics.makeupScore)
        assertEquals("补考通过", physics.remark)
    }

    @Test
    fun `parses negative standard score`() {
        // 标准分是 Z-score 风格、可为负（见 CLAUDE.md 领域约定）；负号绝不能在 toFloat 前被清洗掉
        val parsed = GradePage.parse(FIXTURE)
        val physics = parsed.semesters
            .first { it.semester == "24-25第2学期" }
            .grades.first { it.courseCode == "PH101" }
        assertEquals(-0.30f, physics.gpa!!, 0.0001f)
    }

    @Test
    fun `skips header row that starts with 考试时间`() {
        val parsed = GradePage.parse(FIXTURE)
        val allCodes = parsed.semesters.flatMap { it.grades }.map { it.courseCode }
        assertTrue("表头不应混进成绩", "课程号" !in allCodes)
    }

    @Test
    fun `returns empty meta when lblMsg missing but grade table present`() {
        // 只缺 lblMsg 不算壳页（成绩表锚点还在），meta 降级为空但不抛
        val html = """
            <html><body>
            <table id="_ctl6_dgContent">
              <tr><td>考试时间</td><td>课程号</td><td>课程名称</td><td>所得学分</td><td>课程成绩</td><td>补考成绩</td><td>标准分</td><td>备注</td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val parsed = GradePage.parse(html)
        assertNotNull(parsed.meta)
        assertNull(parsed.meta.college)
        assertNull(parsed.meta.totalCredit)
    }

    @Test
    fun `throws decode error on shell page missing both anchors`() {
        // 会话半失效时 jwc 可能返回 200 的业务壳页（有框架无数据）。JwcResponseGuard
        // 只能识别登录页形态，拦不住它；解析器必须 fail-fast 而不是静默解析成
        // 「空 meta + 0 条成绩」——否则空结果被 GradeRepository 缓存，用户看到
        // 无提示的空成绩单（回归保护：间歇性"成绩看不到"）。
        val thrown = assertThrows(JwcException::class.java) {
            GradePage.parse("<html><body><div>页面框架</div></body></html>")
        }
        assertTrue(thrown.error is JwcError.Decode)
    }

    private companion object {
        // 注意：HTML 里 lblMsg 文本字段之间用全角空格分隔（与教务真实页一致）
        val FIXTURE = """
            <html><body>
            <span id="_ctl6_lblMsg">学院：计算机信息工程学院　专业名称：计算机科学与技术　班级名称：计科24-1班　学号：20240101　姓名：张三　累计学分：7.5</span>
            <table id="_ctl6_dgContent">
              <tr><td>考试时间</td><td>课程号</td><td>课程名称</td><td>所得学分</td><td>课程成绩</td><td>补考成绩</td><td>标准分</td><td>备注</td></tr>
              <tr>
                <td rowspan="2">24-25第1学期</td>
                <td>MA101</td><td>高等数学</td><td>4.0</td><td>88</td><td></td><td>0.92</td><td></td>
              </tr>
              <tr>
                <td>EN101</td><td>大学英语</td><td>3.0</td><td>良好</td><td></td><td>0.85</td><td></td>
              </tr>
              <tr>
                <td rowspan="1">24-25第2学期</td>
                <td>PH101</td><td>大学物理</td><td>4.0</td><td>55</td><td>75</td><td>-0.30</td><td>补考通过</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
