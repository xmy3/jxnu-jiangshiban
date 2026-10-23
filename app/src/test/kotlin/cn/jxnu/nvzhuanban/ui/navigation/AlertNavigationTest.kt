package cn.jxnu.nvzhuanban.ui.navigation

import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertNavigationTest {
    private val loggedIn = AuthState.LoggedIn(UserProfile("20240001", "测试", "", "", "", 2024))

    @Test
    fun `notification destinations become available after manual login`() {
        for (route in listOf(Routes.GRADES, Routes.EXAMS)) {
            assertNull(alertDestination(route, AuthState.LoggedOut, Routes.LOGIN))
            assertEquals(route, alertDestination(route, loggedIn, Routes.SCHEDULE))
        }
    }

    @Test
    fun `expired and logging out sessions cannot open a protected destination`() {
        for (state in listOf(AuthState.LoggedOut, AuthState.Loading, AuthState.Error("过期"))) {
            assertNull(alertDestination(Routes.EXAMS, state, Routes.PROFILE))
        }
    }

    @Test
    fun `login page missing graph and current destination do not trigger navigation`() {
        for (route in listOf(null, Routes.LOGIN, Routes.EXAMS)) {
            assertNull(alertDestination(Routes.EXAMS, loggedIn, route))
        }
    }
}
