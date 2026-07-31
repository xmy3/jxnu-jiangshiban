package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户首页 (User/Default.aspx) 解析回归测试。
 *
 * 只测纯解析逻辑 [UserDefaultPage.parse]（internal 但同模块测试可见）。
 *
 * 关键覆盖：
 *   - `<span id="lblUserInfor">` 内 `欢迎您，(学号,Student) 姓名` 文本抽取
 *   - 学号前 4 位推断入学年级
 *   - 学院/专业/班级在首页拿不到 → 留空
 *   - 头像 URL 应携带 base64 编码的学号（jwc 教务头像约定）
 *   - lblUserInfor 缺失时 name 兜底为 "同学"
 */
class UserDefaultPageTest {

    @Test
    fun `extracts name from lblUserInfor welcome text`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_NORMAL)
        assertEquals("张三", profile.name)
        assertEquals("20250101", profile.studentId)
        assertEquals(2025, profile.grade)
        assertEquals("", profile.college)
        assertEquals("", profile.major)
        assertEquals("", profile.className)
    }

    @Test
    fun `tolerates extra whitespace and fullwidth nbsp`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_WITH_NBSP)
        assertEquals("李四", profile.name)
    }

    @Test
    fun `falls back to default name when lblUserInfor missing`() {
        val profile = UserDefaultPage.parse("20240101", "<html><body><p>nothing</p></body></html>")
        assertEquals("同学", profile.name)
        assertEquals(2024, profile.grade)
    }

    @Test
    fun `falls back to default name when html empty`() {
        val profile = UserDefaultPage.parse("20240101", "")
        assertEquals("同学", profile.name)
    }

    @Test
    fun `grade is zero when studentId not numeric prefix`() {
        val profile = UserDefaultPage.parse("BADID", FIXTURE_NORMAL)
        assertEquals(0, profile.grade)
    }

    @Test
    fun `accepts fullwidth parentheses and comma`() {
        // 教务网换主题时偶尔渲染全角括号 / 全角逗号（WELCOME 注释声称兼容，这里锁死）
        val html = """
            <html><body>
            <span id="lblUserInfor">欢迎您，（2024050001，Student） 张三</span>
            </body></html>
        """.trimIndent()
        val profile = UserDefaultPage.parse("", html)
        assertEquals("2024050001", profile.studentId)
        assertEquals("张三", profile.name)
    }

    @Test
    fun `extractStudentId returns id when logged in and null on anonymous shell`() {
        // SSO 续票用它判「是否真的已登录」：匿名壳页解不出学号必须返回 null，
        // 否则半死会话会被当成续票成功放行
        assertEquals("20250101", UserDefaultPage.extractStudentId(FIXTURE_NORMAL))
        assertNull(UserDefaultPage.extractStudentId("<html><body><p>门户壳页，无欢迎横幅</p></body></html>"))
        assertNull(UserDefaultPage.extractStudentId(""))
    }

    @Test
    fun `extractStudentId returns null on jwc error stubs`() {
        // probeSession 的语义判活（2026-07 起）依赖本函数：jwc 对无效会话曾实测直出这两种
        // 纯文本 stub，解出 null 才能把假 Valid 拦成 Invalid → 触发重登阶梯而非假登录进主界面。
        assertNull(UserDefaultPage.extractStudentId("您提交的内容中含有非法字符,已经被拒绝4.Error参数错误"))
        assertNull(UserDefaultPage.extractStudentId("【访问受限：请登录后访问！】【参数错误】"))
    }

    @Test
    fun `extractStudentId tolerates non-Chinese names via degraded id-only match`() {
        // 主 WELCOME 正则要求姓名段 2-10 个中文字符；留学生等非中文姓名账号会整体 miss——
        // 判活（probeSession / SSO 续票复验）只需要学号，降级匹配必须把它救回来，
        // 否则这批用户的有效会话被恒判 Invalid、每次冷启动白跑全量账密登录。
        val html = """
            <html><body>
            <span id="lblUserInfor">   欢迎您，(2024050001,Student) Smith</span>
            </body></html>
        """.trimIndent()
        assertEquals("2024050001", UserDefaultPage.extractStudentId(html))
        // 主正则命中时降级路径不改变结果
        assertEquals("20250101", UserDefaultPage.extractStudentId(FIXTURE_NORMAL))
    }

    @Test
    fun `generates a photo url that points at jwc PhotoShow endpoint`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_NORMAL)
        assertTrue("应当生成头像 URL", !profile.avatarUrl.isNullOrBlank())
        assertTrue(
            "avatarUrl 应指向 All_PhotoShow 端点: ${profile.avatarUrl}",
            profile.avatarUrl!!.contains("All_PhotoShow.aspx"),
        )
        assertTrue(
            "avatarUrl 应携带 base64 编码后的 UserNum: ${profile.avatarUrl}",
            profile.avatarUrl!!.contains("UserNum=MjAyNTAxMDE%3D"),
        )
    }

    private companion object {
        val FIXTURE_NORMAL = """
            <html><body>
            <span id="lblUserInfor">   欢迎您，(20250101,Student) 张三</span>
            </body></html>
        """.trimIndent()

        // 教务真实页里 `欢迎您，` 后常有多余的全角/半角空格
        val FIXTURE_WITH_NBSP = """
            <html><body>
            <span id="lblUserInfor">&nbsp;&nbsp;欢迎您， ( 20250101 , Student )  李四</span>
            </body></html>
        """.trimIndent()
    }
}
