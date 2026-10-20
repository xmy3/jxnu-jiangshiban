package cn.jxnu.nvzhuanban.ui.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小组件「准点刷新」相关的公开入口：系统特殊权限状态 + 主动重绘。
 *
 * 背景：widget 靠 [WidgetUpdateScheduler] 排的闹钟在上下课那一刻重绘。Android 12+
 * 排**精确**闹钟需要 `SCHEDULE_EXACT_ALARM` 特殊权限（系统设置里叫「闹钟与提醒」），
 * 而 Android 14+ 起该权限默认不授予 —— 没授予时系统会合并推迟 inexact 闹钟，
 * 用户看到的就是「到点了还显示上一节」。这里的入口负责把状态暴露给 UI，并在授予后立刻重排。
 */
enum class WidgetExactAlarmState {
    /** Android 11 及以下：精确闹钟不需要权限，无需任何提示。 */
    NotRequired,

    /** 已授予「闹钟与提醒」：节次边界准点重绘。 */
    Granted,

    /** 未授予：唤醒会被系统合并推迟，需要引导用户去系统设置打开。 */
    Missing,
}

/** 读取当前「闹钟与提醒」权限状态。UI 在 onResume 重新读，用户从系统设置返回即可自愈。 */
fun widgetExactAlarmState(context: Context): WidgetExactAlarmState = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> WidgetExactAlarmState.NotRequired
    WidgetUpdateScheduler.canScheduleExactAlarms(context) -> WidgetExactAlarmState.Granted
    else -> WidgetExactAlarmState.Missing
}

/**
 * 打开系统「闹钟与提醒」授权页。返回 false 表示没找到入口（精简 ROM），调用方给个提示。
 */
fun openWidgetExactAlarmSettings(context: Context): Boolean =
    WidgetUpdateScheduler.openExactAlarmSettings(context)

/**
 * 立刻重绘所有已添加的「今日课表」小组件；一个都没添加时什么都不做。
 *
 * 调用点都该在 IO/后台线程：轻则一次 Glance 渲染（读快照文件），重则触发重新组合。
 * 这条路径同时承担三件事：
 *  - 冷启动 / 用户从系统设置授权回来后，让桌面立刻反映「此刻」而不是等下一轮闹钟
 *  - 重绘时 [TodayScheduleWidget.provideGlance] 会按新的权限状态重排唤醒槽
 *  - 国产 ROM 吞掉闹钟广播后，用户打开一次 App 就能把 widget 拉回正确状态
 */
suspend fun refreshTodayScheduleWidgets(context: Context) {
    withContext(Dispatchers.IO) {
        runCatching {
            // 只通知已经被用户添加到桌面的 widget，避免没有 widget 时报错
            val manager = GlanceAppWidgetManager(context.applicationContext)
            if (manager.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                TodayScheduleWidget().updateAll(context.applicationContext)
            }
        }
    }
}
