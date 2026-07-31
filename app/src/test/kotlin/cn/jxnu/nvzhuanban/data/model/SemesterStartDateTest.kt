package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * [semesterStartDateOf] 回归：开课查询 / 课表两边学期 value 的开学日归一化。
 *
 * 课表 ddlSterm 用 `\` 分隔日期（`2026\3\1 0:00:00`）、开课查询用 `/`（`2026/3/1 0:00:00`），
 * 指同一学期——跳转对齐必须按解析后的日期比，不能比原串。
 */
class SemesterStartDateTest {

    @Test
    fun `parses kkap slash format`() {
        assertEquals(LocalDate.of(2026, 9, 1), semesterStartDateOf("2026/9/1 0:00:00"))
        assertEquals(LocalDate.of(2026, 3, 1), semesterStartDateOf("2026/3/1 0:00:00"))
    }

    @Test
    fun `parses schedule backslash format`() {
        assertEquals(LocalDate.of(2026, 3, 1), semesterStartDateOf("2026\\3\\1 0:00:00"))
    }

    @Test
    fun `same semester across separators normalizes equal`() {
        assertEquals(
            semesterStartDateOf("2026\\3\\1 0:00:00"),
            semesterStartDateOf("2026/3/1 0:00:00"),
        )
    }

    @Test
    fun `date-only value without time part parses`() {
        assertEquals(LocalDate.of(2025, 9, 1), semesterStartDateOf("2025/9/1"))
    }

    @Test
    fun `garbage and blank yield null`() {
        assertNull(semesterStartDateOf("不限"))
        assertNull(semesterStartDateOf(""))
        assertNull(semesterStartDateOf("abc/def/g 0:00:00"))
    }

    @Test
    fun `iso string of parsed date matches schedule semesterStart toString`() {
        // 课表端传的是 LocalDate.toString()（ISO yyyy-MM-dd）；两边按这个口径相等
        assertEquals("2026-03-01", semesterStartDateOf("2026/3/1 0:00:00")?.toString())
    }
}
