package cn.jxnu.nvzhuanban.data.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * [PersistentCookieJar] 的内存过滤规则 + 落盘协议。用 internal 的 File 构造器把 filesDir
 * 指到 JUnit 临时目录，脱离 Android Context 在纯 JVM 验证 CLAUDE.md 强调的关键不变量：
 * host 白名单、max-age=0 删除、同 name+path 覆盖、clearAll 的 stopped 栅门、clearForHost
 * 的 resume，以及 loadFromDisk / persist 的 TSV round-trip。
 *
 * 已有的 [PersistentCookieJarTest] 只覆盖纯函数 cookieAffectsHost；本类补的是实例行为。
 */
class PersistentCookieJarStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jwcUrl = "https://jwc.jxnu.edu.cn/User/Default.aspx".toHttpUrl()

    private fun sessionCookie(
        name: String = "ASP.NET_SessionId",
        value: String = "abc",
        host: String = "jwc.jxnu.edu.cn",
        path: String = "/",
        maxAgeMs: Long = 3_600_000L,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .hostOnlyDomain(host)
        .path(path)
        .expiresAt(System.currentTimeMillis() + maxAgeMs)
        .build()

    private fun newJar() = PersistentCookieJar(tmp.root)

    @Test
    fun `saves and loads a jxnu cookie`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        val loaded = jar.loadForRequest(jwcUrl)
        assertEquals(1, loaded.size)
        assertEquals("abc", loaded.first().value)
    }

    @Test
    fun `rejects non-jxnu host cookie`() {
        val jar = newJar()
        val githubUrl = "https://api.github.com/x".toHttpUrl()
        jar.saveFromResponse(githubUrl, listOf(sessionCookie(host = "api.github.com")))
        assertTrue(jar.loadForRequest(githubUrl).isEmpty())
    }

    @Test
    fun `max-age zero cookie is not stored`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie(maxAgeMs = -1_000L)))
        assertTrue(jar.loadForRequest(jwcUrl).isEmpty())
    }

    @Test
    fun `same name and path overwrites old value`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie(value = "old")))
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie(value = "new")))
        val loaded = jar.loadForRequest(jwcUrl)
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.first().value)
    }

    @Test
    fun `clearAll empties and stops accepting until resume`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        jar.clearAll()
        assertTrue(jar.loadForRequest(jwcUrl).isEmpty())
        // stopped 栅门：clearAll 之后到达的"漏网"cookie 必须被拦，不能重建刚清掉的会话
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        assertTrue(jar.loadForRequest(jwcUrl).isEmpty())
    }

    @Test
    fun `clearAll rejects a response already waiting to save cookies`() {
        val jar = newJar()
        // 固定真实竞争顺序：响应已经进入 saveFromResponse、等待写锁时，logout 抢先清空。
        // 直接控制已有锁，避免为测试在生产代码插入延迟或回调。
        val lock = cookieLock(jar)
        val responseFailure = AtomicReference<Throwable?>()
        val response = Thread {
            runCatching { jar.saveFromResponse(jwcUrl, listOf(sessionCookie(value = "old-response"))) }
                .exceptionOrNull()?.let(responseFailure::set)
        }.apply { isDaemon = true }

        lock.writeLock().lock()
        try {
            response.start()
            waitUntil(2_000L) { lock.hasQueuedThread(response) }
            assertTrue("响应必须已进入保存流程并等待写锁", lock.hasQueuedThread(response))
            // ReentrantReadWriteLock 允许当前线程重入，因此确定地在旧响应写入前完成 logout。
            jar.clearAll()
            assertTrue(jar.loadForRequest(jwcUrl).isEmpty())
        } finally {
            lock.writeLock().unlock()
            response.join(2_000L)
        }

        assertFalse("旧响应线程不能死锁", response.isAlive)
        responseFailure.get()?.let { throw AssertionError("响应保存失败", it) }
        assertTrue("logout 后旧响应不能重新插入 cookie", jar.loadForRequest(jwcUrl).isEmpty())
        assertFalse(jar.hasJwcSession())
        assertFalse(File(tmp.root, "jxnu_cookies.tsv").exists())
        assertFalse(File(tmp.root, "jxnu_cookies.tsv.tmp").exists())
    }

    @Test
    fun `clearAll waits for an active save then removes its memory and disk state`() {
        val jar = newJar()
        val lock = cookieLock(jar)
        val saving = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cookies = object : AbstractList<Cookie>() {
            override val size = 1
            override fun get(index: Int): Cookie {
                // forEach 已在保存写锁里：让 logout 确定地等待这一批响应处理完成。
                saving.countDown()
                check(releaseResponse.await(2, TimeUnit.SECONDS)) { "响应未及时放行" }
                return sessionCookie(value = "active-response")
            }
        }
        val response = Thread {
            runCatching { jar.saveFromResponse(jwcUrl, cookies) }.exceptionOrNull()?.let(failure::set)
        }.apply { isDaemon = true }
        val logout = Thread {
            runCatching { jar.clearAll() }.exceptionOrNull()?.let(failure::set)
        }.apply { isDaemon = true }

        try {
            response.start()
            assertTrue("响应必须已经持有写锁", saving.await(2, TimeUnit.SECONDS))
            logout.start()
            waitUntil(2_000L) { lock.hasQueuedThread(logout) }
            assertTrue("logout 必须等待正在保存的响应", lock.hasQueuedThread(logout))
        } finally {
            releaseResponse.countDown()
            response.join(2_000L)
            logout.join(2_000L)
        }

        assertFalse(response.isAlive)
        assertFalse(logout.isAlive)
        failure.get()?.let { throw AssertionError("并发保存/退出失败", it) }
        assertTrue(jar.loadForRequest(jwcUrl).isEmpty())
        assertFalse(File(tmp.root, "jxnu_cookies.tsv").exists())
        assertFalse(File(tmp.root, "jxnu_cookies.tsv.tmp").exists())
        assertFalse(newJar().hasJwcSession())
    }

    @Test
    fun `clearForHost resumes acceptance`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        jar.clearAll()                          // stopped = true
        jar.clearForHost("jwc.jxnu.edu.cn")     // login 起手仪式，应 resume
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie(value = "fresh")))
        val loaded = jar.loadForRequest(jwcUrl)
        assertEquals(1, loaded.size)
        assertEquals("fresh", loaded.first().value)
    }

    @Test
    fun `hasJwcSession reflects stored jwc cookie`() {
        val jar = newJar()
        assertFalse(jar.hasJwcSession())
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        assertTrue(jar.hasJwcSession())
    }

    @Test
    fun `hasCasTgc detects TGC on cas login path`() {
        val jar = newJar()
        assertFalse(jar.hasCasTgc())
        // TGC 种在 uis 域、path=/cas —— SSO 免密续票就靠它判断值不值得一试
        val casUrl = "https://uis.jxnu.edu.cn/cas/login".toHttpUrl()
        jar.saveFromResponse(
            casUrl,
            listOf(sessionCookie(name = "TGC", value = "TGT-xxx", host = "uis.jxnu.edu.cn", path = "/cas")),
        )
        assertTrue(jar.hasCasTgc())
    }

    @Test
    fun `hasCasTgc false when only jwc session present`() {
        val jar = newJar()
        jar.saveFromResponse(jwcUrl, listOf(sessionCookie()))
        // 只有 jwc 业务 session、没有 CAS TGC → 不该误报可续票
        assertFalse(jar.hasCasTgc())
    }

    @Test
    fun `persists to disk and a fresh jar loads it back`() {
        val jar1 = newJar()
        jar1.saveFromResponse(jwcUrl, listOf(sessionCookie(value = "persisted")))

        // 落盘走后台 daemon 线程，poll 等它把 tmp 原子 rename 成最终文件
        val cookieFile = File(tmp.root, "jxnu_cookies.tsv")
        waitUntil(2_000L) { cookieFile.exists() && cookieFile.readText().isNotBlank() }
        assertTrue("cookie 应已落盘", cookieFile.exists())

        val jar2 = newJar()  // 构造时 init 触发 loadFromDisk
        val loaded = jar2.loadForRequest(jwcUrl)
        assertEquals(1, loaded.size)
        assertEquals("persisted", loaded.first().value)
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
    }

    private fun cookieLock(jar: PersistentCookieJar): ReentrantReadWriteLock =
        PersistentCookieJar::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(jar) as ReentrantReadWriteLock
        }
}
