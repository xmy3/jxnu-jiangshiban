package cn.jxnu.nvzhuanban.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 当前桌面是否支持 pin 请求；不支持时 UI 直接展示手动添加引导，不显示一键按钮。 */
fun isPinTodayScheduleWidgetSupported(context: Context): Boolean =
    context.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

/** 桌面上已添加的「今日课表」小组件个数，用于「已有一个，还要再加吗」的二次确认。 */
fun pinnedTodayScheduleWidgetCount(context: Context): Int {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return 0
    val provider = ComponentName(context, TodayScheduleWidgetReceiver::class.java)
    return runCatching { manager.getAppWidgetIds(provider).size }.getOrDefault(0)
}

/**
 * 「一键添加桌面小组件」：请求当前桌面弹出系统的添加确认窗
 * （[AppWidgetManager.requestPinAppWidget]，API 26+，与 minSdk 持平，无需版本分支）。
 *
 * 返回 true 只代表桌面**受理**了请求，不保证确认窗真的弹出——小米 / 华为等桌面在
 * 未授予本应用「桌面快捷方式」权限时会静默吞掉。真正添加成功的信号是
 * [WidgetPinResultReceiver] 收到桌面的回执（successCount 自增 + Toast），调用方 UI
 * 应据此收尾；收不到回执时要保持「去开启权限 + 手动添加」引导可见，不能拿 true 当成功。
 */
fun requestPinTodayScheduleWidget(context: Context): Boolean {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
    if (!manager.isRequestPinAppWidgetSupported) return false
    val provider = ComponentName(context, TodayScheduleWidgetReceiver::class.java)
    // 桌面确认添加成功后回发的 PendingIntent。IMMUTABLE 会丢弃桌面 fill-in 的
    // EXTRA_APPWIDGET_ID，但我们只关心「成功」这个事件本身，用不到 id。
    val successCallback = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetPinResultReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    // requestPinAppWidget 在 app 处于后台等场合会抛 IllegalStateException，按「不支持」兜住
    return runCatching { manager.requestPinAppWidget(provider, null, successCallback) }.getOrDefault(false)
}

/**
 * 打开能授予「桌面快捷方式」权限的系统设置页——pin 请求被静默吞掉时的自救入口。
 * 小米 / 红米（MIUI / HyperOS，Build.MANUFACTURER 都是 Xiaomi）优先直达安全中心的
 * 应用权限编辑页（历史组件名，新系统可能移除，失败自动退回）；其余机型进应用详情页，
 * 各家的「权限管理 → 桌面快捷方式」入口都在里面。
 */
fun openShortcutPermissionSettings(context: Context) {
    if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
        val miuiPermEditor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            .putExtra("extra_pkgname", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(miuiPermEditor) }.isSuccess) return
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(appDetails) }
}

/**
 * 桌面「添加成功」的回执 receiver（[requestPinTodayScheduleWidget] 的 successCallback）。
 * 只由本应用自己的 PendingIntent 显式触发，无 intent-filter，manifest 里 exported=false。
 * 收到即 Toast 一句成功反馈，并自增 [successCount] 让正在展示的引导弹窗自动关闭。
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        counter.value += 1
        Toast.makeText(context.applicationContext, "已添加到桌面", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val counter = MutableStateFlow(0)

        /** 进程内单调递增的「添加成功」次数；UI 记基线后订阅增量即可感知本次请求的结果。 */
        val successCount: StateFlow<Int> get() = counter
    }
}
