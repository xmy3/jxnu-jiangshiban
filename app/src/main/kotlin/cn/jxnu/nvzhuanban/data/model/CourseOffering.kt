package cn.jxnu.nvzhuanban.data.model

/**
 * 开课安排查询（`MyControl/Public_Kkap.aspx`）的数据模型。
 *
 * 结果表的列由教务网服务端渲染决定：查询 POST 需要登录会话（见
 * [cn.jxnu.nvzhuanban.data.network.JxnuUrls.PAGE_COURSE_OFFERING]），本地开发期拿不到
 * 登录态样张，所以 [CourseOfferingTable] 采用「表头驱动」的通用表格模型而不是硬编码列名——
 * 无论服务端给几列、列序如何，都能解析并渲染。
 */

/**
 * 查询表单里一个下拉选项。
 *
 * [value] 必须**原样**回传给教务网：学院代码是 8 位定宽、带尾随空格（如 `"51000   "`），
 * trim 掉会查不到；「不限」的 value 就是字面量 `不限`。
 */
data class FormOption(
    val label: String,
    val value: String,
)

/**
 * 解析学期下拉 value（`2026/9/1 0:00:00`）里的开学日。
 *
 * 与 [cn.jxnu.nvzhuanban.data.network.pages.SchedulePage.SemesterOption.startDate] 同一套宽容规则：
 * 日期分隔符 `/` 与 `\` 都兼容（课表页 ddlSterm 用 `\`、开课查询页用 `/`，指同一学期），
 * 锁 [java.util.Locale.ROOT] 防非拉丁数字 locale 解析失败。格式不符返回 null。
 *
 * 用途：课表「点教师 / 点教室」跳开课查询时按**开学日**对齐两边的学期选项——
 * 直接比 value 字符串会因分隔符差异永远失配。
 */
fun semesterStartDateOf(value: String): java.time.LocalDate? {
    val normalized = value.substringBefore(' ').replace('\\', '/')
    return runCatching {
        java.time.LocalDate.parse(
            normalized,
            java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d", java.util.Locale.ROOT),
        )
    }.getOrNull()
}

/** 查询表单页解析结果：四个下拉的选项 + ASP.NET 回传三件套。 */
data class CourseOfferingForm(
    /** 学期（`ddlSterm`），value 形如 `2026/9/1 0:00:00`。 */
    val semesters: List<FormOption>,
    /** 课程管理单位 / 学院（`ddlCollege`），首项「不限」。 */
    val colleges: List<FormOption>,
    /** 星期（`ddlWeek`），首项「不限」。 */
    val weeks: List<FormOption>,
    /** 节次（`ddlJC`），首项「不限」。 */
    val sections: List<FormOption>,
    val viewState: String,
    val viewStateGenerator: String,
    val eventValidation: String,
)

/** 一次查询的输入。下拉四项传选中 option 的 value；文本三项可留空（教务网按模糊匹配处理）。 */
data class CourseOfferingQuery(
    val semesterValue: String,
    val collegeValue: String,
    val weekValue: String,
    val sectionValue: String,
    val classroom: String = "",
    val courseName: String = "",
    val teacherName: String = "",
)

/**
 * 查询结果：表头驱动的通用表格。
 *
 * [columns] 为空表示结果表没有可识别的表头（或无结果）；[rows] 每行的单元格数
 * 已对齐到 [columns]（不足补空串，超出截断）。[message] 是结果区的单格提示行
 * （如 GridView 的 EmptyDataText），与数据行互斥出现。
 */
data class CourseOfferingTable(
    val columns: List<String>,
    val rows: List<List<String>>,
    val message: String? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
