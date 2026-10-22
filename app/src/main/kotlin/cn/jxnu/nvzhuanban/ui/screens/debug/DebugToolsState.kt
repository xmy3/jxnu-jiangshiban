package cn.jxnu.nvzhuanban.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * 调试页的非 UI 部分：环境快照、会话探针、剪贴板读写。
 *
 * 全部是纯函数 / 无 Compose 依赖，页面只负责把它们的结果画出来。刻意不做任何上报、
 * 不把 cookie value 写进返回值（[SessionProbeResult] 只带姓名 / 学号是否为空）。
 */

/** 与登录 / 缓存有关的 filesDir 条目（调试页只看这两个，别的一堆缓存文件与登录态无关）。 */
internal val DEBUG_TRACKED_FILES = listOf("jxnu_cookies.tsv", "widget_snapshot.json")

/** 运行环境快照，进页面时读一次，页面里的「重新读取」再刷新一次即可。 */
internal data class DebugEnvInfo(
    /** 取自 PackageManager；拿不到（厂商魔改 / 解析失败）时回退 "0.0.0"。 */
    val versionName: String,
    val sdkInt: Int,
    /** `ApplicationInfo.FLAG_DEBUGGABLE`；本项目未启用 BuildConfig，只能这样判包型。 */
    val debuggable: Boolean,
    val files: List<DebugFileProbe>,
)

internal data class DebugFileProbe(
    val name: String,
    val exists: Boolean,
    val sizeBytes: Long,
) {
    /** 展示文案：不存在 / "12.3 KB"。 */
    val display: String get() = if (exists) formatByteSize(sizeBytes) else "不存在"
}

internal fun readDebugEnv(context: Context): DebugEnvInfo {
    val app = context.applicationContext
    val pm = app.packageManager
    val versionName = runCatching { pm.getPackageInfo(app.packageName, 0).versionName }
        .getOrNull()
        ?: "0.0.0"
    // 用 ApplicationInfo.flags 判 debuggable：本项目没开 BuildConfig（见 build.gradle.kts
    // 未设置 buildConfig = true），BuildConfig.DEBUG 在这里根本不存在。
    val debuggable = runCatching {
        (pm.getApplicationInfo(app.packageName, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(false)
    val files = DEBUG_TRACKED_FILES.map { name ->
        val f = File(app.filesDir, name)
        DebugFileProbe(name = name, exists = f.isFile, sizeBytes = if (f.isFile) f.length() else 0L)
    }
    return DebugEnvInfo(
        versionName = versionName,
        sdkInt = Build.VERSION.SDK_INT,
        debuggable = debuggable,
        files = files,
    )
}

internal fun formatByteSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * 会话探针结果。刻意不携带 token / cookie / 密码：页面可以被截图贴进 issue。
 *
 * 三态对应 [AuthRepository.refreshProfile] 的语义：
 *  - [Success]：仍在登录态且首页重新拉取成功，姓名不是占位
 *  - [NotLoggedIn]：refreshProfile 返回 null（未登录 / 姓名仍是占位 / 首页拉取失败）
 *  - [Failure]：抛了异常（mutex 中断、极端 OOM 等）
 */
internal sealed interface SessionProbeResult {
    data class Success(val name: String, val hasStudentId: Boolean, val elapsedMs: Long) : SessionProbeResult
    data object NotLoggedIn : SessionProbeResult
    data class Failure(val type: String, val userMessage: String) : SessionProbeResult
}

internal suspend fun runSessionProbe(repository: AuthRepository): SessionProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    return try {
        val profile = repository.refreshProfile()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (profile == null) {
            SessionProbeResult.NotLoggedIn
        } else {
            SessionProbeResult.Success(
                name = profile.name,
                hasStudentId = profile.studentId.isNotBlank(),
                elapsedMs = elapsed,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        SessionProbeResult.Failure(
            type = e::class.simpleName ?: e.javaClass.name,
            userMessage = e.message?.takeIf { it.isNotBlank() } ?: "会话探针失败（无异常信息）",
        )
    }
}

/** 复制到剪贴板。Android 13+ 系统会自己弹「已复制」提示，这里不再额外提示。 */
internal fun copyToClipboard(context: Context, label: String, text: String): Boolean = runCatching {
    val manager = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text).apply {
        // Android 13+ 不得在复制预览中直接显示会话凭据；低版本保留同一标记无副作用。
        description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    manager.setPrimaryClip(clip)
}.isSuccess

/**
 * 读剪贴板纯文本；空剪贴板 / 只含图片 / 被系统拒绝（无焦点）时返回 null。
 * Android 10+ 要求应用在前台才可读，本页由用户点击触发，满足条件。
 */
internal fun readClipboardText(context: Context): String? = runCatching {
    val manager = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()
