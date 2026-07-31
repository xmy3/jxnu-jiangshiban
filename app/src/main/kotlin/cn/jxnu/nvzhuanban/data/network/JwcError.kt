package cn.jxnu.nvzhuanban.data.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface JwcError {
    data object SessionExpired : JwcError
    data object EmptyResponse : JwcError
    data class Server(val code: Int) : JwcError
    data class UnexpectedRedirect(val host: String) : JwcError
    data class Network(val detail: String? = null) : JwcError
    data class Ssl(val detail: String? = null) : JwcError
    data class Decode(val detail: String? = null) : JwcError
    data class Unknown(val detail: String? = null) : JwcError
}

class JwcException(
    val error: JwcError,
    override val message: String = error.toUserMessage(),
    cause: Throwable? = null,
) : IOException(message, cause)

fun JwcError.toUserMessage(): String = when (this) {
    JwcError.SessionExpired -> "登录已过期，请重新登录"
    JwcError.EmptyResponse -> "教务系统返回了空页面，请稍后重试"
    is JwcError.Server -> "教务系统返回异常（HTTP $code），可能正在维护"
    is JwcError.UnexpectedRedirect -> "教务系统跳转到了异常地址：$host"
    is JwcError.Network -> "网络连接失败，请检查网络后重试"
    is JwcError.Ssl -> "安全连接失败，请稍后重试"
    is JwcError.Decode -> "页面内容解析失败，教务系统可能已更新"
    is JwcError.Unknown -> detail ?: "请求失败，请稍后重试"
}

fun Throwable.toUserMessage(fallback: String = "加载失败"): String = when (this) {
    // JwcException.message 非空且默认就是 error.toUserMessage()；调用方传了定制文案
    // （如 GradePage 壳页的「请下拉刷新重试」引导）时优先展示定制文案。
    is JwcException -> message
    is SocketTimeoutException -> JwcError.Network(message).toUserMessage()
    is UnknownHostException -> JwcError.Network(message).toUserMessage()
    is SSLException -> JwcError.Ssl(message).toUserMessage()
    // 兜底透传是白名单制：裸 IOException（ConnectException 的 "Failed to connect to …:443"、
    // SocketException…）的 message 是英文系统串，不能上屏，一律归网络失败文案；
    // 其余异常仅当 message 含中文（自己人写的用户文案，如 LoginException）才透传。
    is IOException -> JwcError.Network(message).toUserMessage()
    else -> message?.takeIf { m -> m.any { it in '一'..'鿿' } } ?: fallback
}
