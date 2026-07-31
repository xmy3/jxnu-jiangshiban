package cn.jxnu.nvzhuanban.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CasLoginClient.parseExecutionFromHtml` 的解析回归测试。
 *
 * 这里测的是「拿到 CAS 登录页 HTML 后能否抽到 execution」这一步，不是网络 / cookie 行为。
 *
 * 历史问题：曾间歇性报 "登录页结构异常：缺少 execution 字段"。根因是 CAS TGC cookie 没清导致
 * 第一步 GET 落到非登录页，而非解析逻辑本身有 bug。CAS cookie 清理在 [CasLoginClient.login] 中完成，
 * 那段逻辑依赖真实 OkHttp，没法在 unit test 层覆盖；这里至少把解析路径锁死。
 */
class CasLoginClientParseTest {

    @Test
    fun `extracts execution from hidden input`() {
        val html = """
            <html><body>
            <form action="/cas/login">
              <input type="hidden" name="execution" value="e1s1">
              <input type="text" name="username">
            </form>
            </body></html>
        """.trimIndent()
        assertEquals("e1s1", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `extracts execution from inline JS object (single quotes)`() {
        // 江师大 Vue 单页常见形态：window.__INITIAL_STATE__ = { execution: 'e2s3', ... }
        val html = """
            <html><body>
            <script>window.__INITIAL_STATE__ = { execution: 'e2s3', flow: 'login' };</script>
            </body></html>
        """.trimIndent()
        assertEquals("e2s3", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `extracts execution from inline JS object (double quotes)`() {
        val html = """
            <html><body>
            <script>var s = {"execution":"e7s9","loginUrl":"/cas/login"};</script>
            </body></html>
        """.trimIndent()
        assertEquals("e7s9", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `prefers hidden input over JS when both present`() {
        // 旧版 CAS 同时双兼容时，input 是权威来源（JS 可能是 SSR 缓存）
        val html = """
            <html><body>
            <input type="hidden" name="execution" value="from-input">
            <script>var s = { execution: "from-js" };</script>
            </body></html>
        """.trimIndent()
        assertEquals("from-input", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `returns null when execution missing entirely`() {
        // 模拟 CAS 直接 302 跳过登录页后落到了 jwc 主页：HTML 里根本没有 execution。
        val html = """
            <html><body>
            <h1>江西师范大学教务在线</h1>
            <a href="/User/Default.aspx">个人中心</a>
            </body></html>
        """.trimIndent()
        assertNull(CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `returns null when execution input has empty value`() {
        // 空 value 不应被当成有效 token——继续尝试 JS 兜底
        val html = """
            <html><body>
            <input type="hidden" name="execution" value="">
            </body></html>
        """.trimIndent()
        assertNull(CasLoginClient.parseExecutionFromHtml(html))
    }

    // ---- classifyCasRejection：白名单降级（防改密码 fail-open / 防误清正确密码）----

    @Test
    fun `unrecognized rejection defaults to InvalidCredentials (clears creds)`() {
        // 江西师大 Vue 版 CAS 常常抓不到错误文案 → 必须默认认定凭证失效清密码，
        // 否则改过密码的用户会拿旧密码反复打 CAS 把账号锁死（fail-open 回归）。
        val body = "<html><body><div id=\"app\"></div></body></html>"
        val r = CasLoginClient.classifyCasRejection(body)
        assertTrue(r is CasLoginClient.Result.InvalidCredentials)
    }

    @Test
    fun `explicit wrong-password text is InvalidCredentials`() {
        val body = """<html><body><div class="alert-danger">用户名或密码错误</div></body></html>"""
        val r = CasLoginClient.classifyCasRejection(body)
        assertTrue(r is CasLoginClient.Result.InvalidCredentials)
        assertEquals("用户名或密码错误", (r as CasLoginClient.Result.InvalidCredentials).message)
    }

    @Test
    fun `captcha requirement is Transient (keeps creds)`() {
        // 需验证码是环境性拒绝（高频登录触发风控），不是密码错——保留凭证，靠 throttle 退避。
        val body = """<html><body><div class="error-msg">请输入验证码后重试</div></body></html>"""
        val r = CasLoginClient.classifyCasRejection(body)
        assertTrue(r is CasLoginClient.Result.Transient)
    }

    @Test
    fun `rate-limit busy text is Transient (keeps creds)`() {
        val body = """<html><body><p class="error">系统繁忙，请稍后再试</p></body></html>"""
        val r = CasLoginClient.classifyCasRejection(body)
        assertTrue(r is CasLoginClient.Result.Transient)
    }

    @Test
    fun `account locked text is InvalidCredentials`() {
        // 账号锁定/冻结不含环境性白名单词 → 默认 InvalidCredentials 清凭证（旧密码继续试只会更糟）。
        val body = """<html><body><div class="alert-danger">账号已被锁定</div></body></html>"""
        val r = CasLoginClient.classifyCasRejection(body)
        assertTrue(r is CasLoginClient.Result.InvalidCredentials)
    }
}
