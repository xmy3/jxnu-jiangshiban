package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 文件持久化 CookieJar。
 *
 * 设计选择：
 * - 用纯文本 TSV 文件，每行一条 cookie，便于排查问题（生产环境可改 EncryptedSharedPreferences）
 * - 内存里用 ConcurrentHashMap 缓存，写入文件异步触发避免阻塞主线程
 * - 启动时同步加载（filesDir 文件 IO 通常 < 10ms，可接受）
 * - 落盘采用 tmp + atomic rename，断电只会留下完整旧版或完整新版，不会损坏
 * - 不存 cookie 的安全字段细节（拿到的字段够 OkHttp 重建即可）
 *
 * 该 CookieJar 必须**先于**任何 OkHttp 请求构造（即 Application.onCreate 里 init）。
 */
class PersistentCookieJar internal constructor(filesDir: File) : CookieJar {

    /** 生产构造从 [Context] 取 filesDir；测试走 internal 构造器直接传临时目录（脱离 Android）。 */
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val cookieFile: File = File(filesDir, "jxnu_cookies.tsv")
    private val tmpFile: File = File(filesDir, "jxnu_cookies.tsv.tmp")
    private val byDomain = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val lock = ReentrantReadWriteLock()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // [stopped] 拦截 clearAll 之后到达的"漏网"cookie，避免它们重建刚被清掉的会话文件。
        // login() / clearForHost() 会显式 resume()，恢复后正常接收。
        if (stopped) return
        // host 白名单：本 CookieJar 只为 *.jxnu.edu.cn 服务。共享同一个 OkHttp 实例时，
        // 任何第三方主机（CDN / 统计 / GitHub）的 Set-Cookie 都不允许污染 jxnu_cookies.tsv，
        // 否则下次 CAS 重定向链上 OkHttp 可能把第三方 cookie 错发给 jwc，或者 cookie 文件越来越大。
        // GitHubUpdateClient 用独立 OkHttp 是已知规避手段；这里再加一道结构性兜底。
        if (!url.host.isJxnuHost()) return
        lock.write {
            cookies.forEach { c ->
                // 二次防御：服务器若把 cookie 的 Domain 设到非 jxnu 域（极少见的开放重定向场景），
                // 也一并丢弃。`Cookie.matches(url)` 已经保证 domain 是 url.host 的同域或父域，
                // 但 url.host 是 jxnu 不代表 cookie.domain 也是——OkHttp 会按 RFC 6265 接收。
                if (!c.domain.isJxnuHost()) return@forEach
                val list = byDomain.getOrPut(c.domain) { mutableListOf() }
                // 同 name + path 视为同一个 cookie，新值覆盖旧值
                list.removeAll { it.name == c.name && it.path == c.path }
                // 服务器主动用 max-age=0 删除某个 cookie：不要再加回去
                if (c.expiresAt > System.currentTimeMillis()) {
                    list.add(c)
                }
            }
        }
        persistAsync()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matching = mutableListOf<Cookie>()
        val expired = mutableListOf<Pair<String, Cookie>>()
        // 读路径走读锁，让 CAS 重定向链上的多个 OkHttp 调用真并发。
        // 过期项先记下来，最后再升级到写锁批量清掉。
        lock.read {
            for ((domain, list) in byDomain) {
                for (c in list) {
                    if (c.expiresAt <= now) {
                        expired.add(domain to c)
                        continue
                    }
                    if (c.matches(url)) matching.add(c)
                }
            }
        }
        if (expired.isNotEmpty()) {
            lock.write {
                for ((domain, c) in expired) {
                    byDomain[domain]?.removeAll { it.name == c.name && it.path == c.path && it.expiresAt == c.expiresAt }
                }
                byDomain.entries.removeAll { (_, v) -> v.isEmpty() }
            }
            persistAsync()
        }
        // RFC 6265 §5.4：更具体的 path 必须排在前面。某些 ASP.NET 会话 cookie 在 /cas
        // 和 / 各设一份同名，顺序错了服务端会按"较短 path"那份决定 session。
        matching.sortWith(compareByDescending { it.path.length })
        return matching
    }

    /** 清空所有 cookie（退出登录时调用）。会阻止后续 [saveFromResponse] 直到下一次 [resume]。 */
    fun clearAll() {
        lock.write { byDomain.clear() }
        synchronized(persistLock) {
            stopped = true
            persistGeneration++
            dirty = false
            runCatching { cookieFile.delete() }
            runCatching { tmpFile.delete() }
        }
    }

    /**
     * 清掉所有 domain 落在 [host] 上的 cookie，**忽略 path / secure / httpOnly**。
     *
     * 历史 bug：旧实现用 `Cookie.matches(https://$host/)` 过滤，附带了 RFC 6265 的 **path** 检查 —
     * 而 CAS 的 TGC（票据-粒度凭证）通常种在 `path=/cas`。`/cas` 不是 `/` 的前缀，所以 TGC 不会被清。
     * 用户表现：登录前调用 `clearForHost(uis.jxnu.edu.cn)` 之后，GET `/cas/login` 时浏览器仍把 TGC
     * 发回去 → CAS 看到有效 TGT 直接 302 跳过登录表单 → 落点 jwc → `fetchExecutionToken` 抛出
     * "登录页被重定向至 jwc.jxnu.edu.cn"。
     *
     * 新语义：按 cookie domain 与 [host] 的归属关系判定，与请求路径无关。
     *  - hostOnly cookie（domain 精确等于 host）：domain == host
     *  - domain cookie（domain 是 host 的父域，例如 `.jxnu.edu.cn` 对 uis 子域）：host 以 ".$domain" 结尾
     * 这样 TGC (path=/cas) / JSESSIONID (path=/cas) / CASPRIVACY (path=/) 都会被一并清掉，
     * 但不会牵连 jwc.jxnu.edu.cn 域的业务 session（hostOnly=jwc，不与 uis 同 host）。
     *
     * 调用此方法后会显式 resume，确保后续 [saveFromResponse] 接收 cookie。
     */
    fun clearForHost(host: String) {
        var changed = false
        lock.write {
            for ((_, list) in byDomain) {
                val it = list.iterator()
                while (it.hasNext()) {
                    if (cookieAffectsHost(it.next(), host)) {
                        it.remove()
                        changed = true
                    }
                }
            }
            if (changed) byDomain.entries.removeAll { (_, v) -> v.isEmpty() }
        }
        if (changed) {
            synchronized(persistLock) {
                stopped = false  // login 链路立刻会写入新 cookie，确保 saveFromResponse 不被拦
                persistGeneration++
            }
            persistAsync()
        } else {
            // 没有 cookie 要清也至少把 stopped 复位 —— clearForHost 是 login 起手仪式，调用方期望
            // 后续的 saveFromResponse 一定能落库。
            synchronized(persistLock) { stopped = false }
        }
    }

    /**
     * 判断当前是否有能发送到 jwc 域名的 cookie。
     *
     * 用 OkHttp 自带的 `Cookie.matches(url)` 做匹配，正确处理：
     *  - cookie domain = `jwc.jxnu.edu.cn`（精确）
     *  - cookie domain = `jxnu.edu.cn`（更宽的父域名）
     *  - path / secure / httpOnly / expiresAt 限制
     */
    fun hasJwcSession(): Boolean {
        val probeUrl = "https://${JxnuUrls.JWC_HOST}/".toHttpUrl()
        return loadForRequest(probeUrl).isNotEmpty()
    }

    /**
     * 本地是否还留着 CAS 的票据授予凭证（TGC / CASTGC，通常 `path=/cas`）。
     *
     * 用来决定「免密 SSO 续票」值不值得一试：TGC 在（可能仍有效）才 GET 一次 `/cas/login`
     * 让 CAS 用它换新的 jwc 会话；TGC 根本不在（登录起手 clearForHost 清过、或从没登录过）就
     * 直接跳过这一步，省掉一次注定 302 回登录表单的无谓请求。probe url 用 `/cas/login` 覆盖
     * `path=/cas` 的 TGC。
     */
    fun hasCasTgc(): Boolean {
        val probeUrl = "https://${JxnuUrls.CAS_HOST}/cas/login".toHttpUrl()
        return loadForRequest(probeUrl).any {
            it.name.equals("TGC", ignoreCase = true) || it.name.equals("CASTGC", ignoreCase = true)
        }
    }

    private fun loadFromDisk() {
        if (!cookieFile.exists()) return
        runCatching {
            cookieFile.readLines().forEach { line ->
                parseCookieLine(line)?.let { c ->
                    // 与 saveFromResponse 同口径，丢弃旧版本可能落库的非 jxnu cookie
                    if (!c.domain.isJxnuHost()) return@let
                    byDomain.getOrPut(c.domain) { mutableListOf() }.add(c)
                }
            }
        }
    }

    private val persistLock = Any()
    /** 有未落盘的修改。每次 saveFromResponse / prune 都置 true，落盘后由后台线程清零。 */
    @Volatile private var dirty = false
    /** 后台落盘线程是否已经启动。同时只允许一个，避免并发写文件。 */
    @Volatile private var writing = false
    /** 单调递增的持久化版本，用来阻止退出登录前的旧快照在清空后回写到磁盘。 */
    @Volatile private var persistGeneration = 0L
    /**
     * "拒绝接收新 cookie"的栅门。[clearAll] 置 true，[clearForHost] 置 false。
     * 防止 logout 后还在飞的 OkHttp 响应通过 [saveFromResponse] 把会话 cookie 重新落库。
     */
    @Volatile private var stopped = false

    /**
     * 标记有修改并按需启动后台落盘线程。
     *
     * 旧实现用单 boolean `persistPending`：写线程进入后早期就置 false，导致写期间到来的
     * 新写入找不到"已有线程在跑"的信号 → 也直接返回 → 新 cookie 不落盘。
     * 现在改成 dirty/writing 分离：dirty 任何时候由新写入置 true；写线程在每轮写完后
     * 重新检查 dirty，仍脏就再写一轮，直到追平为止才退出。
     */
    private fun persistAsync() {
        val shouldStart = synchronized(persistLock) {
            if (stopped) return  // logout 后期"漏网"写入直接吞掉，不重启 daemon
            dirty = true
            if (writing) false else { writing = true; true }
        }
        if (!shouldStart) return
        Thread {
            try {
                while (true) {
                    val generation = synchronized(persistLock) {
                        if (stopped) {
                            // 中途被 clearAll：把 writing 复位，让 logout 之后再 login 时能起新线程
                            writing = false
                            dirty = false
                            null
                        } else if (dirty) {
                            dirty = false
                            persistGeneration
                        } else {
                            writing = false
                            null
                        }
                    }
                    if (generation == null) return@Thread
                    persistSync(generation)
                }
            } catch (_: Throwable) {
                synchronized(persistLock) { writing = false }
            }
        }.also { it.isDaemon = true; it.name = "CookieJar-Persist" }.start()
    }

    private fun persistSync(generation: Long) {
        val snapshot: List<Cookie> = lock.read {
            byDomain.values.flatMap { it.toList() }
        }
        // TSV 行格式防线：字段含 \t/\n/\r 的 cookie 无法无损序列化（写盘会错位 / 拆行，重载时
        // 静默丢失或读出脏值），跳过持久化、仅内存生效。真实 jwc/CAS cookie 是 token 字符集，
        // 正常永远不会命中；这是对服务端异常输出的兜底。
        val text = snapshot
            .filter { c -> listOf(c.name, c.value, c.domain, c.path).none { it.contains('\t') || it.contains('\n') || it.contains('\r') } }
            .joinToString("\n") { serializeCookieLine(it) }
        synchronized(persistLock) {
            // 二次校验：临门一脚发现已被 clearAll，立即放弃，不要把刚清的快照写回去
            if (stopped || generation != persistGeneration) return
            runCatching {
                // 先写 tmp 再原子 rename，避免断电留下截断文件
                tmpFile.writeText(text)
                // File.renameTo 在 Android(POSIX) 同卷下是原子的；目标存在时大多数 FS 也能覆盖，
                // 失败再降级到先删后改。
                if (!tmpFile.renameTo(cookieFile)) {
                    cookieFile.delete()
                    tmpFile.renameTo(cookieFile)
                }
            }
        }
    }

    private fun serializeCookieLine(c: Cookie): String =
        listOf(
            c.name,
            c.value,
            c.domain,
            c.path,
            c.expiresAt.toString(),
            c.secure.toString(),
            c.httpOnly.toString(),
            c.hostOnly.toString(),
        ).joinToString("\t")

    private fun parseCookieLine(line: String): Cookie? {
        val p = line.split("\t")
        if (p.size < 8) return null
        return runCatching {
            Cookie.Builder()
                .name(p[0])
                .value(p[1])
                .path(p[3])
                .expiresAt(p[4].toLong())
                .apply {
                    if (p[7].toBoolean()) hostOnlyDomain(p[2]) else domain(p[2])
                    if (p[5].toBoolean()) secure()
                    if (p[6].toBoolean()) httpOnly()
                }
                .build()
        }.getOrNull()
    }
}

/**
 * 是否属于 `*.jxnu.edu.cn`。Cookie.domain 在 OkHttp 已 normalize 不带前导点，但服务器
 * Set-Cookie 时可能带；URL.host 不会带。两边都 trim 一次稳妥。
 */
private fun String.isJxnuHost(): Boolean {
    val h = trimStart('.').lowercase()
    return h == JxnuUrls.ROOT_DOMAIN || h.endsWith("." + JxnuUrls.ROOT_DOMAIN)
}

/**
 * 这条 cookie 是否归属于 [host]（用于 [PersistentCookieJar.clearForHost]）—— 只看 host/domain
 * 关系，**不**看 path / secure / httpOnly / expiresAt。
 *
 * RFC 6265 §5.1.3：
 *  - hostOnly cookie：domain 精确等于 host
 *  - domain cookie：host 与 domain 相同，或 host 以 ".$domain" 结尾
 *
 * Pure function，便于单测。
 */
internal fun cookieAffectsHost(cookie: Cookie, host: String): Boolean {
    val target = host.trimStart('.').lowercase()
    val cd = cookie.domain.trimStart('.').lowercase()
    return if (cookie.hostOnly) {
        cd == target
    } else {
        target == cd || target.endsWith(".$cd")
    }
}
