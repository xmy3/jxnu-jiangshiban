package cn.jxnu.nvzhuanban.data.model

/**
 * 一门课的「考试出分」单项分。
 *
 * 数据源是 `xfz_Test_cj.ascx`：教师录入分数后、正式成绩单还没下发时的预览。
 * 字段语义来自该页表头（平时 / 期中 / 实践 / 卷面 / 总评 / 备注 / 考试情况）。
 * 这些列在教务系统里允许为空（HTML 表现为 `&nbsp;`），解析时统一归一为 null，
 * UI 据此决定是否显示对应的 Chip / 行。
 */
data class TestGrade(
    val id: String,
    /** 教务系统的序号（页面第一列），仅用于稳定排序与 key。 */
    val sequenceNo: Int,
    val courseCode: String,
    val courseName: String,
    /** 算法名称（成绩合成方式），通常为空。 */
    val algorithmName: String? = null,
    val regularScore: String? = null,
    val midtermScore: String? = null,
    val practiceScore: String? = null,
    val finalExamScore: String? = null,
    val totalScore: String? = null,
    val remark: String? = null,
    /** 考试情况：缺考 / 缓考 / 免听 等，通常为空。 */
    val examStatus: String? = null,
)

/**
 * 考试出分页的一组成绩，对应页面里一个 `<fieldset>`（主专业课程 / 双专业课程）。
 */
data class TestGradeGroup(
    val title: String,
    val grades: List<TestGrade>,
)

/**
 * 考试出分页的完整解析结果。
 *
 * @param pageTitle 形如 `25-26第2学期期末成绩查询`，含学期与"期中/期末"区分。
 * @param semesterDate 顶部学生信息块的"考试学期"日期文本（如 `2026-03-01`），不一定是真考试日，仅用于展示。
 * @param disclaimer 页面顶部红框里的"仅供参考"注释，用作 UI 的提示条。
 * @param groups 课程分组：通常是「主专业课程成绩」和「双专业课程成绩」。无记录的组会被过滤掉。
 */
data class TestGradeReport(
    val pageTitle: String,
    val studentId: String? = null,
    val studentName: String? = null,
    val semesterDate: String? = null,
    val disclaimer: String? = null,
    val groups: List<TestGradeGroup>,
)
