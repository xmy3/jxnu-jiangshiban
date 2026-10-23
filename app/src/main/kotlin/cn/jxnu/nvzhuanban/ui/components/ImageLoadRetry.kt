package cn.jxnu.nvzhuanban.ui.components

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private val IMAGE_RETRY_DELAYS_MS = longArrayOf(1_000, 3_000)

/** 图片只对瞬时故障补两次请求；取消、无权限、失效链接和无法解码的内容不循环请求。 */
internal suspend fun <T> loadImageWithRetry(load: suspend () -> T): Result<T> {
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            val image = load()
            currentCoroutineContext().ensureActive()
            return Result.success(image)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (!failure.isRetryableImageFailure() || retries == IMAGE_RETRY_DELAYS_MS.size) {
                return Result.failure(failure)
            }
            delay(IMAGE_RETRY_DELAYS_MS[retries++])
        }
    }
}

internal fun Throwable.isRetryableImageFailure(): Boolean = when (this) {
    is CancellationException -> false
    // JwcException 本身继承 IOException，必须先分类，避免 404 / AuthRejected 被当成断网。
    is JwcException -> when (val reason = error) {
        is JwcError.Network -> true
        is JwcError.Server -> reason.code == 408 || reason.code == 429 || reason.code in 500..599
        else -> false
    }
    is SSLException, is ProtocolException -> false
    is IOException -> true
    else -> false
}
