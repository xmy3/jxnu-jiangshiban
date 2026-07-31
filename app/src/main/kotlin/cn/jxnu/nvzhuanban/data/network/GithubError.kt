package cn.jxnu.nvzhuanban.data.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * GitHub Update 通道的错误分类。
 *
 * 跟 [JwcError] 平行存在 —— 不复用 jwc 文案，否则会出现
 * "教务系统返回异常 (HTTP 404)" 这种语义错位的错误信息。
 */
sealed interface GithubError {
    /** `/releases/latest` 返回 404：仓库里还没 publish 任何 release。 */
    data object NotFound : GithubError
    data class HttpError(val code: Int) : GithubError
    data class Network(val detail: String? = null) : GithubError
    data class Parse(val detail: String? = null) : GithubError
}

class GithubException(
    val error: GithubError,
    override val message: String = error.toUpdateMessage(),
    cause: Throwable? = null,
) : IOException(message, cause)

fun GithubError.toUpdateMessage(): String = when (this) {
    GithubError.NotFound -> "暂未发布任何版本"
    is GithubError.HttpError -> "GitHub 返回异常（HTTP $code）"
    is GithubError.Network -> "网络连接失败，请检查网络后重试"
    is GithubError.Parse -> "响应解析失败，请稍后重试"
}

/**
 * `Throwable.toUserMessage` 的 update-channel 版本。
 *
 * 跟 jwc 的 `toUserMessage` 用一样的策略：把常见的网络异常归并到自定义错误类型，
 * 让 UI 层得到 i18n 后的中文 message，而不是 OkHttp 的英文原文。
 */
fun Throwable.toUpdateMessage(fallback: String = "检查更新失败"): String = when (this) {
    is GithubException -> error.toUpdateMessage()
    is SocketTimeoutException -> GithubError.Network(message).toUpdateMessage()
    is UnknownHostException -> GithubError.Network(message).toUpdateMessage()
    is SSLException -> GithubError.Network(message).toUpdateMessage()
    is IOException -> message ?: GithubError.Network().toUpdateMessage()
    else -> message ?: fallback
}
