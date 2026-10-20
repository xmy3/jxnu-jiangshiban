package cn.jxnu.nvzhuanban

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.ui.navigation.AlertNavigation
import cn.jxnu.nvzhuanban.ui.navigation.AppNav
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme
import cn.jxnu.nvzhuanban.ui.widget.refreshTodayScheduleWidgets
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen 必须在 super.onCreate 之前 install，否则 manifest 主题不被识别
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 从「出分 / 考试变更」通知点进来：先把目标路由存起来，等 AppNav 的 NavHost 就绪再跳
        AlertNavigation.requestFrom(intent)

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

        // 冷启动顺手重绘一次桌面小组件：国产 ROM 常把后台闹钟广播整个吞掉，用户
        // 「打开 App 再回桌面」时不该还看到上一节课的高亮。重绘同时会按当前的
        // 「闹钟与提醒」权限状态重排唤醒槽。没添加过 widget 时内部直接返回。
        lifecycleScope.launch { refreshTodayScheduleWidgets(this@MainActivity) }
    }

    /**
     * App 已经开着时点通知：manifest 里 MainActivity 是 `singleTop`，走这里而不是新建实例，
     * 否则点一次通知就在回退栈上多一层，返回要按两次才退出。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AlertNavigation.requestFrom(intent)
    }

    private companion object {
        /** Splash 最多保持的毫秒数。超过此时长无论会话恢复是否完成都放行，由 AppNav 显示 loading 占位。 */
        const val SPLASH_MAX_WAIT_MS = 800L
    }
}
