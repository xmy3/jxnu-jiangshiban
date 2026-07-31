package cn.jxnu.nvzhuanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.ui.navigation.AppNav
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen 必须在 super.onCreate 之前 install，否则 manifest 主题不被识别
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 等 Application 启动的会话恢复任务完成再放走 splash —— 但**最多等 [SPLASH_MAX_WAIT_MS]**。
        // 任务在 NvzhuanbanApp.onCreate 已经在 Dispatchers.IO 里启动；
        // 用 lifecycleScope 而非裸 CoroutineScope，Activity 销毁时自动取消，避免协程泄漏。
        //
        // 为什么要兜底超时：tryRestoreSession 内部至少要发 1 个 HTTP 请求验 cookie + 1 个 HTTP 请求
        // 拉用户首页；cookie 过期还要走整段 CAS（4~5 次请求）。网络稍差时 splash 会卡 2~5s，
        // 从 widget 点进来时尤其难受。超时放行后 AppNav 会自行显示 loading 占位继续等
        // sessionRestore（见 AppNav 顶部的 produceState 块），完成后再首次组合 NavHost。
        val app = application as NvzhuanbanApp
        var isReady = app.sessionRestore.isCompleted
        splash.setKeepOnScreenCondition { !isReady }
        if (!isReady) {
            lifecycleScope.launch {
                // 完成快于 800ms → 立即放行；超 800ms → 兜底放行，sessionRestore 仍在 appScope 跑
                withTimeoutOrNull(SPLASH_MAX_WAIT_MS) { app.sessionRestore.await() }
                isReady = true
            }
        }

        enableEdgeToEdge()
        // 全局静音：禁用系统 View 点击/缩放等触发的提示音；震感由系统默认行为保留
        window.decorView.isSoundEffectsEnabled = false
        setContent {
            val themeMode by ThemePrefs.themeMode.collectAsState()
            val dynamicColor by ThemePrefs.dynamicColor.collectAsState()
            NvzhuanbanTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                AppNav()
            }
        }
    }

    private companion object {
        /** Splash 最多保持的毫秒数。超过此时长无论会话恢复是否完成都放行，由 AppNav 显示 loading 占位。 */
        const val SPLASH_MAX_WAIT_MS = 800L
    }
}
