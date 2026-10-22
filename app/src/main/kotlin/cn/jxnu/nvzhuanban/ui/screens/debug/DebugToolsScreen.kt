package cn.jxnu.nvzhuanban.ui.screens.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.CookieSummary
import cn.jxnu.nvzhuanban.data.network.JxnuHttpClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.PersistentCookieJar
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import cn.jxnu.nvzhuanban.data.work.AlertScheduler
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.theme.AppShape
import cn.jxnu.nvzhuanban.ui.widget.refreshTodayScheduleWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开发者工具页（**仅供调试包使用**，入口由调用方（设置页）按 debuggable 控制）。
 *
 * 面向"抓教务网页数据"排障的四个面：运行环境、会话诊断、Cookie 调试、后台任务。
 *
 * 设计约束：
 *  - 屏幕上**永不出现 cookie value / token / 密码**：Cookie 只展示 host + name + 是否过期；
 *    需要原样搬迁登录态时走剪贴板导出，旁边明写"含登录凭据"。
 *  - 不新增网络调用：会话探针复用 [AuthRepository.refreshProfile]（内部自带 IO 边界），
 *    失败语义（null = 未登录 / 占位 / 拉取失败）原样透传给用户。
 *  - 危险操作（清 cookie / 清快照）一律 [AlertDialog] 二次确认并写清后果。
 *  - 正式包被误开时顶部醒目提示（兜底，正常入口不该出现）。
 *  - 不写日志、不上报：连条数都不落 Log，避免调试代码变成凭据侧信道。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // CookieJar / AuthRepository 都是 Application.onCreate 里初始化好的进程级单例。
    // 这里 runCatching 兜底：真没初始化时页面降级成只读提示，而不是整页崩掉。
    val cookieJar = remember { runCatching { JxnuHttpClient.get().cookieJar }.getOrNull() }
    val repository = remember { runCatching { AuthRepository.instance }.getOrNull() }
    val fallbackAuthState = remember { MutableStateFlow<AuthState>(AuthState.LoggedOut) }
    val authState by (repository?.state ?: fallbackAuthState).collectAsStateWithLifecycle()

    // 页面局部状态：不跨页面存活，退出即清（调试页没有需要恢复的现场）。
    var env by remember { mutableStateOf(readDebugEnv(context)) }
    var cookieSummaries by remember { mutableStateOf(cookieJar?.summary().orEmpty()) }
    var cookieMessage by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<SessionProbeResult?>(null) }
    var taskMessage by remember { mutableStateOf<String?>(null) }
    var refreshingWidget by remember { mutableStateOf(false) }
    var confirmClearCookies by remember { mutableStateOf(false) }
    var confirmClearSnapshot by remember { mutableStateOf(false) }

    fun reloadEnv() {
        env = readDebugEnv(context)
    }

    fun reloadCookies() {
        cookieSummaries = cookieJar?.summary().orEmpty()
        // cookie 文件大小会变，一并重读环境区。
        reloadEnv()
    }

    if (confirmClearCookies) {
        DebugConfirmDialog(
            title = "清空 jwc host cookie？",
            body = "将删除所有会随请求发往 ${JxnuUrls.JWC_HOST} 的 cookie（含 JSESSIONID 等业务会话），" +
                "之后访问教务会退回登录页，需要重新登录。\n\n" +
                "注意：Domain 为 .${JxnuUrls.ROOT_DOMAIN} 的父域 cookie（可能承载 CAS 票据）也会一并清掉，" +
                "所以清完之后不一定还能靠免密续票自动恢复。",
            onConfirm = {
                cookieJar?.clearForHost(JxnuUrls.JWC_HOST)
                reloadCookies()
                cookieMessage = "已清空 ${JxnuUrls.JWC_HOST} 相关 cookie"
            },
            onDismiss = { confirmClearCookies = false },
        )
    }

    if (confirmClearSnapshot) {
        DebugConfirmDialog(
            title = "清空小组件快照？",
            body = "将删除 filesDir/widget_snapshot.json，桌面「今日课表」小组件会立刻变成" +
                "「打开 App 加载课表」的空态，直到下次打开 App 重新拉取课表。\n\n" +
                "与退出登录同款后果：本次进程内快照写入会被锁定（WidgetSnapshotStore.clear 的栅门），" +
                "直到下一次登录成功才会恢复写入。",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { WidgetSnapshotStore.clear(context) }
                    refreshTodayScheduleWidgets(context)
                    reloadEnv()
                    taskMessage = "已删除 widget_snapshot.json 快照"
                }
            },
            onDismiss = { confirmClearSnapshot = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发者工具") },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 兜底提示：入口本该由调用方按 debuggable 控制，正式包里万一被打开也要显眼。
            if (!env.debuggable) {
                item { DebugWarningBanner("当前为正式包，本页仅供调试") }
            }

            item {
                EnvironmentSection(env = env, onReload = { reloadEnv() })
            }

            item {
                SessionSection(
                    authState = authState,
                    hasSavedCredentials = repository?.hasSavedCredentials(),
                    probing = probing,
                    probeResult = probeResult,
                    onProbe = {
                        val repo = repository
                        probeResult = null
                        if (repo == null) {
                            probeResult = SessionProbeResult.Failure("AuthRepository 未初始化", "会话仓库未初始化，无法探测")
                        } else {
                            probing = true
                            scope.launch {
                                probeResult = runSessionProbe(repo)
                                probing = false
                            }
                        }
                    },
                )
            }

            item {
                CookieSection(
                    cookieJar = cookieJar,
                    summaries = cookieSummaries,
                    message = cookieMessage,
                    onCopyJson = {
                        val jar = cookieJar
                        cookieMessage = if (jar == null) {
                            "CookieJar 未初始化，无法导出"
                        } else {
                            val json = jar.exportJson()
                            if (copyToClipboard(context, "jxnu_cookies", json)) {
                                "已复制 ${json.length} 字符的 cookie JSON 到剪贴板"
                            } else {
                                "复制失败：系统剪贴板不可用"
                            }
                        }
                    },
                    onImportJson = {
                        val jar = cookieJar
                        if (jar == null) {
                            cookieMessage = "CookieJar 未初始化，无法导入"
                        } else {
                            val clip = readClipboardText(context)
                            cookieMessage = if (clip == null) {
                                "剪贴板为空或不是文本"
                            } else {
                                runCatching { jar.importJson(clip) }.fold(
                                    onSuccess = { count -> "已导入 $count 条 cookie（覆盖内存会话）" },
                                    onFailure = { e -> e.message ?: "导入失败：${e::class.simpleName}" },
                                )
                            }
                            reloadCookies()
                        }
                    },
                    onClearJwc = { confirmClearCookies = true },
                )
            }

            item {
                BackgroundTaskSection(
                    message = taskMessage,
                    refreshingWidget = refreshingWidget,
                    onRunAlertCheck = {
                        scope.launch {
                            // sync 内部会 getInstance(WorkManager)（开库，是磁盘操作），切 IO 稳妥。
                            val result = withContext(Dispatchers.IO) {
                                runCatching { AlertScheduler.sync(context, runNow = true) }
                            }
                            taskMessage = result.fold(
                                onSuccess = { "已入队：出分 / 考试检查（提醒开关关闭时任务会被直接取消）" },
                                onFailure = { e -> "入队失败：${e.message ?: e::class.simpleName}" },
                            )
                        }
                    },
                    onRefreshWidget = {
                        refreshingWidget = true
                        scope.launch {
                            runCatching { refreshTodayScheduleWidgets(context) }
                            refreshingWidget = false
                            taskMessage = "已请求重绘桌面小组件（桌面没加小组件时不会有任何变化）"
                        }
                    },
                    onClearSnapshot = { confirmClearSnapshot = true },
                )
            }
        }
    }
}

