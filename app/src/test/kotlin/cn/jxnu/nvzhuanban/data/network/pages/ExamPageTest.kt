package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 考试安排页解析回归测试。
 *
 * 关键覆盖：
 *   - `_ctl1_dgContent` 7 列结构（课程号|名称|学号|时间|教室|座位|备注）
 *   - 表头跳过、yyyy-MM-dd HH:mm:ss 时间解析、无效时间整行丢弃
 *   - seat / remark 空 → null
 */
class ExamPageTest {

    @Test
    fun `parses well-formed exam rows`() {
        val exams = ExamPage.parse(FIXTURE)
        assertEquals(2, exams.size)

        val math = exams.first { it.courseCode == "MA101" }
        assertEquals("高等数学", math.courseName)
        assertEquals(LocalDateTime.of(2026, 6, 20, 8, 30, 0), math.startTime)
        assertEquals("文科楼A302", math.location)
        assertEquals("12 排 8 号", math.seat)
        assertNull("空备注 → null", math.remark)
    }

    @Test
    fun `nullifies blank seat and keeps non-blank remark`() {
        val exams = ExamPage.parse(FIXTURE)
        val english = exams.first { it.courseCode == "EN101" }
        assertNull("空座位 → null", english.seat)
        assertEquals("机考", english.remark)
    }

    @Test
    fun `skips header row and rows with unparseable time`() {
        val exams = ExamPage.parse(FIXTURE_WITH_BAD_ROW)
        // 表头 + 坏时间行都应丢弃；只剩 1 行有效
        assertEquals(1, exams.size)
        assertTrue(exams.all { it.courseCode != "课程号" })
    }

    @Test
    fun `ignores rows with wrong column count`() {
        // 6 列、8 列都不该被当作数据行
        val exams = ExamPage.parse(FIXTURE_WRONG_COLS)
        assertEquals(0, exams.size)
    }

    private companion object {
        val FIXTURE = """
            <html><body>
            <table id="_ctl1_dgContent">
              <tr><td>课程号</td><td>课程名称</td><td>学号</td><td>考试时间</td><td>教室号</td><td>座位号</td><td>备注</td></tr>
              <tr>
                <td>MA101</td><td>高等数学</td><td>20240101</td>
                <td>2026-06-20 08:30:00</td><td>文科楼A302</td><td>12 排 8 号</td><td></td>
              </tr>
              <tr>
                <td>EN101</td><td>大学英语</td><td>20240101</td>
                <td>2026-06-22 14:00:00</td><td>文科楼B210</td><td></td><td>机考</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val FIXTURE_WITH_BAD_ROW = """
            <html><body>
            <table id="_ctl1_dgContent">
              <tr><td>课程号</td><td>课程名称</td><td>学号</td><td>考试时间</td><td>教室号</td><td>座位号</td><td>备注</td></tr>
              <tr><td>X1</td><td>X1</td><td>20240101</td><td>not-a-date</td><td>X</td><td>X</td><td></td></tr>
              <tr><td>MA101</td><td>高等数学</td><td>20240101</td><td>2026-06-20 08:30:00</td><td>A302</td><td>1</td><td></td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val FIXTURE_WRONG_COLS = """
            <html><body>
            <table id="_ctl1_dgContent">
              <tr><td>1</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td></tr>
              <tr><td>1</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td><td>7</td><td>8</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
