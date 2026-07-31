package cn.jxnu.nvzhuanban.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64

/**
 * JXNU 教务系统所有 URL 集中管理。
 *
 * 认证走 CAS（uis.jxnu.edu.cn），业务页走 jwc.jxnu.edu.cn。
 * URL 中的 `\` 反斜杠是 ASP.NET UserControl 路径分隔符，**不要** URL-encode 成 `%5C`，
 * 否则服务器会按字面量匹配失败返回 404。
 */
object JxnuUrls {
    const val CAS_HOST = "uis.jxnu.edu.cn"
    const val JWC_HOST = "jwc.jxnu.edu.cn"
    /** 公共根域：用于一致地把 *.jxnu.edu.cn 的请求/重定向升级到 https。 */
    const val ROOT_DOMAIN = "jxnu.edu.cn"

    const val CAS_BASE = "https://$CAS_HOST/cas"
    const val JWC_BASE = "https://$JWC_HOST"

    // 认证相关
    const val CAS_PUBLIC_KEY = "$CAS_BASE/jwt/publicKey"
    const val CAS_LOGIN = "$CAS_BASE/login"
    const val SSO_CALLBACK = "$JWC_BASE/sso/login.aspx"
    const val PORTAL_INDEX = "$JWC_BASE/Portal/Index.aspx"
    const val PORTAL_LOGIN_ACCOUNT = "$JWC_BASE/Portal/LoginAccount.aspx?t=sso"
    const val USER_DEFAULT = "$JWC_BASE/User/Default.aspx"

    // 业务页（注意反斜杠保留原样）
    const val PAGE_SCHEDULE = "$JWC_BASE/User/default.aspx?&code=111&&uctl=MyControl\\xfz_kcb.ascx&MyAction=Personal"
    const val PAGE_EXAM = "$JWC_BASE/User/default.aspx?&code=129&&uctl=MyControl\\xfz_test_schedule.ascx"
    const val PAGE_GRADE = "$JWC_BASE/MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx&Action=Personal"
    const val PAGE_GRADUATION_AUDIT = "$JWC_BASE/MyControl/All_Display.aspx?UserControl=xfz_bysh.ascx&Action=Personal"

    /** 本人学籍/身份信息校对表（我的 → 基本信息）。含考生号/身份证等强 PII，需登录会话。 */
    const val PAGE_STUDENT_INFO = "$JWC_BASE/MyControl/Student_InforCheck.aspx"

    /**
     * 教工检索页（公共服务 → 教工信息）。
     * 表单字段：`_ctl1:txtKeyWord` + `_ctl1:ddlType`（姓名/教号）+ `_ctl1:ddlSQLType`（精确/模糊）+ `_ctl1:btnSearch`，
     * 需要带 ASP.NET 三件套（__VIEWSTATE / __VIEWSTATEGENERATOR / __EVENTVALIDATION）。
     */
    const val PAGE_TEACHER_SEARCH = "$JWC_BASE/User/default.aspx?&code=120&&uctl=MyControl\\all_teacher.ascx"

    /** 教工基本信息页。`userNum` 是教号的 base64（搜索结果直接给出，不要再次编码）。 */
    fun teacherDetailUrl(userNum: String): String =
        userDisplayUrl("All_TeacherInfor.ascx", "Teacher", userNum)

    /** 教工课表页。`userNum` 是教号的 base64。 */
    fun teacherScheduleUrl(userNum: String): String =
        userDisplayUrl("Xfz_Kcb.ascx", "Teacher", userNum)

    /** 教工头像，`userNum` 是教号的 base64（与 [teacherDetailUrl] 同一参数）。 */
    fun teacherPhotoUrl(userNum: String): String =
        photoUrl("Teacher", userNum)

    /**
     * 学生检索页（公共服务 → 学生信息）。结构和 [PAGE_TEACHER_SEARCH] 几乎一致：
     * `_ctl1:txtKeyWord` + `_ctl1:ddlType`（姓名 / 学号）+ `_ctl1:ddlSQLType` + `_ctl1:btnSearch` +
     * ASP.NET 三件套；结果表 `_ctl1_dgContent` 多出一列「班级名称」。
     */
    const val PAGE_STUDENT_SEARCH = "$JWC_BASE/User/default.aspx?&code=119&&uctl=MyControl\\all_searchstudent.ascx"

    /** 学生基本信息页。`userNum` 是学号的 base64（搜索结果直接给出）。 */
    fun studentDetailUrl(userNum: String): String =
        userDisplayUrl("All_StudentInfor.ascx", "Student", userNum)

