package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageGuard] 判据回归测试。
 *
 * 关键覆盖：
 *  - 四种 shape（Login / AccessDenied / Maintenance / Business）的判定
 *  - 空白文档 → Unknown（没有业务页骨架）
 *  - [PageGuard.requireBusinessPage] 的三种出口：Login → SessionExpired、无锚点 → Decode、有锚点 → 直接返回
 *  - 正文里恰好出现 `name="execution"` 字面量时**不能**误判成登录页（历史上 JwcResponseGuard 踩过这个坑）
 */
class PageGuardTest {

    @Test
    fun `detects cas login page by execution field`() {
        assertEquals(PageGuard.Shape.Login, PageGuard.detect(Jsoup.parse(CAS_LOGIN_PAGE)))
    }

    @Test
    fun `detects portal login account page by form action and password field`() {
        assertEquals(PageGuard.Shape.Login, PageGuard.detect(Jsoup.parse(PORTAL_LOGIN_PAGE)))
    }

    @Test
    fun `detects access denied stub`() {
        assertEquals(PageGuard.Shape.AccessDenied, PageGuard.detect(Jsoup.parse(ACCESS_DENIED_STUB)))
        assertEquals(
            PageGuard.Shape.AccessDenied,
            PageGuard.detect(Jsoup.parse("<html><body><div>没有此页面访问权限！</div></body></html>")),
        )
    }

    @Test
    fun `detects maintenance notice`() {
        assertEquals(PageGuard.Shape.Maintenance, PageGuard.detect(Jsoup.parse(MAINTENANCE_PAGE)))
        assertEquals(
            PageGuard.Shape.Maintenance,
            PageGuard.detect(Jsoup.parse("<html><body><p>访问过于频繁，请稍后再试</p></body></html>")),
        )
    }

    @Test
    fun `detects business page`() {
        assertEquals(PageGuard.Shape.Business, PageGuard.detect(Jsoup.parse(GRADE_PAGE)))
    }

    @Test
    fun `detects unknown shape for empty document`() {
        assertEquals(PageGuard.Shape.Unknown, PageGuard.detect(Jsoup.parse("<html><body></body></html>")))
    }

    @Test
    fun `literal execution text inside body is not a login page`() {
        // 站长把一段 CAS 文档原文贴进通知正文时，子串扫描会误判成登录页 → 静默重登。
        val html = """
            <html><body>
            <div id="main-content">
              <p>CAS 登录页的关键隐藏域是 input[name=execution]，排障时请确认它随表单一起提交。</p>
              <p>系统使用统一身份认证，无需单独注册。</p>
            </div>
            </body></html>
        """.trimIndent()
        assertEquals(PageGuard.Shape.Business, PageGuard.detect(Jsoup.parse(html)))
    }

    @Test
    fun `throws session expired when login page has no anchor`() {
        val thrown = assertThrows(JwcException::class.java) {
            PageGuard.requireBusinessPage(Jsoup.parse(CAS_LOGIN_PAGE), "成绩", anchorFound = false)
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `throws decode with shell page hint when unknown page has no anchor`() {
        val thrown = assertThrows(JwcException::class.java) {
            PageGuard.requireBusinessPage(Jsoup.parse("<html><body></body></html>"), "考试安排", anchorFound = false)
        }
        assertTrue("应为 Decode：${thrown.error}", thrown.error is JwcError.Decode)
        val detail = (thrown.error as JwcError.Decode).detail.orEmpty()
        assertTrue("detail 应含页面名: $detail", detail.contains("考试安排"))
        assertTrue("detail 应含 shape: $detail", detail.contains("Unknown"))
        assertTrue("用户文案应是中文引导: ${thrown.message}", thrown.message.contains("重新登录"))
    }

    @Test
    fun `does not throw when anchor is present`() {
        // 真实空数据页（0 门课 / 0 场考试）也有锚点，绝不能因为"没数据"被判成壳页
        PageGuard.requireBusinessPage(Jsoup.parse(GRADE_PAGE), "成绩", anchorFound = true)
        PageGuard.requireBusinessPage(Jsoup.parse(CAS_LOGIN_PAGE), "成绩", anchorFound = true)
    }

    private companion object {
        /** CAS 登录页：`input[name=execution]` + action 指向 /cas/login。 */
        val CAS_LOGIN_PAGE = """
            <html><body>
            <form id="fm1" method="post" action="/cas/login;jsessionid=abc?service=https%3A%2F%2Fjwc.jxnu.edu.cn%2Fdefault.aspx">
              <input id="username" name="username" type="text">
              <input id="password" name="password" type="password">
              <input type="hidden" name="execution" value="e1s1">
              <input type="hidden" name="lt" value="LT-1-abc">
            </form>
            </body></html>
        """.trimIndent()

        /** jwc 自家 SSO 重登页：action 指向 LoginAccount.aspx + 带 encrypt 密码框。 */
        val PORTAL_LOGIN_PAGE = """
            <html><body>
            <form name="form1" method="post" action="LoginAccount.aspx" id="form1">
              <input name="username" type="text">
              <input name="passwordEncrypt" type="password">
              <input type="submit" name="btnLogin" value="登录">
            </form>
            </body></html>
        """.trimIndent()

        /** 2026-07 实测的「访问受限」纯文本 stub（60 字节左右，零 HTML 结构）。 */
        const val ACCESS_DENIED_STUB = "【访问受限：请登录后访问！】【参数错误】"

        val MAINTENANCE_PAGE = """
            <html><body>
            <div class="text-center">教务系统维护中</div>
            <div>请于 22:00 后再试</div>
            </body></html>
        """.trimIndent()

        /** 正常成绩页骨架：个人信息 span + 成绩表。 */
        val GRADE_PAGE = """
            <html><body>
            <span id="_ctl6_lblMsg">学院：计算机信息工程学院　专业名称：计算机科学与技术　姓名：张三</span>
            <table id="_ctl6_dgContent">
              <tr><td>考试时间</td><td>课程号</td><td>课程名称</td><td>所得学分</td><td>课程成绩</td><td>补考成绩</td><td>标准分</td><td>备注</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }
}
