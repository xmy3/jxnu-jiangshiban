package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 全局 OkHttp 单例，启动时由 [cn.jxnu.nvzhuanban.NvzhuanbanApp] 调用 [init]。
 *
 * - 持久化 Cookie 通过 [PersistentCookieJar]
 * - 20MB 磁盘 HTTP 缓存（cacheDir/http），静态图片按 Last-Modified/ETag 协商命中
 * - 自动跟随重定向（CAS 登录后会跳 4-5 次跨域）
 * - 默认 UA 伪装成 Chrome，避免被某些反爬规则误伤
 * - 默认超时 15s/30s，长查询页可单独调整
 * - 仅 debug 包启用 BASIC 网络日志；release 包不写 URL，避免头像 URL 里的学号侧漏
 */
class JxnuHttpClient private constructor(
    val cookieJar: PersistentCookieJar,
    val client: OkHttpClient,
) {
    /**
     * 清空磁盘 HTTP 缓存。退出登录时调用 —— 上一用户的头像 / 师生照片响应是明文落盘的，
     * 不能跨账号残留。evictAll 是磁盘操作，切到 IO 线程执行。
     */
    suspend fun clearHttpCache() {
        withContext(Dispatchers.IO) {
            runCatching { client.cache?.evictAll() }
        }
    }

    companion object {
        // 江西师大教务系统 PC 端真实抓包用的 UA，移动端用 Chrome Mobile 也能登录，但保持一致风险更低
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

        @Volatile private var INSTANCE: JxnuHttpClient? = null

        fun init(context: Context): JxnuHttpClient {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): JxnuHttpClient = INSTANCE
            ?: error("JxnuHttpClient 尚未初始化，请在 Application.onCreate() 调用 JxnuHttpClient.init(this)")

        private fun build(appContext: Context): JxnuHttpClient {
            val cookieJar = PersistentCookieJar(appContext)

            val uaInterceptor = Interceptor { chain ->
                val req = chain.request()
                val needUa = req.header("User-Agent") == null
                val newReq = if (needUa) req.newBuilder().header("User-Agent", DEFAULT_UA).build() else req
                chain.proceed(newReq)
            }

            // 出站把 http://*.jxnu.edu.cn 升级到 https://。即便手抖留了 http URL，也走加密。
            val upgradeRequestInterceptor = Interceptor { chain ->
                val req = chain.request()
                val url = req.url
                if (url.scheme == "http" && url.host.endsWith(JxnuUrls.ROOT_DOMAIN)) {
                    val httpsReq = req.newBuilder()
                        .url(url.newBuilder().scheme("https").build())
                        .build()
                    chain.proceed(httpsReq)
                } else {
                    chain.proceed(req)
                }
            }

            // 服务端偶发 302 → http://。在 NetworkInterceptor 改 Location 头到 https，
            // 让 OkHttp 内部 follow-redirect 用 TLS 完成下一跳。
            val upgradeRedirectInterceptor = Interceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code in 300..399) {
                    val location = response.header("Location")
                    if (location != null && location.startsWith("http://")) {
                        val rewritten = location.replaceFirst("http://", "https://")
                        // 只重写指向 jxnu 域的；外站 http 链接维持原状交给上层判断
                        val hostPart = rewritten.removePrefix("https://").substringBefore('/')
                        if (hostPart.endsWith(JxnuUrls.ROOT_DOMAIN)) {
                            return@Interceptor response.newBuilder()
                                .header("Location", rewritten)
                                .build()
                        }
                    }
                }
                response
            }

            val builder = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                // 磁盘缓存只对带可缓存头的响应生效——jwc 的静态图片带 Last-Modified/ETag 能协商命中，
                // CAS/教务动态页不带缓存头（且多为带查询串的 GET / POST）不会被缓存，登录链路不受影响
                .cache(Cache(appContext.cacheDir.resolve("http"), 20L * 1024 * 1024))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(uaInterceptor)
                .addInterceptor(upgradeRequestInterceptor)
                .addNetworkInterceptor(upgradeRedirectInterceptor)

            if (appContext.isDebuggable()) {
                builder.addInterceptor(debugLoggingInterceptor())
            }

            val client = builder.build()

            return JxnuHttpClient(cookieJar, client)
        }

        private fun Context.isDebuggable(): Boolean =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        private fun debugLoggingInterceptor(): HttpLoggingInterceptor =
            HttpLoggingInterceptor { message ->
                android.util.Log.d("JxnuHttp", message.redactSensitiveUrlParts())
            }.apply {
                // BASIC：method/url/状态/耗时；不打印 headers/body，避免密码与 cookie 进入日志。
                level = HttpLoggingInterceptor.Level.BASIC
            }

        private fun String.redactSensitiveUrlParts(): String =
            replace(Regex("""(?i)(UserNum=)[^&\s]+"""), "$1<redacted>")
    }
}
