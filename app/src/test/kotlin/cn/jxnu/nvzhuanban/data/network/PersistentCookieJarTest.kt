package cn.jxnu.nvzhuanban.data.network

import okhttp3.Cookie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 [cookieAffectsHost] 的边界，回归保护早期 `clearForHost` 的路径过滤 bug —
 * 旧实现用 `Cookie.matches(https://host/)` 判定，TGC (path=/cas) 不匹配 path=/ 探测 URL，
 * 导致 CAS 登录前的 cookie 清理形同虚设、CAS 直接 302 跳过登录表单。
 */
class PersistentCookieJarTest {

    @Test
    fun `tgc with path cas is cleared for cas host`() {
        val tgc = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-xxx")
            .hostOnlyDomain("uis.jxnu.edu.cn")
            .path("/cas")
            .build()
        assertTrue(cookieAffectsHost(tgc, "uis.jxnu.edu.cn"))
    }

    @Test
    fun `parent domain cookie matches subdomain`() {
        val tgc = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-xxx")
            .domain("jxnu.edu.cn")
            .path("/")
            .build()
        assertTrue(cookieAffectsHost(tgc, "uis.jxnu.edu.cn"))
        assertTrue(cookieAffectsHost(tgc, "jwc.jxnu.edu.cn"))
    }

    @Test
    fun `host only cookie does not match unrelated subdomain`() {
        val jwcSession = Cookie.Builder()
            .name("ASP.NET_SessionId")
            .value("abc")
            .hostOnlyDomain("jwc.jxnu.edu.cn")
            .path("/")
            .build()
        // 清 uis 不应牵连 jwc 业务 session
        assertFalse(cookieAffectsHost(jwcSession, "uis.jxnu.edu.cn"))
        // 自己 host 还能匹配
        assertTrue(cookieAffectsHost(jwcSession, "jwc.jxnu.edu.cn"))
    }

    @Test
    fun `unrelated host returns false`() {
        val tgc = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-xxx")
            .domain("jxnu.edu.cn")
            .path("/")
            .build()
        assertFalse(cookieAffectsHost(tgc, "example.com"))
        assertFalse(cookieAffectsHost(tgc, "evil-jxnu.edu.cn.attacker.com"))
    }

    @Test
    fun `path filter is ignored - cookie on cas path still affects host`() {
        // 这是 bug 的核心场景：cookie path 是 `/cas`，host 探针 path 是 `/`。
        // 旧实现走 Cookie.matches 会因为 path 前缀不匹配而漏掉；新实现忽略 path 直接命中。
        val tgc = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-xxx")
            .hostOnlyDomain("uis.jxnu.edu.cn")
            .path("/cas")
            .build()
        // 旧 bug 在这里返回 false；新实现必须 true
        assertTrue(cookieAffectsHost(tgc, "uis.jxnu.edu.cn"))
    }

    @Test
    fun `case is normalized`() {
        val tgc = Cookie.Builder()
            .name("CASTGC")
            .value("TGT-xxx")
            .domain("jxnu.edu.cn")
            .path("/")
            .build()
        // OkHttp Builder 已 lowercase cookie.domain；host 端我们自己再 lowercase 一次防御
        assertTrue(cookieAffectsHost(tgc, "UIS.JXNU.EDU.CN"))
    }
}
