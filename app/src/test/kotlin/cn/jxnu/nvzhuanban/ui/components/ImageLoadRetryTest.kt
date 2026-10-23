package cn.jxnu.nvzhuanban.ui.components

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageLoadRetryTest {
    @Test
    fun `temporary offline image succeeds after network recovers`() = runTest {
        val attemptsAt = mutableListOf<Long>()
        val image = Any()
        val result = loadImageWithRetry {
            attemptsAt += testScheduler.currentTime
            if (attemptsAt.size < 3) throw IOException("offline")
            image
        }

        assertSame(image, result.getOrThrow())
        assertEquals(listOf(0L, 1_000L, 4_000L), attemptsAt)
    }

    @Test
    fun `persistent network failure stops after two retries`() = runTest {
        var attempts = 0
        val failure = JwcException(JwcError.Network())
        val result = loadImageWithRetry<Unit> {
            attempts++
            throw failure
        }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(3, attempts)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test
    fun `unavailable servers may recover but missing or forbidden images do not retry`() = runTest {
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            var attempts = 0
            val result = loadImageWithRetry {
                if (++attempts == 1) throw JwcException(JwcError.Server(code))
                "image"
            }
            assertEquals("image", result.getOrThrow())
            assertEquals(2, attempts)
        }
        for (code in listOf(400, 401, 403, 404, 410)) {
            var attempts = 0
            val result = loadImageWithRetry<Unit> {
                attempts++
                throw JwcException(JwcError.Server(code))
            }
            assertTrue(result.isFailure)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `rejected auth invalid content and TLS configuration errors never retry`() = runTest {
        val failures = listOf(
            JwcException(JwcError.SessionExpired),
            JwcException(JwcError.Decode()),
            JwcException(JwcError.EmptyResponse),
            JwcException(JwcError.Ssl()),
            JwcException(JwcError.UnexpectedRedirect("example.invalid")),
            SSLHandshakeException("certificate rejected"),
            ProtocolException("unsupported protocol"),
            IllegalArgumentException("invalid image URL"),
        )
        for (failure in failures) {
            var attempts = 0
            val result = loadImageWithRetry<Unit> {
                attempts++
                throw failure
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, attempts)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `undecodable response does not request the same bytes repeatedly`() = runTest {
        var attempts = 0
        val result = loadImageWithRetry<Any?> { attempts++; null }
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
        assertEquals(1, attempts)
    }

    @Test
    fun `loader cancellation is propagated instead of becoming an image failure`() = runTest {
        val cancellation = CancellationException("left screen")
        try {
            loadImageWithRetry<Unit> { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `leaving screen during backoff cancels subsequent requests`() = runTest {
        var attempts = 0
        val loading = launch {
            loadImageWithRetry<Unit> {
                attempts++
                throw IOException("offline")
            }
            fail("Cancelled load must not produce a terminal result")
        }
        runCurrent()
        assertEquals(1, attempts)
        loading.cancelAndJoin()
        advanceUntilIdle()
        assertTrue(loading.isCancelled)
        assertEquals(1, attempts)
    }
}
