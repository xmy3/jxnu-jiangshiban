package cn.jxnu.nvzhuanban.data.network

import cn.jxnu.nvzhuanban.data.model.AppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调用 GitHub REST API 拉 `repos/.../releases/latest`。
 *
 * 关键约束：**不复用 [JxnuHttpClient] 的 OkHttp 实例**。那个 client 装了
 * [PersistentCookieJar] —— 全局生效、无 host 过滤 —— api.github.com 的 Set-Cookie
 * 会被落到 `filesDir/jxnu_cookies.tsv` 永久存留，污染 CAS 登录 cookie 文件，下次 CAS
 * 重定向时甚至可能把 GitHub cookie 当成 jwc 的发出去。
 *
 * 因此这里用独立 [OkHttpClient] + [CookieJar.NO_COOKIES]。dispatcher/connectionPool 也
 * 不共享 —— OkHttp 默认值对每小时几次的更新检查完全够用，多一份内存换 zero coupling。
 *
 * GitHub API 要求：
 *  - **User-Agent 必须设**（否则 403），固定 App 名即可满足（GitHub 只要求非空可识别 UA；
 *    本项目未启用 BuildConfig，不为 UA 引入版本号读取链）
 *  - `Accept: application/vnd.github+json` 是当前稳定版媒体类型
 *  - `X-GitHub-Api-Version` 是 2022-11-28，固定
 *
 * 未认证速率限制：60 req/h —— App 启动 24h 限频 + 手动检查远远够用。
 *
 * `/releases/latest` 默认只返回非 prerelease 的 release，所以拿到 prerelease 是异常情况，
 * [SemVer.fromString] 会因 `-rc1` 后缀返回 null，调用方静默不提示。
 */
open class GitHubUpdateClient(
    private val userAgent: String = "nvzhuanban-android",
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 拉一次 latest release。
     * @throws GithubException 网络 / HTTP / 解析任意环节失败
     */
    open suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        val body: String = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw GithubException(GithubError.NotFound)
                    !response.isSuccessful -> throw GithubException(GithubError.HttpError(response.code))
                    else -> response.body?.string()
                        ?: throw GithubException(GithubError.Parse("响应体为空"))
                }
            }
        } catch (e: GithubException) {
            throw e
        } catch (e: IOException) {
            throw GithubException(GithubError.Network(e.message), cause = e)
        }
        parse(body)
    }

    /**
     * 把 GitHub release JSON 映射到 [AppRelease]。
     *
     * 提为 `internal` 是为了让 JVM 单测可以脱离网络直接喂 fixture JSON 验证字段抽取，
     * 不是因为外部调用方需要。
     */
    internal fun parse(json: String): AppRelease = try {
        val obj = JSONObject(json)
        val tagName = obj.optString("tag_name", "")
        if (tagName.isBlank()) throw GithubException(GithubError.Parse("缺少 tag_name"))
        AppRelease(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            htmlUrl = obj.optString("html_url", ""),
            body = obj.optString("body", ""),
            publishedAt = obj.optString("published_at", ""),
        )
    } catch (e: JSONException) {
        throw GithubException(GithubError.Parse(e.message), cause = e)
    }

    companion object {
        /** `xmy3/jxnu-jiangshiban` 仓库的 latest release endpoint。 */
        const val API_URL = "https://api.github.com/repos/xmy3/jxnu-jiangshiban/releases/latest"
    }
}
