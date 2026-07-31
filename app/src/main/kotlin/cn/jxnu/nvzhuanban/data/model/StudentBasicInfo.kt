package cn.jxnu.nvzhuanban.data.model

/**
 * 本人「学生信息校对表」数据（来自 `MyControl/Student_InforCheck.aspx`）。
 *
 * 这是**登录用户自己**的学籍/身份信息，区别于查他人的 [StudentInfo]（师生检索详情）。
 * 页面上的身份证号（lblSFZH）属强 PII，产品决定不解析、不落内存、不展示。
 */
data class StudentBasicInfo(
    val studentId: String,   // 学号
    val name: String,        // 姓名
    val className: String,   // 班级
    val examId: String,      // 考生号
    val gender: String,      // 性别
    val ethnicity: String,   // 民族
    val birthDate: String,   // 出生日期（已格式化为 YYYY-MM-DD，解析不出则原样）
)
