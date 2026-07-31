package cn.jxnu.nvzhuanban.data.model

/**
 * 补缓考考试安排。
 *
 * 数据来源 `MyControl/All_Display.aspx?UserControl=xfz_Test_BHK.ascx`。
 *
 * 教务网把考试时间/地点的"教室号"、"考试时间"两列经常留空，真正的时间和地点是用自然语言
 * 写在 [examMethod]（页面列名"考试方式"）里，例如
 * `请于3月10日下午15:00在瑶湖校区C田径场参加考试，联系电话：88880000`。
 * [remark] 表示性质：「补考」或「缓考」。
 */
data class MakeupExam(
    val id: String,
    val campus: String,
    val college: String,
    val className: String,
    val studentName: String,
    val studentId: String,
    val courseCode: String,
    val courseName: String,
    val courseType: String,
    val managingDept: String,
    /** 教室号；教务网常留空，此时为 null。 */
    val location: String?,
    /** 考试时间字符串；教务网常留空，此时为 null。真实时间通常在 [examMethod] 里。 */
    val examTime: String?,
    /** "考试方式" 列；实际内容是「请于X月X日X时在X地参加考试」的整段说明文字。 */
    val examMethod: String?,
    /** 「补考」/「缓考」；为空时按"补考"展示。 */
    val remark: String?,
)
