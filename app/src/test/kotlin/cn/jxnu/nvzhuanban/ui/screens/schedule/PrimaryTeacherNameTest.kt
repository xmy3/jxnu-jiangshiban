package cn.jxnu.nvzhuanban.ui.screens.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [primaryTeacherName] 回归：课表教师字段 → 开课查询「教师姓名」模糊框的取名规则。
 *
 * 合班课 teacher 常是多名拼接，开课查询按单名模糊匹配，必须取首名；单名场景原样返回。
 */
class PrimaryTeacherNameTest {

    @Test
    fun `single name passes through unchanged`() {
        assertEquals("张三", primaryTeacherName("张三"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("张三", primaryTeacherName("  张三  "))
    }

    @Test
    fun `space separated multi-teacher takes first`() {
        assertEquals("张三", primaryTeacherName("张三 李四"))
    }

    @Test
    fun `full-width space separated takes first`() {
        assertEquals("张三", primaryTeacherName("张三　李四"))
    }

    @Test
    fun `comma variants take first`() {
        assertEquals("张三", primaryTeacherName("张三,李四"))
        assertEquals("张三", primaryTeacherName("张三，李四"))
        assertEquals("张三", primaryTeacherName("张三、李四"))
    }

    @Test
    fun `slash and semicolon separators take first`() {
        assertEquals("张三", primaryTeacherName("张三/李四"))
        assertEquals("张三", primaryTeacherName("张三;李四"))
        assertEquals("张三", primaryTeacherName("张三；李四"))
    }

    @Test
    fun `leading separators are skipped to first real name`() {
        assertEquals("张三", primaryTeacherName(" ,张三 李四"))
    }

    @Test
    fun `blank input yields empty`() {
        assertEquals("", primaryTeacherName(""))
        assertEquals("", primaryTeacherName("   "))
    }
}
