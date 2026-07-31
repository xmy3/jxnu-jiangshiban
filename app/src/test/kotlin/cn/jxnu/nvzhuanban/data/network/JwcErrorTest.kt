package cn.jxnu.nvzhuanban.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class JwcErrorTest {
    @Test
    fun `maps structured errors to user messages`() {
        assertEquals("登录已过期，请重新登录", JwcError.SessionExpired.toUserMessage())
        assertEquals("教务系统返回异常（HTTP 500），可能正在维护", JwcError.Server(500).toUserMessage())
        assertEquals("教务系统跳转到了异常地址：example.com", JwcError.UnexpectedRedirect("example.com").toUserMessage())
    }

    @Test
    fun `maps common network throwables`() {
        assertEquals("网络连接失败，请检查网络后重试", SocketTimeoutException("timeout").toUserMessage())
        assertEquals("网络连接失败，请检查网络后重试", UnknownHostException("dns").toUserMessage())
        assertEquals("安全连接失败，请稍后重试", SSLException("tls").toUserMessage())
    }
}
