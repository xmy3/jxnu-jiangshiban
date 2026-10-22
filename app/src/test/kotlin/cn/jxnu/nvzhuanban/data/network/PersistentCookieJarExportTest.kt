package cn.jxnu.nvzhuanban.data.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 调试页依赖的两个新能力：[PersistentCookieJar.exportJson] / [PersistentCookieJar.importJson]
 * 的 round-trip 与非法输入防线。
 *
 * 用 internal 的 File 构造器把 filesDir 指到 JUnit 临时目录（与 [PersistentCookieJarStoreTest]
 * 同款），纯 JVM 验证，不需要 Android Context。
 */
class PersistentCookieJarExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jwcUrl = "https://jwc.jxnu.edu.cn/User/Default.aspx".toHttpUrl()

    private fun cookie(
        name: String,
        value: String,
        path: String = "/",
        maxAgeMs: Long = 3_600_000L,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .hostOnlyDomain("jwc.jxnu.edu.cn")
        .path(path)
        .expiresAt(System.currentTimeMillis() + maxAgeMs)
        .build()

    private fun newJar(folder: String) = PersistentCookieJar(tmp.newFolder(folder))

    @Test
    fun `import with no allowed domain preserves the current session`() {
        val target = newJar("target")
        target.saveFromResponse(jwcUrl, listOf(cookie("JWT", "keep")))
        assertRejected {
            target.importJson("""[{"name":"JWT","value":"x","domain":"example.com"}]""")
        }
        assertEquals("keep", target.loadForRequest(jwcUrl).single().value)
    }

    /** 断言 [PersistentCookieJar.importJson] 抛统一文案的 IllegalArgumentException。 */
    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("非法 cookie JSON 应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cookie JSON 格式不正确", e.message)
        }
    }

    @Test
    fun `export then import round-trips cookies into a fresh jar`() {
        val source = newJar("source")
        source.saveFromResponse(
            jwcUrl,
            listOf(
                cookie("ASP.NET_SessionId", "abc"),
                cookie("JWT", "xyz", path = "/User"),
            ),
        )
        val json = source.exportJson()

        // 另一份 filesDir（模拟另一台设备 / 另一个安装）导入后应能取回同名同值的 cookie。
        val target = newJar("target")
        assertEquals(2, target.importJson(json))

        val restored = target.loadForRequest(jwcUrl)
        assertEquals(setOf("ASP.NET_SessionId", "JWT"), restored.map { it.name }.toSet())
        assertEquals("abc", restored.first { it.name == "ASP.NET_SessionId" }.value)
        assertEquals("xyz", restored.first { it.name == "JWT" }.value)
    }

    @Test
    fun `import rejects malformed json and leaves existing cookies untouched`() {
        val target = newJar("target")
        target.saveFromResponse(jwcUrl, listOf(cookie("JWT", "keep")))

        assertRejected { target.importJson("{not-json") }
        // 顶层是对象而不是数组
        assertRejected { target.importJson("{\"name\":\"JWT\",\"value\":\"x\"}") }
        // 数组元素缺 domain
        assertRejected { target.importJson("[{\"name\":\"JWT\",\"value\":\"x\"}]") }

        // 失败路径不能把内存里的会话清掉（"清一半"比没有更难排障）
        assertEquals("keep", target.loadForRequest(jwcUrl).single().value)
    }

    @Test
    fun `import rejects empty array so state cannot be wiped by accident`() {
        val target = newJar("target")
        assertRejected { target.importJson("[]") }
        assertRejected { target.importJson("") }
    }

    @Test
    fun `summary reports host and name but flags expired cookies`() {
        val source = newJar("source")
        // 过期 cookie 不能通过 saveFromResponse 写入（expiresAt <= now 会被丢弃），只能从 JSON 导入，
        // 正好用来验证 summary() 的 expired 判定。
        source.importJson(
            "[{\"name\":\"OLD\",\"value\":\"v\",\"domain\":\"jwc.jxnu.edu.cn\",\"path\":\"/\"," +
                "\"expiresAt\":1,\"secure\":false,\"httpOnly\":false,\"hostOnly\":true}]",
        )

        val summaries = source.summary()
        assertEquals(1, summaries.size)
        assertEquals("jwc.jxnu.edu.cn", summaries.single().host)
        assertEquals("OLD", summaries.single().name)
        assertTrue(summaries.single().expired)
    }
}
