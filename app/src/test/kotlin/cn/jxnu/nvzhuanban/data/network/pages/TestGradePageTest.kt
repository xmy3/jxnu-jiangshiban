package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试出分页（xfz_Test_cj.ascx）解析回归测试。fixture 来自 `samples/test_grades.html` 的关键片段。
 *
 * 关键覆盖：
 *  - 页面顶部信息（pageTitle / 学号 / 姓名 / 考试学期 / disclaimer）
 *  - 主专业课程成绩 `_ctl6_gvZZY` 11 列的字段映射
 *  - 空单元格（HTML 里是 `&nbsp;`，text() 之后是不间断空格 ` `）归一为 null
 *  - 双专业课程成绩 `_ctl6_gvSZY` 仅含 `<td colspan="10">没有记录</td>` 时整组被过滤
 */
class TestGradePageTest {

    @Test
    fun `parses page title and student meta`() {
        val parsed = TestGradePage.parse(FIXTURE)
        assertEquals("25-26第2学期期末成绩查询", parsed.pageTitle)
        assertEquals("2024050001", parsed.studentId)
        assertEquals("张三", parsed.studentName)
        assertEquals("2026-03-01", parsed.semesterDate)
        assertNotNull(parsed.disclaimer)
        assertTrue("disclaimer 应包含核心提示", parsed.disclaimer!!.contains("仅供参"))
    }

    @Test
    fun `keeps only non-empty groups`() {
        val parsed = TestGradePage.parse(FIXTURE)
        // 主专业有 4 条；双专业 "没有记录" 整组应被丢弃
        assertEquals(1, parsed.groups.size)
        assertEquals("主专业课程成绩", parsed.groups[0].title)
        assertEquals(4, parsed.groups[0].grades.size)
    }

    @Test
    fun `maps 11 columns to TestGrade fields`() {
        val parsed = TestGradePage.parse(FIXTURE)
        val first = parsed.groups[0].grades.first { it.sequenceNo == 1 }
        assertEquals("052031", first.courseCode)
        assertEquals("大学英语 Ⅱ", first.courseName)
        // &nbsp; 的列应归一为 null
        assertNull(first.algorithmName)
        assertEquals("0", first.regularScore)
        assertEquals("16.8", first.midtermScore)
        assertNull(first.practiceScore)
        assertNull(first.finalExamScore)
        assertEquals("0", first.totalScore)
        assertNull(first.remark)
        assertNull(first.examStatus)
    }

    @Test
    fun `falls back gracefully when blockquote missing`() {
        val parsed = TestGradePage.parse("<html><body><div class=\"text-large\">x</div></body></html>")
        assertEquals("x", parsed.pageTitle)
        assertNull(parsed.studentId)
        assertNull(parsed.studentName)
        assertNull(parsed.semesterDate)
        assertTrue(parsed.groups.isEmpty())
    }

    @Test
    fun `keeps stable id from sequence and courseCode`() {
        val parsed = TestGradePage.parse(FIXTURE)
        val ids = parsed.groups[0].grades.map { it.id }
        assertEquals(listOf("1-052031", "2-255505", "3-262246", "4-262539"), ids)
    }

    private companion object {
        // 缩减自 samples/test_grades.html：保留页标题 + blockquote + 主表（4 行）+ 副表（没有记录）
        val FIXTURE = """
            <html><body>
            <div class="text-center text-large" style="line-height:50px;">
                25-26第2学期期末成绩查询
            </div>
            <blockquote class="border-red">
                <p class="text-big">学号：<u>2024050001</u> 姓名：<u>张三</u> 考试学期：<u>2026-03-01</u></p><hr>
                <p class="text-sub">注：因部分老师未提交成绩，缓考、免听、加分等处理未能到位，此成绩仅供参加，正式成绩以开学后下发为准。</p>
            </blockquote>
            <fieldset class="border-red"><legend class="button bg-red">主专业课程成绩</legend>
                <table id="_ctl6_gvZZY">
                    <tbody><tr>
                        <th>序号</th><th>课程号</th><th>课程名称标识</th><th>算法名称</th><th>平时成绩</th><th>期中成绩</th><th>实践成绩</th><th>卷面成绩</th><th>总评成绩</th><th>备注</th><th>考试情况</th>
                    </tr><tr>
                        <td>1</td><td>052031    </td><td>大学英语 Ⅱ</td><td>&nbsp;</td><td>0</td><td>16.8</td><td>&nbsp;</td><td>&nbsp;</td><td>0</td><td>&nbsp;</td><td>&nbsp;</td>
                    </tr><tr>
                        <td>2</td><td>255505    </td><td>高等数学（工学类）Ⅱ</td><td>&nbsp;</td><td>0</td><td>16.2</td><td>0</td><td>0</td><td>0</td><td>&nbsp;</td><td>&nbsp;</td>
                    </tr><tr>
                        <td>3</td><td>262246    </td><td>数据结构（理论）</td><td>&nbsp;</td><td>0</td><td>8.8</td><td>0</td><td>0</td><td>0</td><td>&nbsp;</td><td>&nbsp;</td>
                    </tr><tr>
                        <td>4</td><td>262539    </td><td>Python程序设计（师范）</td><td>&nbsp;</td><td>0</td><td>15</td><td>0</td><td>0</td><td>0</td><td>&nbsp;</td><td>&nbsp;</td>
                    </tr></tbody>
                </table>
            </fieldset>
            <fieldset class="border-red"><legend class="button bg-red">双专业课程成绩</legend>
                <table id="_ctl6_gvSZY">
                    <tbody><tr><td colspan="10">没有记录</td></tr></tbody>
                </table>
            </fieldset>
            </body></html>
        """.trimIndent()
    }
}
