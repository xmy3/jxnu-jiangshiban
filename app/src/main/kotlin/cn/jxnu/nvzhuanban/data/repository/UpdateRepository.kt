package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.data.network.GitHubUpdateClient
import cn.jxnu.nvzhuanban.data.network.SemVer
import cn.jxnu.nvzhuanban.data.storage.UpdatePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 检查 GitHub 上是否有比当前安装版本更新的 release。
 *
 * 两条入口路径：
 *  - [checkIfDue]：App 启动时由 [cn.jxnu.nvzhuanban.NvzhuanbanApp] 跑的静默检查，
 *    24h 限频；任何失败都吞掉，且**不写时间戳**，让下一次 App 启动有机会重试。
 *  - [forceCheck]：「我的」页面用户主动点击「检查更新」时调用，绕过限频；失败让
 *    [cn.jxnu.nvzhuanban.data.network.GithubException] 上浮供 UI 展示。
 *
 * [latestRelease] 仅在「是更新版本 + 未被用户跳过」时持有值；其他情况（同版本 / 比当前老 /
 * 已跳过）都置 null。UI 侧的 SettingsBlock 仅根据这个 StateFlow 决定 subtitle 文案。
 */
class UpdateRepository(
    private val client: GitHubUpdateClient = GitHubUpdateClient(),
) {

    private val mutex = Mutex()

    private val _latestRelease = MutableStateFlow<AppRelease?>(null)
    val latestRelease: StateFlow<AppRelease?> = _latestRelease.asStateFlow()

    /**
     * 静默检查：距上次成功 < 24h 直接短路。失败 / 无新版本都静默吞掉，绝不抛。
     */
    suspend fun checkIfDue(currentVersion: String) {
        val now = System.currentTimeMillis()
        val last = UpdatePrefs.lastCheckEpochMs.value
        if (now - last < CHECK_INTERVAL_MS) return
        mutex.withLock {
            val release = runCatching { client.fetchLatest() }.getOrNull() ?: return
            UpdatePrefs.setLastCheck(now)
            applyRelease(release, currentVersion)
        }
    }

    /**
     * 主动检查：绕过限频。返回是否拿到了**真的更新版本**（不论是否被用户跳过过）。
     *
     * 注意：[forceCheck] 不调用 [UpdatePrefs.setLastCheck] —— 这是个用户主观重查动作，
     * 不应该消费下一次静默窗口，否则会出现"用户点完手动检查 → 24h 内 App 启动再也不查"。
     *
     * @return 更新版本时返回 [AppRelease]，已是最新返回 null
     * @throws cn.jxnu.nvzhuanban.data.network.GithubException 网络 / HTTP / 解析失败
     */
    suspend fun forceCheck(currentVersion: String): AppRelease? = mutex.withLock {
        val release = client.fetchLatest()
        applyRelease(release, currentVersion, ignoreSkip = true)
    }

    /**
     * 推送 release 到 [latestRelease] 并返回"是否更新版本"。
     *
     * - `ignoreSkip = false`（静默路径）：被用户跳过的 tag 不刷红字
     * - `ignoreSkip = true`（主动路径）：被跳过的 tag 也会重新弹 Dialog，给用户"取消跳过"的入口
     *
     * 即使返回 null（已是最新 / 比当前老），也要清空 [_latestRelease]，避免留下 stale 提示。
     */
    private fun applyRelease(
        release: AppRelease,
        currentVersion: String,
        ignoreSkip: Boolean = false,
    ): AppRelease? {
        val latestSv = SemVer.fromString(release.versionName)
        val currentSv = SemVer.fromString(currentVersion)
        val isNewer = latestSv != null && currentSv != null && latestSv > currentSv
        val isSkipped = !ignoreSkip && release.tagName == UpdatePrefs.skippedVersion.value
        _latestRelease.value = if (isNewer && !isSkipped) release else null
        return release.takeIf { isNewer }
    }

    companion object {
        /** 静默检查最小间隔：24h，足以覆盖正常用户的发版节奏，又不会过分挤占速率限制。 */
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        val instance: UpdateRepository by lazy { UpdateRepository() }
    }
}
