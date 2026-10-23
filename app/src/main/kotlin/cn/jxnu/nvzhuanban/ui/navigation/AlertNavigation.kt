package cn.jxnu.nvzhuanban.ui.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.work.AlertNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知点击 → 目标页面的**一次性**摆渡。
 *
 * 通知的 PendingIntent 只能带一个字符串 extra，而 Compose Navigation 的 NavHost 在
 * MainActivity.onCreate 时还没组合出来 —— 所以不在 Activity 里直接 navigate，而是把目标
 * 路由暂存在这里，由 [AppNav] 在 NavHost 就绪后消费一次。
 *
 * 只保留最新一条：连点两条通知时以最后一条为准，不会排队跳两次。
 */
object AlertNavigation {

    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    /** Activity 每次 onNewIntent / onCreate 都调一次；没有路由 extra 时什么都不做。 */
    fun requestFrom(intent: Intent?) {
        val route = intent?.getStringExtra(AlertNotifier.EXTRA_ROUTE) ?: return
        // 取走后立刻清掉 extra：Activity 重建（旋转 / 进程恢复）时 setIntent 的旧 Intent
        // 会被重新读一次，不清就会在每轮重建后再跳一次同样的页面。
        intent.removeExtra(AlertNotifier.EXTRA_ROUTE)
        _pendingRoute.value = route
    }

    fun consume() {
        _pendingRoute.value = null
    }
}

/** 通知跳转只取决于当前会话与当前页面，不能使用首次启动时固定的 startDestination。 */
internal fun alertDestination(route: String, authState: AuthState, currentRoute: String?): String? =
    route.takeIf {
        authState is AuthState.LoggedIn && currentRoute != null &&
            currentRoute != Routes.LOGIN && currentRoute != route
    }

@Composable
internal fun AlertNavigationEffect(nav: NavHostController, authState: AuthState) {
    val pendingRoute by AlertNavigation.pendingRoute.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    LaunchedEffect(pendingRoute, authState, currentRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        // 首次组合时先等导航图就绪；登录页收到的通知仍按原约定丢弃。
        if (currentRoute == null) return@LaunchedEffect
        AlertNavigation.consume()
        alertDestination(route, authState, currentRoute)?.let { nav.navigate(it) }
    }
}
