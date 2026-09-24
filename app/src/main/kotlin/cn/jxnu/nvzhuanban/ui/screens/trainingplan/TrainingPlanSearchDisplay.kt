package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import cn.jxnu.nvzhuanban.data.network.pages.TrainingPlanSearchPage

internal data class PlanCourseRow(
    val name: String,
    val credit: String?,
    val semester: String?,
    val isDegreeCourse: Boolean,
    val prerequisite: String?,
)

internal data class PlanCourseGroup(
    val title: String,
    val courses: List<PlanCourseRow>,
)

/** 只提取手机阅读培养方案需要的信息，不把教务系统的 11 列原样堆在课程卡上。 */
internal fun List<TrainingPlanSearchPage.Table>.toPlanCourseGroups(): List<PlanCourseGroup> =
    mapIndexedNotNull { index, table ->
        val nameIndex = table.columns.indexOfFirst { it.contains("课程名称") }
        if (nameIndex < 0) return@mapIndexedNotNull null
        val categoryIndex = table.columns.indexOfFirst { it.contains("课程性质") }
        val creditIndex = table.columns.indexOfFirst { it == "学分" }
        val semesterIndex = table.columns.indexOfFirst { it.contains("开课时间") }
        val degreeIndex = table.columns.indexOfFirst { it.contains("学位课程") }
        val prerequisiteIndex = table.columns.indexOfFirst { it.contains("先修课程") }
        fun List<String>.field(position: Int): String? =
            getOrNull(position)?.trim()?.takeIf { it.isNotEmpty() }

        val courses = table.rows.mapNotNull { row ->
            val name = row.field(nameIndex) ?: return@mapNotNull null
            val prerequisite = row.field(prerequisiteIndex)
                ?.takeUnless { it == "无" || it == "无要求" || it == "无先修课程" }
            PlanCourseRow(
                name = name,
                credit = row.field(creditIndex),
                semester = row.field(semesterIndex),
                isDegreeCourse = row.field(degreeIndex) in setOf("是", "√", "✓"),
                prerequisite = prerequisite,
            )
        }
        if (courses.isEmpty()) return@mapIndexedNotNull null
        PlanCourseGroup(
            title = table.rows.firstNotNullOfOrNull { it.field(categoryIndex) }
                ?: "课程模块 ${index + 1}",
            courses = courses,
        )
    }
