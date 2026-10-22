package cn.jxnu.nvzhuanban.data.work

import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AlertSessionTest {
    private val loggedIn = AuthState.LoggedIn(UserProfile("2024050001", "张三", "", "", "", 2024))

    @Test
    fun `manual login after failed startup restore enables checks without restarting app`() = runTest {
        val restore = CompletableDeferred(false)
        var state: AuthState = AuthState.LoggedOut
        assertFalse(canRunAlertCheck(restore) { state })
        state = loggedIn
        assertTrue(canRunAlertCheck(restore) { state })
    }

    @Test
    fun `checks wait for startup but a later logout still blocks them`() = runTest {
        val restore = CompletableDeferred<Boolean>()
        var state: AuthState = AuthState.Loading
        val allowed = async(start = CoroutineStart.UNDISPATCHED) { canRunAlertCheck(restore) { state } }
        assertFalse(allowed.isCompleted)
        state = loggedIn
        restore.complete(true)
        assertTrue(allowed.await())
        state = AuthState.LoggedOut
        assertFalse(canRunAlertCheck(restore) { state })
    }

    @Test
    fun `cancelling a pending check does not turn cancellation into a successful check`() = runTest {
        val restore = CompletableDeferred<Boolean>()
        val allowed = async(start = CoroutineStart.UNDISPATCHED) { canRunAlertCheck(restore) { loggedIn } }
        allowed.cancel()
        allowed.join()
        assertTrue(allowed.isCancelled)
    }
}
