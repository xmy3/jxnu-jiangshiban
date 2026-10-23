package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.work.canRunAlertCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginCompletionTest {
    private val user = UserProfile("2026000000", "测试", "", "", "", 2026)

    @Test
    fun `navigation cancellation after publishing the session does not lose scheduling`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)
        val scheduling = CompletableDeferred<Unit>()
        val resumeScheduling = CompletableDeferred<Unit>()
        var scheduled = false
        val login = async {
            completeLogin(
                state = state,
                loadProfile = { user },
                scheduleAlerts = {
                    scheduling.complete(Unit)
                    resumeScheduling.await()
                    scheduled = true
                },
            )
        }
        scheduling.await()
        assertTrue(state.value is AuthState.LoggedIn)
        login.cancel()
        resumeScheduling.complete(Unit)
        login.join()
        assertTrue(scheduled)
    }

    @Test
    fun `cancellation before profile completes does not publish a session or schedule alerts`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)
        val profile = CompletableDeferred<UserProfile>()
        var scheduled = false
        val login = async {
            completeLogin(
                state = state,
                loadProfile = {
                    runCatching { profile.await() }.getOrDefault(user)
                },
                scheduleAlerts = { scheduled = true },
            )
        }
        runCurrent()
        login.cancel()
        login.join()
        assertEquals(AuthState.Loading, state.value)
        assertFalse(scheduled)
    }

    @Test
    fun `manual login waits for profile and publishes session before immediate worker runs`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)
        val startup = CompletableDeferred(false)
        val profile = CompletableDeferred<UserProfile>()
        var scheduled = false
        var checkAllowed = false
        val login = async {
            completeLogin(state, loadProfile = { profile.await() }, scheduleAlerts = {
                scheduled = true
                checkAllowed = canRunAlertCheck(startup) { state.value }
            })
        }
        runCurrent()
        assertFalse("Slow profile fetch must not launch a check with Loading state", scheduled)
        assertEquals(AuthState.Loading, state.value)
        profile.complete(user)
        login.await()
        assertTrue(scheduled)
        assertTrue("Worker must accept the session even if startup restore failed", checkAllowed)
    }
}
