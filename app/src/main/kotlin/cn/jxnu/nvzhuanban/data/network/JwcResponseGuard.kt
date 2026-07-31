package cn.jxnu.nvzhuanban.data.network

import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Centralizes the sanity checks for HTML fetched from the JWC site.
 *
 * OkHttp follows redirects, so an expired session often arrives here as a
 * successful CAS login page rather than as an HTTP error. Treating that page as
 * business HTML causes confusing empty screens; this guard converts it into a
 * clear re-login failure.
 *
 * 重要：本 guard **不再**直接调 [SessionEvents.notifyExpired]。原因是 reauth-retry
 * 流程（见 [JwcClient.runWithSessionRecovery]）希望在尝试静默重登后再决定要不要
 * 把用户踢回登录页。如果这里就广播 expired，会和 retry 成功后的 `_state = LoggedIn`
 * 发生竞态闪烁。会话过期信号统一由上层 [JwcClient] 在 retry 也失败时再发。
 */
object JwcResponseGuard {
    fun readJwcHtml(response: Response, emptyMessage: String): String {
        validateJwcResponse(response)
        val body = response.body.string()
        if (body.isBlank()) throw JwcException(JwcError.EmptyResponse, emptyMessage)
        // 落点 host 在 jwc 但内容是登录表单（200 渲染 / Vue SPA 客户端跳转 / Portal 登录页）
        // 或「访问受限」短文本 stub —— 嗅探
        if (looksLikeLoginPage(body) || looksLikeAccessDeniedStub(body)) {
            throw JwcException(JwcError.SessionExpired)
        }
        return body
    }

    fun validateJwcResponse(response: Response) {
        val finalUrl = response.request.url
        val finalHost = finalUrl.host
        val finalPath = finalUrl.encodedPath

        // CAS 显式踢回登录页
        if (finalHost == JxnuUrls.CAS_HOST && finalPath.startsWith("/cas/login")) {
            throw JwcException(JwcError.SessionExpired)
        }
        // jwc 内部的 SSO 重登页：会话失效但还没走到 CAS 这条路时落点
        if (finalHost == JxnuUrls.JWC_HOST && finalPath.startsWith("/Portal/LoginAccount.aspx")) {
            throw JwcException(JwcError.SessionExpired)
        }
        // jwc 新版「未登录跳转」（2026-07 实测）：无效会话不再跳 CAS / LoginAccount，而是
        // 302 落到自家 default.aspx?preurl=<原路径>（200 + 短报错文本，无登录页特征）。
        // preurl 是「登录后回跳」参数，只在未登录被弹回时出现——已登录流量不会带它落到
        // default.aspx（app 也从不主动请求该 URL），可放心按会话失效处理。放在 URL 层而非
        // body 嗅探，是因为 getBytes（图片端点）只走本函数、不读 body。
        if (finalHost == JxnuUrls.JWC_HOST &&
            finalPath.equals("/default.aspx", ignoreCase = true) &&
            finalUrl.queryParameterNames.any { it.equals("preurl", ignoreCase = true) }
        ) {
            throw JwcException(JwcError.SessionExpired)
        }
        if (finalHost != JxnuUrls.JWC_HOST) {
            throw JwcException(JwcError.UnexpectedRedirect(finalHost))
        }
        if (!response.isSuccessful) {
            throw JwcException(JwcError.Server(response.code))
        }
    }

    /**
     * 给 [JwcClient.getBytes] 用：图片端点回了「看起来是 HTML **或极短**」的字节时，嗅一下是不是
     * 登录页 / 访问受限 stub。是 → 抛 SessionExpired（让 getBytesAuth 触发重登重放），否则静默放行
     * （可能只是 SVG / 内联 HTML / 小图标）。
     */
    fun assertNotLoginPage(bytes: ByteArray) {
        val head = bytes.take(MAX_PROBE_BYTES).toByteArray().toString(Charsets.UTF_8)
        if (looksLikeLoginPage(head) || looksLikeAccessDeniedStub(head)) {
            throw JwcException(JwcError.SessionExpired)
        }
    }

    /**
     * 结构化判断"这是不是一张登录页"。
     *
     * 之前用子串扫描（`"__RSA__" in body`、`"name=\"execution\"" in body`），假阳性：
     * 通知/校历的正文里只要恰好包含这些字面量（站长把一段 CAS 文档原文贴进通知 / 邮件模板等），
     * 业务页就被误判成会话过期 → 触发不该发生的静默重登，最差还会把 reauth 也用错密码 N 次。
     *
     * 现在改为先用 Jsoup 解析后看真正的表单结构：
     *  - 存在 `<input name="execution">` —— CAS 登录页的硬特征
     *  - 存在 `<form>` 里包含 `name=passwordEncrypt|password|username` 且 action 指向 cas/login
     *  - 退化到子串扫描时，要求**同时**命中 ≥2 个特征，单一关键词不再算数
     */
    private fun looksLikeLoginPage(body: String): Boolean {
        // 8KB 头部足够覆盖 CAS / Portal 登录页的 <head> + 表单。整页解析对大通知详情太贵。
        val head = if (body.length > MAX_PROBE_BYTES) body.substring(0, MAX_PROBE_BYTES) else body
        val structural = runCatching {
            val doc = Jsoup.parse(head)
            val hasExecution = doc.selectFirst("input[name=execution]") != null
            val loginForm = doc.selectFirst("form[action*=/cas/login], form#loginForm, form[action*=LoginAccount]")
            val hasPasswordField = loginForm?.selectFirst("input[name=passwordEncrypt], input[name=password]") != null
            hasExecution || hasPasswordField
        }.getOrDefault(false)
        if (structural) return true

        // 兜底子串扫描：只有同时命中 ≥2 个 fingerprint 才认。__RSA__ 单独出现不再算数。
        val hits = LOGIN_PAGE_FINGERPRINTS.count { it in head }
        return hits >= 2
    }

    private const val MAX_PROBE_BYTES = 8192

    /**
     * jwc 的「访问受限」短文本 stub（2026-07 实测）：会话无效时 MyControl/All_Display.aspx 系端点
     * **零跳 200 直出** 60 字节纯文本「【访问受限：请登录后访问！】【参数错误】」——落点 URL 不变、
     * 无任何 HTML 结构，preurl 判据够不着，只能靠 body 识别。判据刻意收紧到「极短 + 双关键词」：
     * 正常业务页（ASP.NET 光 __VIEWSTATE 就远超 [MAX_STUB_BYTES]）与提及这些词的通知正文
     * 都不可能命中，参照 [looksLikeLoginPage] 注释里子串误伤的历史教训。
     */
    private fun looksLikeAccessDeniedStub(body: String): Boolean {
        if (body.length > MAX_STUB_BYTES) return false
        return "访问受限" in body && "请登录" in body
    }

    /**
     * 「访问受限」stub 的长度上限。[JwcClient.getBytes] 也用它决定「短到值得嗅探」的字节数——
     * 真实图片一般远大于此，且二进制解码后不会命中中文关键词。
     */
    internal const val MAX_STUB_BYTES = 512

    /** 子串兜底用 fingerprint；要求命中 ≥2 个才算登录页，避免单一关键词巧合误伤。 */
    private val LOGIN_PAGE_FINGERPRINTS = listOf(
        "__RSA__",
        "name=\"passwordEncrypt\"",
        "id=\"loginForm\"",
        "name=\"execution\"",
        "/cas/login?service",
    )
}
