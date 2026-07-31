package cn.jxnu.nvzhuanban.data.model

/**
 * 一条教工检索结果。字段顺序与教务网表头保持一致（所在单位 / 姓名 / 教号 / 性别）。
 *
 * [userNum] 是教号的 base64（教务网生成），用来拼基本信息和教工课表的二级页 URL，
 * 解析层不解码，调用方原样传给 [cn.jxnu.nvzhuanban.data.network.JxnuUrls.teacherDetailUrl] 等。
 */
data class Teacher(
    val name: String,
    val teacherId: String,
    val department: String,
    val gender: String,
    val userNum: String,
)

/** 搜索字段：对应表单 `_ctl1:ddlType` 的两个 option。 */
enum class TeacherSearchField(val formValue: String) {
    NAME("姓名"),
    ID("教号"),
}

/** 匹配方式：对应表单 `_ctl1:ddlSQLType`。注意 option value 在样本里就是「精确 / 模糊」（前后有空白，提交时需保持字面值）。 */
enum class TeacherMatchMode(val formValue: String) {
    EXACT("精确"),
    FUZZY("模糊"),
}

data class TeacherSearchQuery(
    val keyword: String,
    val field: TeacherSearchField,
    val mode: TeacherMatchMode,
)

/**
 * 教工基本信息页（All_TeacherInfor.ascx）的解析结果。
 * 学校该页只暴露姓名/性别/Email/职称/教学简介五个字段，外加头像；其它信息（单位、教号）
 * 由检索列表带过来，调用方需要展示完整资料时把 [Teacher] 一并传进来。
 */
data class TeacherInfo(
    val name: String,
    val gender: String,
    val email: String,
    val title: String,
    val intro: String,
)
