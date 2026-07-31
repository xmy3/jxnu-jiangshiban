package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody

/**
 * Small facade for business requests to jwc.jxnu.edu.cn.
 *
 * 普通的 [getHtml] / [postHtml] / [getBytes] 是阻塞同步函数，应在 IO 上调用。
 *
 * 带 `Auth` 后缀的 suspend 版本会在 SessionExpired 时自动调 [SessionRecovery] 静默
 * 重登并重放一次原请求。Repository 默认应该用这些，除非业务页是公开的（不带 cookie
 * 也能访问，如通知列表）。
 */
object JwcClient {
    fun getHtml(url: String, emptyMessage: String): String {
        val req = Request.Builder().url(url).get().build()
        return executeHtml(req, emptyMessage)
    }

    fun postHtml(url: String, body: RequestBody, emptyMessage: String): String {
        val req = Request.Builder().url(url).post(body).build()
        return executeHtml(req, emptyMessage)
    }

    fun getBytes(url: String, emptyMessage: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        return JxnuHttpClient.get().client.newCall(req).execute().use { resp ->
            JwcResponseGuard.validateJwcResponse(resp)
            val bytes = resp.body.bytes().takeIf { it.isNotEmpty() }
                ?: throw JwcException(JwcError.EmptyResponse, emptyMessage)
            // 半死会话对图片端点也可能回 200 + 登录/占位 HTML（不重定向），或「访问受限」纯文本
            // 短 stub（不像 HTML，2026-07 实测形态），validateJwcResponse 放行后 BitmapFactory
            // 解不出图 → 静默破图、永不自愈。这里对「看起来是 HTML 或极短」的字节补一次嗅探，
            // 把它转成 SessionExpired 让 getBytesAuth 能触发重登重放。
            if (bytes.looksLikeHtml() || bytes.size <= JwcResponseGuard.MAX_STUB_BYTES) {
                JwcResponseGuard.assertNotLoginPage(bytes)
            }
            bytes
        }
    }

    /** 带"会话过期 → 静默重登 → 重放"的 GET 包装。 */
    suspend fun getHtmlAuth(url: String, emptyMessage: String): String =
        runWithSessionRecovery { getHtml(url, emptyMessage) }

    /** 带"会话过期 → 静默重登 → 重放"的 POST 包装。 */
    suspend fun postHtmlAuth(url: String, body: RequestBody, emptyMessage: String): String =
        runWithSessionRecovery { postHtml(url, body, emptyMessage) }

    /**
     * 带"会话过期 → 静默重登 → 重放"的二进制 GET 包装（头像等）。
     *
     * [notifyOnAuthRejected] = false：图片是**装饰性**请求，即便 reauth 判定 AuthRejected
     * 也不该把用户从正在看的页面踢到登录页——一张头像 / 师生照片加载失败顶多显示占位。
     * 真正决定去留的是那些承载正文的 *HtmlAuth 请求。
     */
    suspend fun getBytesAuth(url: String, emptyMessage: String): ByteArray =
        runWithSessionRecovery(notifyOnAuthRejected = false) { getBytes(url, emptyMessage) }

    private fun executeHtml(req: Request, emptyMessage: String): String =
        JxnuHttpClient.get().client.newCall(req).execute().use { resp ->
            JwcResponseGuard.readJwcHtml(resp, emptyMessage)
        }

    /**
     * 阻塞调用包成 IO 协程；捕获 SessionExpired 后按 [ReauthOutcome] 三态处理。
     * 详见 [withSessionRecovery] 的契约。
     */
    private suspend inline fun <T> runWithSessionRecovery(
        notifyOnAuthRejected: Boolean = true,
        crossinline block: () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            withSessionRecovery(
                reauth = { SessionRecovery.reauth() },
                notifyExpired = { SessionEvents.notifyExpired() },
                notifyOnAuthRejected = notifyOnAuthRejected,
                block = { block() },
            )
        }
}

/**
 * "会话过期 → reauth 一次 → 重放一次" 的纯容错骨架，从 [JwcClient] 的 runWithSessionRecovery
 * 抽出以便单测：不含 IO dispatcher / 具体单例依赖，[reauth] 与 [notifyExpired] 由调用方注入。
 *
 * 行为契约（由 JwcClientSessionRecoveryTest 锁定）：
 *  - [block] 成功 → 直接返回，绝不 reauth / notify
 *  - [block] 抛非 SessionExpired 的 [JwcException] → 原样透传，不 reauth
 *  - SessionExpired 且 reauth = [ReauthOutcome.AuthRejected] → [notifyExpired]（除非
 *    [notifyOnAuthRejected]=false）并透传原异常
 *  - SessionExpired 且 reauth = [ReauthOutcome.Transient] → **不 notify**，把异常转成
 *    [JwcError.Network]（中性可重试文案，绝不透传「登录已过期」误导用户），让 UI 走可重试错误态
 *  - SessionExpired 且 reauth = [ReauthOutcome.Success] → 重放一次 [block]；成功则返回其结果
 *  - 重放仍抛 SessionExpired → [notifyExpired]（同样受 [notifyOnAuthRejected] 约束）并透传（**不再二次 reauth**）
 *  - 重放抛其它异常 → 原样透传，不 notify
 */
internal suspend fun <T> withSessionRecovery(
    reauth: suspend () -> ReauthOutcome,
    notifyExpired: () -> Unit,
    notifyOnAuthRejected: Boolean = true,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: JwcException) {
    if (e.error != JwcError.SessionExpired) throw e
    when (reauth()) {
        ReauthOutcome.AuthRejected -> {
            if (notifyOnAuthRejected) notifyExpired()
            throw e
        }
        ReauthOutcome.Transient -> {
            // 瞬时失败：不踢人，但也别把「登录已过期」透传给 UI（会误导用户去手动重登）。
            // 换成中性的可重试网络错误，配合 UiStateViewModel.refresh「失败静默保留旧数据」。
            throw JwcException(JwcError.Network(), cause = e)
        }
        ReauthOutcome.Success -> try {
            block()
        } catch (e2: JwcException) {
            if (e2.error == JwcError.SessionExpired && notifyOnAuthRejected) notifyExpired()
            throw e2
        }
    }
}

/** 前若干字节看起来像 HTML（`<!doctype`/`<html`/`<!--`/`<form` 等起手）。 */
private fun ByteArray.looksLikeHtml(): Boolean {
    val prefix = take(256).toByteArray().toString(Charsets.ISO_8859_1).trimStart().lowercase()
    return prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
        prefix.startsWith("<!--") || prefix.startsWith("<?xml") ||
        prefix.startsWith("<form") || prefix.contains("<html")
}
