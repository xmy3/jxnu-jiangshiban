package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import androidx.core.content.edit

/**
 * 轻量 KV 存储：学号、installation UUID、记住账号偏好。
 *
 * 故意**不存密码**——
 * - 真要做免密登录，应该用 Android Keystore 加密存 token/cookie，不存明文密码
 * - 现阶段密码每次都让用户重新输；登录成功后靠 cookie 维持会话
 *
 * 用 SharedPreferences 而非 DataStore：
 * - 同步读取（DataStore 需要协程，CookieJar 不方便）
 * - 数据量极小（< 200 字节）
 * - 不需要事务性多键更新
 */
class AuthStorage(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jxnu_auth", Context.MODE_PRIVATE)

    /** 上次登录的学号，用于自动回填登录框。 */
    var lastUsername: String?
        get() = prefs.getString(K_USERNAME, null)
        set(v) = prefs.edit { putString(K_USERNAME, v) }

    /** 用户是否勾选了"记住账号"。 */
    var rememberMe: Boolean
        get() = prefs.getBoolean(K_REMEMBER_ME, true)
        set(v) = prefs.edit { putBoolean(K_REMEMBER_ME, v) }

    /**
     * 最近一次**成功登录**的学号（与 [lastUsername] 不同：不受"记住账号"开关影响）。
     * 用于登录成功时判断"换了个人登录"，据此决定是否清空上一用户的本地派生数据
     * （课程周次覆盖 / 通知已读锚点等）。会话过期→同一用户重登的场景则保留这些数据。
     */
    var lastLoggedUser: String?
        get() = prefs.getString(K_LAST_LOGGED_USER, null)
        set(v) = prefs.edit { putString(K_LAST_LOGGED_USER, v) }

    /** Installation UUID，首次访问时生成并持久化。 */
    fun installUuid(): String {
        prefs.getString(K_INSTALL_UUID, null)?.let { return it }
        val fresh = DeviceFingerprint.newInstallUuid()
        prefs.edit { putString(K_INSTALL_UUID, fresh) }
        return fresh
    }

    /**
     * 清空账号相关偏好（学号回填、记住账号开关）。
     *
     * **保留** [K_INSTALL_UUID]（设备指纹，重置会让 CAS 侧设备信任失效）与
     * [K_LAST_LOGGED_USER]（换号检测依据，清掉会让下一次登录漏掉跨用户数据清理）。
     * 当前无调用方；如果要接进 logout，注意 AuthRepository.logout 已负责清业务数据。
     */
    fun clear() {
        prefs.edit {
            remove(K_USERNAME)
            remove(K_REMEMBER_ME)
        }
    }

    companion object {
        private const val K_USERNAME = "last_username"
        private const val K_REMEMBER_ME = "remember_me"
        private const val K_INSTALL_UUID = "install_uuid"
        private const val K_LAST_LOGGED_USER = "last_logged_user"

        @Volatile private var INSTANCE: AuthStorage? = null

        fun init(context: Context): AuthStorage {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: AuthStorage(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): AuthStorage = INSTANCE
            ?: error("AuthStorage 未初始化，请在 Application.onCreate 调用 AuthStorage.init(this)")
    }
}
