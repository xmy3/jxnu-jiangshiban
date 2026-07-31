package cn.jxnu.nvzhuanban.data.model

/**
 * 一条学生检索结果。
 *
 * [userNum] 是学号的 base64（教务网生成，含 `==` padding），用来拼基本信息/课表 URL，
 * 解析层不解码、原样保留——直接传给 [cn.jxnu.nvzhuanban.data.network.JxnuUrls.studentDetailUrl] 等。
 */
data class Student(
    val name: String,
    val studentId: String,
    val department: String,
    val className: String,
    val gender: String,
    val userNum: String,
)

/** 搜索字段：对应表单 `_ctl1:ddlType` 的两个 option。和 [TeacherSearchField] 的差别在「学号」与「教号」。 */
enum class StudentSearchField(val formValue: String) {
    NAME("姓名"),
    ID("学号"),
}

/** 匹配方式：对应表单 `_ctl1:ddlSQLType`。 */
enum class StudentMatchMode(val formValue: String) {
    EXACT("精确"),
    FUZZY("模糊"),
}

data class StudentSearchQuery(
    val keyword: String,
    val field: StudentSearchField,
    val mode: StudentMatchMode,
)

/**
 * 学生基本信息页（All_StudentInfor.ascx）的解析结果。
 * 该页直接给出 班级 / 学号 / 姓名 / 性别 + 头像；其它教务信息（如院系）由检索列表带来。
 */
data class StudentInfo(
    val name: String,
    val studentId: String,
    val className: String,
    val gender: String,
)
