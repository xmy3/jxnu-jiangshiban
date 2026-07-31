package cn.jxnu.nvzhuanban.data.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JwcResponseGuardTest {

    @Test
    fun `reads html from successful jwc response`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, "<html>ok</html>")

        assertEquals("<html>ok</html>", JwcResponseGuard.readJwcHtml(response, "empty"))
    }

    @Test
    fun `throws session expired when final url is cas login`() {
        val response = response("https://uis.jxnu.edu.cn/cas/login?service=x", 200, "<form></form>")
        // 新契约：guard **不**再直接 notify SessionEvents，把这一职责留给 JwcClient.runWithSessionRecovery，
        // 这样 reauth 成功的场景就不会先广播 expired 再撤回，造成 UI 闪烁。
        val before = SessionEvents.expiredSignal.value

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
        assertEquals("登录已过期，请重新登录", thrown.toUserMessage())
        assertEquals(before, SessionEvents.expiredSignal.value)
    }

    @Test
    fun `throws session expired when final url is jwc portal login account`() {
        val response = response("https://jwc.jxnu.edu.cn/Portal/LoginAccount.aspx?t=sso", 200, "<html></html>")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `throws session expired when body sniffs as cas login form`() {
        // jwc host 自身回了一个登录表单 HTML（200 渲染 / SPA 客户端跳转 / Portal 钓鱼回流）。
        // 嗅探到 __RSA__ 关键字应当强制视为会话过期，避免业务解析器吃到空数据后展示空白页。
        val body = """
            <html><body>
              <form id="loginForm" action="/cas/login?service=...">
                <input type="hidden" name="passwordEncrypt" value="__RSA__abc">
              </form>
            </body></html>
        """.trimIndent()
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, body)

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `throws session expired when minimal cas login form with execution input only`() {
        // 现实的 CAS 登录页可能极简：一个 <input name="execution"> 就够。
        // 结构化判断必须独立成立，不依赖子串 fingerprint 计数。
        val body = """<html><form><input name="execution" value="e1s1"></form></html>"""
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, body)

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `does not flag announcement body that incidentally mentions __RSA__`() {
        // 回归保护 #9：旧的 any() 判断会把任何提到 __RSA__ 的通知正文当作登录页 → 触发不该
        // 发生的静默重登。新逻辑要求结构化命中或子串 ≥2 个，单纯出现 __RSA__ 不应误判。
        val body = """
            <html><body>
              <article>
                <h1>关于统一身份认证密码加密流程的说明</h1>
                <p>新版 CAS 在前端用 RSA 公钥加密密码，传输时密文前缀为 __RSA__ 字面量。</p>
              </article>
            </body></html>
        """.trimIndent()
        val response = response("https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=1", 200, body)

        // 不抛异常，正常返回 body
        val result = JwcResponseGuard.readJwcHtml(response, "empty")
        assertEquals(body, result)
    }

    @Test
    fun `throws session expired when redirected to jwc default aspx with preurl`() {
        // 2026-07 实测：jwc 对无效会话不再跳 CAS/LoginAccount，而是 302 两跳落在自家
        // default.aspx?preurl=<原路径>，200 + 74 字节纯文本报错。落点在 jwc 域、200、无登录页
        // 特征，旧守卫全放行 → SessionExpired 永远不触发 → 自动重登体系整体失效（曾致线上症状：
        // 长时间闲置后「课表不加载、个人信息丢失」且永不自愈）。preurl 是 jwc「登录后回跳」跳转的
        // 专用参数，已登录流量不会带它落到 default.aspx。
        val response = response(
            "https://jwc.jxnu.edu.cn/default.aspx?preurl=user/default.aspx",
            200,
            "您提交的内容中含有非法字符,已经被拒绝4.Error参数错误",
        )

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `validateJwcResponse also rejects preurl redirect for byte endpoints`() {
        // getBytes（图片）只走 validateJwcResponse，不读 body——preurl 落点判定必须在 URL 层完成。
        val response = response(
            "https://jwc.jxnu.edu.cn/Default.aspx?PreUrl=MyControl/All_PhotoShow.aspx",
            200,
            "junk",
        )

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.validateJwcResponse(response)
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `throws session expired on short access-denied stub body`() {
        // 2026-07 实测：MyControl/All_Display.aspx 系（成绩/培养方案/补缓考/出分）对无效会话
        // 零跳 200 直出 60 字节纯文本。落点 URL 不变，只能靠 body 嗅探。
        val response = response(
            "https://jwc.jxnu.edu.cn/MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx&Action=Personal",
            200,
            "【访问受限：请登录后访问！】【参数错误】",
        )

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `does not flag large business page that mentions access-denied words`() {
        // 通知正文完全可能出现「访问受限」「请登录」字样；嗅探仅对短小无结构的 stub 生效，
        // 正常业务页（ASP.NET 页面光 VIEWSTATE 就远超阈值）不受影响。
        val filler = "<p>教务处关于系统维护的说明。</p>".repeat(60)
        val body = "<html><body><article>" +
            "<p>维护期间部分页面访问受限：请登录后访问！由此带来的不便敬请谅解。</p>" +
            filler + "</article></body></html>"
        val response = response("https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=1", 200, body)

        assertEquals(body, JwcResponseGuard.readJwcHtml(response, "empty"))
    }

    @Test
    fun `assertNotLoginPage throws on access-denied stub bytes`() {
        // 半死会话下图片端点若回同款「访问受限」短文本，也要转成 SessionExpired 让 getBytesAuth 重登。
        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.assertNotLoginPage("【访问受限：请登录后访问！】【参数错误】".toByteArray())
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `rejects unexpected final host`() {
        val response = response("https://example.com/phishing", 200, "<html></html>")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.UnexpectedRedirect("example.com"), thrown.error)
    }

    @Test
    fun `rejects unsuccessful jwc response`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 500, "boom")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.Server(500), thrown.error)
    }

    @Test
    fun `rejects empty jwc response with structured error`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, "")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.EmptyResponse, thrown.error)
    }

    @Test
    fun `assertNotLoginPage throws on login-form bytes`() {
        // 图片端点回了「其实是登录页」的 HTML 字节 → 必须转成 SessionExpired 让 getBytesAuth 重登。
        val body = """<html><form id="loginForm" action="/cas/login">
            <input name="passwordEncrypt" value="__RSA__x"></form></html>"""
        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.assertNotLoginPage(body.toByteArray())
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `assertNotLoginPage passes through real image-ish bytes`() {
        // 普通（非登录页）字节不应被误判 —— 不抛即通过
        JwcResponseGuard.assertNotLoginPage("<svg><rect/></svg>".toByteArray())
    }

    private fun response(url: String, code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Server Error")
            .body(body.toResponseBody())
            .build()
}