    /** 学生课表页。与教师课表共用 [Xfz_Kcb.ascx] UserControl，只是 UserType 不同。 */
    fun studentScheduleUrl(userNum: String): String =
        userDisplayUrl("Xfz_Kcb.ascx", "Student", userNum)

    /** 学生头像，`userNum` 是学号的 base64。 */
    fun studentPhotoUrl(userNum: String): String =
        photoUrl("Student", userNum)

    /**
     * 期中 / 期末考试出分速查页。
     *
     * 这是教师录入成绩后、正式成绩单（[PAGE_GRADE]）公布之前的预览页：包含平时 / 期中 / 实践 / 卷面 / 总评 等单项分。
     * 页面顶部明确写着"此成绩仅供参考，正式成绩以开学后下发为准"。
     */
    const val PAGE_TEST_GRADE = "$JWC_BASE/MyControl/All_Display.aspx?UserControl=xfz_Test_cj.ascx"

    /**
     * 补缓考考试安排页。13 列表格 `_ctl6_dgContent`；教务网把真正的考试时间/地点塞在
     * "考试方式" 列的自然语言里，前 9 列是学生 + 课程元信息。
     */
    const val PAGE_MAKEUP_EXAM = "$JWC_BASE/MyControl/All_Display.aspx?UserControl=xfz_Test_BHK.ascx"

    /**
     * 教学周历（俗称校历）索引页。公开页，无需登录。
     *
     * GBK 编码：HTTP Content-Type 头没带 charset，只在 `<meta http-equiv>` 里声明，OkHttp 默认按
     * UTF-8 解码 `response.body.string()` 会乱码。读这页要走 [JwcClient.getBytes] 后手工 `String(bytes, GBK)`。
     */
    const val PAGE_CALENDAR_INDEX = "$JWC_BASE/Jxzl_Index.htm"

    /**
     * 开课安排查询页（Public_Kkap）。按 学期 / 学院 / 星期 / 节次 / 教室号 / 课程名 / 教师姓名
     * 组合检索全校开课信息，结果是 GridView（`table#gvContent`）。
     *
     * 表单页 GET **无需登录**就能拿到（学期/学院下拉都在），但**查询 POST 必须带登录会话**：
     * 匿名回传合法三件套也只会得到 51 字节纯文本「Error:系统错误，请与系统管理员联系！」
     * （2026-07 实测，UA/Referer/字段组合均无关）。所以两步都走 *Auth 变体。
     * 学院下拉的 value 是 8 位定宽代码（如 `51000   `，尾随空格必须原样回传）；
     * 学期 value 格式 `yyyy/M/1 0:00:00`，与课表 ddlSterm 一致。
     */
    const val PAGE_COURSE_OFFERING = "$JWC_BASE/MyControl/Public_Kkap.aspx"

    /** 用户头像，UserNum 是学号的 base64。 */
    fun userPhotoUrl(studentId: String): String {
        val encoded = Base64.getEncoder().encodeToString(studentId.toByteArray(Charsets.UTF_8))
        return photoUrl("Student", encoded)
    }

    private fun userDisplayUrl(userControl: String, userType: String, userNum: String): String =
        "$JWC_BASE/MyControl/All_Display.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("UserControl", userControl)
            .addQueryParameter("UserType", userType)
            .addQueryParameter("UserNum", userNum)
            .build()
            .toString()

    private fun photoUrl(userType: String, userNum: String): String =
        "$JWC_BASE/MyControl/All_PhotoShow.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("UserType", userType)
            .addQueryParameter("UserNum", userNum)
            .build()
            .toString()

    /**
     * 构造 CAS 登录的完整 URL，service 参数已正确编码。
     *
     * 等价于浏览器抓包里看到的：
     *   https://uis.jxnu.edu.cn/cas/login?service=https%3A%2F%2Fjwc.jxnu.edu.cn%2Fsso%2Flogin.aspx%3FtargetUrl%3D%7Bbase64%7DaHR0cHM6...
     */
    fun casLoginEntry(): String {
        val targetB64 = Base64.getEncoder().encodeToString(PORTAL_INDEX.toByteArray(Charsets.UTF_8))
        val ssoTarget = "$SSO_CALLBACK?targetUrl={base64}$targetB64"
        return CAS_LOGIN.toHttpUrl().newBuilder()
            .addQueryParameter("service", ssoTarget)
            .build()
            .toString()
    }
}
