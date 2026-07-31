package cn.jxnu.nvzhuanban

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.repository.UpdateRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.AvatarPrefs
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import cn.jxnu.nvzhuanban.data.storage.PeopleSearchHistoryStore
import cn.jxnu.nvzhuanban.data.storage.ScheduleHeightPrefs
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.data.storage.UpdatePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NvzhuanbanApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 登录态恢复任务，MainActivity 的 splash 用它做"保持等待"的信号。
     * 结果 = true → 已恢复为 LoggedIn；false → 需要让用户走登录页。
     */
    lateinit var sessionRestore: Deferred<Boolean>
        private set

    override fun onCreate() {
        super.onCreate()

        // 关掉 foundation 新版文本 context menu，否则 SelectionContainer（通知详情正文）选中
        // 文字会「弹两次」复制菜单。必须在首个 Composition 之前设置，详见函数注释。
        disableDuplicateTextContextMenu()

        // 预打开冷启动路径要读的全部 SharedPreferences 文件：getSharedPreferences 只触发各自
        // 后台线程的磁盘加载，首次 get*() 才阻塞等待。先把文件全部打开（只开不读），下面各
        // init() 的首读大多命中已加载缓存 —— 否则「打开一个→读一个」会让这批文件的加载在
        // 主线程上串行，全部叠在首帧之前。
        STARTUP_PREFS_FILES.forEach { getSharedPreferences(it, MODE_PRIVATE) }

        ThemePrefs.init(this)
        AvatarPrefs.init(this)
        ScheduleHeightPrefs.init(this)
        CourseOverridesStore.init(this)
        PeopleSearchHistoryStore.init(this)
        AnnouncementReadAnchor.init(this)
        UpdatePrefs.init(this)
        val auth = AuthRepository.init(this)
        val deferred = CompletableDeferred<Boolean>()
        sessionRestore = deferred

        appScope.launch {
            val ok = runCatching { auth.tryRestoreSession() }.getOrDefault(false)
            deferred.complete(ok)
        }

        // 通知页是公开页（无需登录），可以与登录态恢复并发跑。
        // 目的是让底部导航的小红点能在用户进入通知 tab 前就感知到新通知。
        // 失败（无网/超时）静默忽略，UI 不会显示红点。
        appScope.launch {
            runCatching { AnnouncementRepository.instance.fetchAll(page = 1) }
        }

        // GitHub 新版本检查：24h 限频，失败静默不写时间戳（下次启动仍可重试）。
        // 跑在独立 OkHttp 实例上，不污染 jwc cookie 存储。详见 UpdateRepository。
        appScope.launch {
            runCatching {
                UpdateRepository.instance.checkIfDue(currentVersionName())
            }
        }
    }

    /**
     * foundation 1.8 起 [ComposeFoundationFlags.isNewContextMenuEnabled] 默认 true，启用新版文本
     * context menu：在 Android 上它给 [androidx.compose.foundation.text.selection.SelectionContainer]
     * 同时装配「系统平台浮动工具栏」+「Compose 自绘 dropdown」两个 provider 并都渲染出来，长按选中
     * 正文时**两个复制菜单一起弹**（本 app 仅通知详情 AnnouncementDetailScreen 用了 SelectionContainer，
     * 故现象集中在通知页）。置 false 回到旧的单一系统 context menu —— 即升级 BOM 前的行为，也是用户要的
     * 「交给系统」。这是全局开关，必须在首个 Composition 之前赋值（故放 onCreate 最前）；属于
     * @ExperimentalFoundationApi，将来 BOM 若移除该 flag 会在编译期报错（能被及时发现，不是静默失效）。
     */
    @OptIn(ExperimentalFoundationApi::class)
    private fun disableDuplicateTextContextMenu() {
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    /**
     * 从 packageManager 读 versionName。读不到（极少见，比如包信息异常）回退到 `0.0.0`，
     * 让 [cn.jxnu.nvzhuanban.data.network.SemVer.fromString] 解析成 0.0.0 → 任何 release
     * 都"更新"，反而不会卡死。这个回退跟 [ProfileScreen] 的 `versionName` 读取一致。
     */
    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    private companion object {
        /**
         * 冷启动会被同步首读的 SharedPreferences 文件名。与各 storage 类及
         * [cn.jxnu.nvzhuanban.data.network.AuthStorage] 里私有的 PREF_NAME 一一对应 ——
         * 那边改名时这里要跟着改（列表只用于预加载，漏一个只是回到串行加载，不影响正确性）。
         */
        val STARTUP_PREFS_FILES = listOf(
            "theme_prefs",             // ThemePrefs
            "avatar_prefs",            // AvatarPrefs
            "schedule_height_prefs",   // ScheduleHeightPrefs
            "course_overrides",        // CourseOverridesStore
            "people_search_history",   // PeopleSearchHistoryStore
            "announcement_read_prefs", // AnnouncementReadAnchor
            "update_prefs",            // UpdatePrefs
            "jxnu_auth",               // AuthStorage（AuthRepository.init 内首读）
        )
    }
}
