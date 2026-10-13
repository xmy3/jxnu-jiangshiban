package cn.jxnu.nvzhuanban.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Navigation saved back stacks with synthetic account data, without logging in. */
@RunWith(AndroidJUnit4::class)
class SessionNavigationInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun expiredSessionDiscardsSavedProfileAndAnnouncementViewModels() {
        verifySessionBoundary(logoutFromProfile = false)
    }

    @Test
    fun explicitLogoutDiscardsActiveProfileAndSavedAnnouncementViewModels() {
        verifySessionBoundary(logoutFromProfile = true)
    }

    private fun verifySessionBoundary(logoutFromProfile: Boolean) {
        lateinit var nav: NavHostController
        var account = "A"
        val created = mutableListOf<AccountViewModel>()
        compose.setContent {
            nav = rememberNavController()
            NavHost(
                navController = nav,
                startDestination = Routes.SCHEDULE,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.LOGIN) { Text("login") }
                composable(Routes.SCHEDULE) { Text("schedule") }
                listOf(Routes.PROFILE, Routes.ANNOUNCEMENT).forEach { route ->
                    composable(route) {
                        val model = viewModel { AccountViewModel(account).also(created::add) }
                        Text("$route:${model.account}")
                    }
                }
            }
        }

        fun selectTab(route: String) {
            compose.runOnIdle {
                nav.navigate(route) {
                    popUpTo(Routes.SCHEDULE) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            compose.waitForIdle()
        }

        selectTab(Routes.PROFILE)
        compose.onNodeWithText("profile:A").assertExists()
        selectTab(Routes.ANNOUNCEMENT)
        compose.onNodeWithText("announcement:A").assertExists()
        selectTab(if (logoutFromProfile) Routes.PROFILE else Routes.SCHEDULE)
        compose.runOnIdle {
            // Switching tabs within one session must retain the original ViewModels.
            assertEquals(2, created.size)
            assertTrue(created.none { it.cleared })
            nav.navigateToLogin()
        }
        compose.onNodeWithText("login").assertExists()
        compose.runOnIdle {
            assertTrue("All old tab ViewModels must be cleared", created.all { it.cleared })
            account = "B"
            nav.navigate(Routes.SCHEDULE) {
                popUpTo(Routes.LOGIN) { inclusive = true }
                launchSingleTop = true
            }
        }
        selectTab(Routes.PROFILE)
        compose.onNodeWithText("profile:B").assertExists()
        selectTab(Routes.ANNOUNCEMENT)
        compose.onNodeWithText("announcement:B").assertExists()
        compose.runOnIdle {
            assertEquals(4, created.size)
            assertFalse(created.last().cleared)
        }
    }

    private class AccountViewModel(val account: String) : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() { cleared = true }
    }
}
