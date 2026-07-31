package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import cn.jxnu.nvzhuanban.data.network.pages.UserDefaultPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * CAS SSO 登录流程（江西师大教务系统专用）。
 *
 * 完整 5 步：
 *  1. GET `/cas/login?service=...` 拉登录页 HTML，解出 `execution` token
 *  2. GET `/cas/jwt/publicKey` 拉 RSA 公钥
 *  3. 用公钥加密密码 → `__RSA__<base64>`
 *  4. POST `/cas/login?service=...` 表单（含 username、加密密码、execution、fpVisitorId 等 13 个字段）
 *  5. OkHttp 自动跟随 302 → SSO 验票 → 落 `/User/Default.aspx`
 *
 * 判断登录是否成功：看最终重定向落点的 URL。
 *  - 落 `jwc.jxnu.edu.cn/User/...` 或 `/Portal/...` ⇒ 成功
 *  - 落 `uis.jxnu.edu.cn/cas/login` ⇒ 失败（账号密码错、需验证码、需 MFA 等），从 HTML 抓错误信息
 */
class CasLoginClient(
    context: Context,
    private val httpClient: JxnuHttpClient = JxnuHttpClient.get(),
    private val authStorage: AuthStorage = AuthStorage.get(),
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data object Success : Result

        /**
         * CAS **明确拒绝了这套凭证**：密码错、账号锁定/冻结/停用/不存在、需改密等。
         * 上层据此清掉本地保存的密码（继续用只会反复失败、甚至把账号打锁）。
         */
        data class InvalidCredentials(val message: String) : Result

        /**
         * **瞬时 / 未知失败**：网络异常、TLS 失败、jwc 5xx、登录页结构异常、落点未识别，
         * 以及「CAS 落回登录页但看起来是验证码 / 风控要求」——这些都**不能**清凭证（凭证多半仍有效，
         * 只是此刻环境不允许登录），也**不该**据此把用户踢到登录页，稍后重试即可恢复。
         */
        data class Transient(val message: String) : Result
    }

    /** 探测本地会话是否仍然可用。三态区分「确实失效」与「网络够不着」，让上层能乐观放行。 */
    sealed interface SessionProbe {
        /** 会话有效，附带已经取到的 `/User/Default.aspx` HTML，调用方可直接解析，省一次往返。 */
        data class Valid(val html: String) : SessionProbe
        /** 会话确证失效（无 jwc cookie / 被踢回登录页 / jwc 5xx）——必须重新登录。 */
        data object Invalid : SessionProbe
        /** 网络够不着（超时 / DNS / TLS）——不知道会话死没死，别据此销毁本地会话。 */
        data object Unreachable : SessionProbe
    }

    /** [fetchExecutionToken] 的细分结果，便于区分网络失败 / 重定向 / HTML 结构异常。 */
    private sealed interface ExecutionResult {
        data class Ok(val token: String) : ExecutionResult
        data class Bad(val message: String) : ExecutionResult
    }

    /**
     * 免密 SSO 续票：**不清任何 cookie**，直接 GET `/cas/login?service=…`。
     * 若本地 CAS 的 TGC 仍有效，CAS 会 302 带 service ticket 回 jwc → jwc 落新 session cookie。
     * 成功判据是**能真正解析出已登录首页**（lblUserInfor 学号），而不是「落点在 jwc 且不像登录页」——
     * 后者会被匿名可见的 Portal 壳页骗过（假续票成功）。
     *
     * @return true = 续票成功、jwc 会话已刷新；false = TGC 失效或网络失败，需退回完整账密登录。
     *
     * 全程 raw OkHttp（不走 *Auth），因此调用方（[AuthRepository] 持 authMutex）不会重入 SessionRecovery。
     */
    suspend fun tryRefreshViaSso(): Boolean = withContext(Dispatchers.IO) {
        // 本地压根没有 TGC 就别发这一枪：注定 302 回登录表单，纯浪费 + 徒增对 CAS 的打点。
        if (!httpClient.cookieJar.hasCasTgc()) return@withContext false
        runCatching {
            // GET 登录入口：TGC 有效 → CAS 302 换票 → OkHttp 跟随重定向落到 jwc。
            val entryReq = Request.Builder().url(JxnuUrls.casLoginEntry()).get().build()
            httpClient.client.newCall(entryReq).execute().use { it.body.string() }
            // 用 /User/Default.aspx 复验：解得出学号才算真的登进去了。
            val probeReq = Request.Builder().url(JxnuUrls.USER_DEFAULT).get().build()
            httpClient.client.newCall(probeReq).execute().use { resp ->
                val html = JwcResponseGuard.readJwcHtml(resp, "用户首页返回空响应")
                UserDefaultPage.extractStudentId(html)?.isNotBlank() == true
            }
        }.getOrDefault(false)
    }

    suspend fun login(
        username: String,
        password: String,
        captcha: String = "",
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            // 起手清掉 uis.jxnu.edu.cn 域的旧 cookie（主要是 CASTGC / TGC）。
            //
            // 历史 bug：曾有一次报"登录页结构异常：缺少 execution 字段"，间歇出现无法稳定复现。
            // 根因是 jwc 业务 session 失效后 validateSession 已返回 false，但 CAS 的 TGC 还有效——
            // GET 登录页时 CAS 会直接 302 跳过登录表单走 SSO，OkHttp 跟随重定向落在 jwc 主页，
            // 解析出的 HTML 根本没有 execution 字段。
            //
            // 这里清掉 uis 域 cookie，迫使 CAS 重新渲染登录表单。原代码注释里作者预判过此问题
            // （"如果遇到问题再加 clear"），现在补上。
            httpClient.cookieJar.clearForHost(JxnuUrls.CAS_HOST)

            val loginEntryUrl = JxnuUrls.casLoginEntry()
            val execution = when (val r = fetchExecutionToken(loginEntryUrl)) {
                is ExecutionResult.Ok -> r.token
                // 登录页结构异常 / 被 302 走 SSO / 网络失败 —— 都不是「凭证错」，归瞬时，别清密码。
                is ExecutionResult.Bad -> return@runCatching Result.Transient(r.message)
            }

            val publicKeyMaterial = fetchPublicKey()
            val encryptedPassword = runCatching {
                RsaPasswordEncryptor.encryptPassword(password, publicKeyMaterial)
            }.getOrElse {
                return@runCatching Result.Transient("密码加密失败：${it.message}")
            }

            val visitorId = DeviceFingerprint.visitorId(appContext, authStorage)

            val form = FormBody.Builder()
                .add("username", username)
                .add("password", encryptedPassword)
                .add("captcha", captcha)
                .add("rememberMe", "on")
                .add("currentMenu", "1")
                .add("failN", "-1")
                .add("mfaState", "")
                .add("execution", execution)
                .add("_eventId", "submit")
                .add("geolocation", "")
                .add("fpVisitorId", visitorId)
                .add("trustAgent", "")
                .add("submit1", "Login1")
                .build()

            val req = Request.Builder()
                .url(loginEntryUrl)
                .post(form)
                .header("Referer", loginEntryUrl)
                .header("Origin", "https://${JxnuUrls.CAS_HOST}")
                .build()

            httpClient.client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val finalHost = resp.request.url.host
                when {
                    !resp.isSuccessful && resp.code !in 300..399 ->
                        Result.Transient("登录失败：HTTP ${resp.code}")

                    finalHost == JxnuUrls.JWC_HOST -> Result.Success

                    finalHost == JxnuUrls.CAS_HOST && finalUrl.contains("/cas/login") -> {
                        // 落回 CAS 登录页：**默认认定凭证失效**（清密码是安全默认，防旧密码反复打 CAS 锁号）。
                        // 只有明确嗅探到「验证码 / 风控 / 系统繁忙」这类**环境性**拒绝时才降级为 Transient 保留凭证——
                        // 这些是白名单，识别不出（含错误文案抓不到的常见情况）一律当密码错清凭证。
                        val body = resp.body.string()
                        classifyCasRejection(body)
                    }

                    else -> Result.Transient("登录后落点未识别：$finalUrl")
                }
            }
        }.getOrElse { e ->
            Result.Transient(formatException(e))
        }
    }

    /**
     * 探测当前 cookie 是否还能登录：GET `/User/Default.aspx`，三态区分「有效 / 确证失效 / 网络够不着」。
     *
     * 关键：**只有真正的网络异常（IOException：超时 / DNS / TLS）才归 [SessionProbe.Unreachable]**。
     * `JwcException(SessionExpired)`、无 jwc cookie、jwc 5xx 都是「确证失效」→ [SessionProbe.Invalid]。
     * 弄反会致命：把「确定已死的会话」当成离线乐观放行，用户永远进不去也永不提示重登。
     *
     * [SessionProbe.Valid] 携带已取到的首页 HTML，[AuthRepository.tryRestoreSession] 可直接解析，
     * 省掉紧接着 fetchAndParse 对同一 URL 的第二次 GET。
     */
    suspend fun probeSession(): SessionProbe = withContext(Dispatchers.IO) {
        if (!httpClient.cookieJar.hasJwcSession()) return@withContext SessionProbe.Invalid
        try {
            val req = Request.Builder().url(JxnuUrls.USER_DEFAULT).get().build()
            httpClient.client.newCall(req).execute().use { resp ->
                // readJwcHtml 会把「200 实为登录页 / 跳 CAS / 跳 SSO 重登页 / preurl 未登录跳转 /
                // 访问受限 stub / jwc 5xx」全部抛成 JwcException。
                val html = JwcResponseGuard.readJwcHtml(resp, "用户首页返回空响应")
                // 语义判活：拿回的必须是真·已登录首页（lblUserInfor 里解得出学号）。jwc 对无效
                // 会话的报错形态一变再变（2026-07 起是 default.aspx?preurl= 跳转 / 「访问受限」
                // 短文本），Guard 的形态清单永远可能落后一步；这里按「首页语义」兜底，解不出
                // 学号一律按失效处理——决不把垃圾页放行成 Valid（那会让冷启动带着占位 profile
                // 假登录进主界面、课表/个人信息全空且永不自愈）。与 [tryRefreshViaSso] 的复验同款。
                if (UserDefaultPage.extractStudentId(html).isNullOrBlank()) {
                    SessionProbe.Invalid
                } else {
                    SessionProbe.Valid(html)
                }
            }
        } catch (e: JwcException) {
            // 业务语义的失效（含 SessionExpired / EmptyResponse / Server 5xx / 异常重定向）→ 确证失效。
            SessionProbe.Invalid
        } catch (e: IOException) {
            // 纯网络够不着 → 不知道会话死没死，交给上层决定是否乐观放行。
            SessionProbe.Unreachable
        }
    }

    /** 退出：清 cookie + 清存储；下一次访问需要重新登录。 */
    fun logout() {
        httpClient.cookieJar.clearAll()
        // 不清 lastUsername / installUuid，保留用户体验
    }

    @Throws(IOException::class)
    private fun fetchExecutionToken(loginUrl: String): ExecutionResult {
        val req = Request.Builder().url(loginUrl).get().build()
        httpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ExecutionResult.Bad("登录页请求失败：HTTP ${resp.code}")
            }
            // OkHttp 跟随重定向后落点要么仍在 CAS（正常的登录表单），要么被 SSO 直接跳到了
            // jwc / Portal（说明 CAS 视角已认证，本不该走到 login 流程）。后者通常意味着
            // 上面 clearForHost 没生效或之后又被刷上了新 cookie——抛出明确信息便于排查。
            val finalHost = resp.request.url.host
            if (finalHost != JxnuUrls.CAS_HOST) {
                return ExecutionResult.Bad("登录页被重定向至 $finalHost，CAS 可能已存在有效会话")
            }
            val html = resp.body.string()
            if (html.isEmpty()) {
                return ExecutionResult.Bad("登录页响应为空")
            }
            return parseExecutionFromHtml(html)
                ?.let { ExecutionResult.Ok(it) }
                ?: ExecutionResult.Bad("登录页结构异常：未找到 execution 字段（CAS 可能已改版）")
        }
    }

    @Throws(IOException::class)
    private fun fetchPublicKey(): String {
        val req = Request.Builder()
            .url(JxnuUrls.CAS_PUBLIC_KEY)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", JxnuUrls.casLoginEntry())
            .build()
        httpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("公钥接口返回 HTTP ${resp.code}")
            return resp.body.string().trim().also {
                if (it.isEmpty()) error("公钥响应为空")
            }
        }
    }

    internal companion object {
        /**
         * 从 CAS 登录页 HTML 中提取 `execution` 字段，提不到返回 null。Pure function，便于单测。
         *
         * 兼容两种渲染：
         *  - 传统 CAS 表单：`<input type="hidden" name="execution" value="e1s1">`
         *  - Vue 单页（江西师大现行版）：内联 JS 里 `execution: "..."` 或 `"execution":"..."`
         */
        internal fun parseExecutionFromHtml(html: String): String? {
            val doc = Jsoup.parse(html)
            doc.selectFirst("input[name=execution]")?.attr("value")?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            val regex = Regex("""["']?execution["']?\s*[:=]\s*["']([^"']+)["']""")
            return regex.find(html)?.groupValues?.get(1)
        }

        /**
         * 把「POST 后落回 /cas/login」的 body 分类为 [Result]。**白名单降级**：
         *
         *  - 默认（含错误文案抓不到 / 江西师大 Vue 版常抓不到文案）→ [Result.InvalidCredentials]
         *    清凭证。这是安全默认：旧密码 / 改过的密码反复打 CAS 会把账号打锁，宁可让用户手动重登。
         *  - **仅当**明确嗅探到「验证码 / 滑块 / 系统繁忙 / 频繁 / 稍后再试」这类**环境性**拒绝时
         *    → [Result.Transient] 保留凭证：这不是密码错，是此刻登录不了，稍后能自愈。
         *
         * 注意本 app 登录页没有验证码输入 UI（captcha 恒为空串），所以「需验证码」对手动登录同样
         * 无法满足——把它归 Transient 只是为了**不误清正确密码**，靠 throttle 退避避免风暴，并不指望自愈。
         *
         * Pure function，便于单测。
         */
        internal fun classifyCasRejection(body: String): Result {
            val text = runCatching { Jsoup.parse(body).text() }.getOrDefault(body)
            val hitEnvironmental = CAS_ENVIRONMENTAL_MARKERS.any { it in text }
            return if (hitEnvironmental) {
                Result.Transient("登录暂时受限（需验证码或稍后重试）")
            } else {
                Result.InvalidCredentials(extractCasErrorText(body) ?: "账号或密码错误，请重新登录")
            }
        }

        /** 从 CAS 登录页 HTML 抽人类可读错误文案（供 InvalidCredentials 展示）；抓不到返回 null。 */
        private fun extractCasErrorText(html: String): String? {
            if (html.isBlank()) return null
            val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
            val candidates = listOf(
                "div.alert-danger", "div.error-msg", "div.errors",
                "div[class*=error]", "p.error", "span.error",
            )
            for (sel in candidates) {
                val t = doc.selectFirst(sel)?.text()?.trim()
                if (!t.isNullOrEmpty()) return t
            }
            return Regex("""\b(?:errorMsg|errorMessage|errMsg)\s*[:=]\s*["']([^"']{4,80})["']""")
                .find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        }

        /**
         * 「环境性拒绝」白名单标记：命中任一即认定不是密码错、而是此刻登录不了，保留凭证。
         * 只收对「密码是否正确」中立的词——绝不含「密码 / 账号」等可能真表示凭证错的词。
         */
        private val CAS_ENVIRONMENTAL_MARKERS = listOf(
            "验证码", "captcha", "滑块", "拖动", "拼图",
            "系统繁忙", "稍后", "稍候", "频繁", "过于频繁",
            "请重试", "请稍后再试", "服务繁忙", "访问过快",
        )
    }

    private fun formatException(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络连接"
        is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
        is javax.net.ssl.SSLException -> "SSL 握手失败：${t.message ?: "证书错误"}"
        is IOException -> "网络异常：${t.message ?: "I/O 错误"}"
        else -> t.message ?: t::class.java.simpleName
    }
}
