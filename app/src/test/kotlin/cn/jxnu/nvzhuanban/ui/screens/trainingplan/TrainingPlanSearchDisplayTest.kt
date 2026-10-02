package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanSearchDisplayTest {
    private fun course(semester: String?) = PlanCourseRow("课程", "2", semester, false, null)

    @Test fun `semester options are unique naturally ordered and keep unknown semesters accessible`() {
        val groups = listOf(PlanCourseGroup("必修", listOf(
            course("第10学期"), course("第2学期"), course("第1学期"), course("第2学期"), course(null), course(" "),
        )))
        assertEquals(listOf("第1学期", "第2学期", "第10学期", "未注明学期"), groups.semesterOptions())
        assertEquals(2, groups.forSemester("未注明学期").single().courses.size)
    }

    @Test fun `semester filtering preserves module positions and all restores every course`() {
        val groups = listOf(
            PlanCourseGroup("公共必修", listOf(course("第1学期"), course("第2学期"))),
            PlanCourseGroup("专业主干", listOf(course("第2学期"))),
        )
        val filtered = groups.forSemester("第1学期")
        assertEquals(listOf("公共必修", "专业主干"), filtered.map { it.title })
        assertEquals(1, filtered.sumOf { it.courses.size })
        assertTrue(filtered[1].courses.isEmpty())
        assertEquals(groups, groups.forSemester(null))
        assertEquals(3, groups.sumOf { it.courses.size })
    }

    @Test fun `keeps useful course fields from official eleven column table`() {
        val columns = listOf("课程性质", "课程号", "课程名称标识", "学位课程", "先修课程说明",
            "学分", "周课堂学时", "周实验学时", "课堂总学时", "实践总学时", "开课时间")
        val tables = listOf(TrainingPlanSearchPage.Table(columns, listOf(
            listOf("专业主干", "262246", "数据结构（理论）", "是", "高级语言程序设计", "4",
                "4", "0", "64", "0", "第2学期"),
            listOf("专业主干", "262256", "数据结构（实验）", "", "无", "1",
                "0", "2", "0", "32", "第2学期"),
        )))

        val group = tables.toPlanCourseGroups().single()
        assertEquals("专业主干", group.title)
        assertEquals(2, group.courses.size)
        assertEquals("数据结构（理论）", group.courses[0].name)
        assertEquals("4", group.courses[0].credit)
        assertEquals("第2学期", group.courses[0].semester)
        assertTrue(group.courses[0].isDegreeCourse)
        assertEquals("高级语言程序设计", group.courses[0].prerequisite)
        assertFalse(group.courses[1].isDegreeCourse)
        assertNull(group.courses[1].prerequisite)
    }
}