/* ------------------------------- 1. 运行环境 ------------------------------- */

@Composable
private fun EnvironmentSection(env: DebugEnvInfo, onReload: () -> Unit) {
    DebugSection(title = "运行环境") {
        DebugInfoRow(label = "版本名", value = "v${env.versionName}")
        DebugInfoRow(label = "SDK_INT", value = env.sdkInt.toString())
        DebugInfoRow(
            label = "包类型",
            value = if (env.debuggable) "debuggable" else "release",
            valueColor = if (env.debuggable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        DebugDivider()
        Text(
            text = "登录 / 缓存文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        env.files.forEach { file ->
            DebugInfoRow(
                label = file.name,
                value = file.display,
                mono = true,
                valueColor = if (file.exists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DebugActionButton(text = "重新读取", onClick = onReload)
    }
}

/* ------------------------------- 2. 会话诊断 ------------------------------- */

@Composable
private fun SessionSection(
    authState: AuthState,
    hasSavedCredentials: Boolean?,
    probing: Boolean,
    probeResult: SessionProbeResult?,
    onProbe: () -> Unit,
) {
    DebugSection(title = "会话诊断") {
        when (authState) {
            is AuthState.LoggedIn -> {
                DebugInfoRow(label = "登录态", value = "已登录", valueColor = MaterialTheme.colorScheme.primary)
                // 只报"是否非空"：姓名原文 / 学号截图外流同样算泄露，排障只需要知道解析成功没有。
                DebugInfoRow(label = "姓名", value = if (authState.profile.name.isNotBlank()) "非空" else "空")
                DebugInfoRow(label = "学号", value = if (authState.profile.studentId.isNotBlank()) "非空" else "空")
            }
            is AuthState.Error -> DebugInfoRow(
                label = "登录态",
                value = "错误：${authState.message}",
                valueColor = MaterialTheme.colorScheme.error,
            )
            AuthState.Loading -> DebugInfoRow(label = "登录态", value = "进行中")
            AuthState.LoggedOut -> DebugInfoRow(label = "登录态", value = "未登录")
        }
        if (hasSavedCredentials != null) {
            DebugInfoRow(label = "本机已保存密码", value = if (hasSavedCredentials) "有" else "无")
        }
        DebugDivider()
        Text(
            text = "会话探针会重新拉一次教务首页，验证当前 cookie 还能不能取到数据；耗时越短链路越顺。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DebugActionButton(
            text = "跑一次会话探针",
            onClick = onProbe,
            busy = probing,
        )
        when (probeResult) {
            null -> Unit
            is SessionProbeResult.Success -> ProbeResultText(
                text = "成功：${probeResult.name.ifBlank { "（姓名为空）" }} · " +
                    "学号${if (probeResult.hasStudentId) "已取到" else "缺失"} · ${probeResult.elapsedMs} ms",
                color = MaterialTheme.colorScheme.primary,
            )
            SessionProbeResult.NotLoggedIn -> ProbeResultText(
                text = "返回 null：当前不在登录态，或首页拉取失败 / 姓名仍是占位（refreshProfile 的 null 语义）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is SessionProbeResult.Failure -> ProbeResultText(
                text = "异常 ${probeResult.type}：${probeResult.userMessage}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/* ------------------------------- 3. Cookie 调试 ----------------------------- */

@Composable
private fun CookieSection(
    cookieJar: PersistentCookieJar?,
    summaries: List<CookieSummary>,
    message: String?,
    onCopyJson: () -> Unit,
    onImportJson: () -> Unit,
    onClearJwc: () -> Unit,
) {
    // hasJwcSession / hasCasTgc 内部走 loadForRequest（会顺带修剪过期项），
    // 所以只在 cookie 集合变化时算一次，避免每次重组都触发一遍。
    val hasJwcSession = remember(cookieJar, summaries) { cookieJar?.hasJwcSession() ?: false }
    val hasCasTgc = remember(cookieJar, summaries) { cookieJar?.hasCasTgc() ?: false }

    DebugSection(title = "Cookie 调试") {
        DebugInfoRow(
            label = "内存条数",
            value = if (cookieJar == null) "CookieJar 未初始化" else summaries.size.toString(),
        )
        DebugInfoRow(
            label = "能发往 jwc 的 cookie",
            value = if (hasJwcSession) "有" else "无",
            valueColor = if (hasJwcSession) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        DebugInfoRow(label = "CAS 票据（TGC / CASTGC）", value = if (hasCasTgc) "有" else "无")
        DebugDivider()
        if (summaries.isEmpty()) {
            Text(
                text = "内存里没有任何 cookie（未登录，或已被清空）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 按 host 分组展示；只给 name 和过期与否，value 永不上屏。
            summaries.groupBy { it.host }.forEach { (host, cookies) ->
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                cookies.forEach { summary -> CookieSummaryRow(summary) }
            }
        }
        DebugDivider()
        DebugActionButton(
            text = "复制 JSON 到剪贴板",
            onClick = onCopyJson,
            enabled = cookieJar != null,
        )
        Text(
            text = "含登录凭据，仅本机调试使用，勿分享",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        DebugActionButton(
            text = "从剪贴板导入",
            onClick = onImportJson,
            enabled = cookieJar != null,
        )
        DebugActionButton(
            text = "清空 jwc host cookie",
            onClick = onClearJwc,
            enabled = cookieJar != null,
            danger = true,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CookieSummaryRow(summary: CookieSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start,
        )
        Text(
            text = if (summary.expired) "已过期" else "有效",
            style = MaterialTheme.typography.labelSmall,
            color = if (summary.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/* ------------------------------- 4. 后台任务 ------------------------------- */

@Composable
private fun BackgroundTaskSection(
    message: String?,
    refreshingWidget: Boolean,
    onRunAlertCheck: () -> Unit,
    onRefreshWidget: () -> Unit,
    onClearSnapshot: () -> Unit,
) {
    DebugSection(title = "后台任务") {
        Text(
            text = "这几个入口直接驱动 WorkManager / Glance，用来验证「提醒不响」「小组件不刷新」是数据问题还是调度问题。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DebugActionButton(text = "立刻跑一次出分/考试检查", onClick = onRunAlertCheck)
        DebugActionButton(
            text = "刷新桌面小组件",
            onClick = onRefreshWidget,
            busy = refreshingWidget,
        )
        DebugActionButton(
            text = "清空小组件快照",
            onClick = onClearSnapshot,
            danger = true,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* --------------------------------- 通用组件 -------------------------------- */

@Composable
private fun DebugSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.primary, AppShape.tag),
            )
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun DebugInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ProbeResultText(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun DebugDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun DebugActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        enabled = enabled && !busy,
        shape = AppShape.listItem,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (busy) "运行中…" else text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DebugWarningBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.listItem,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun DebugConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text("确认", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
