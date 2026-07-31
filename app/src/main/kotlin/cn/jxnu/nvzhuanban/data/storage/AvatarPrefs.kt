package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 是否在「我的」页加载教务系统上的学生头像。
 *
 * 默认 false：默认显示占位 [androidx.compose.material.icons.outlined.Person] 图标，
 * 用户在「我的 → 显示学生头像」里勾选后才走网络。
 */
object AvatarPrefs {

    private const val PREF_NAME = "avatar_prefs"
    private const val KEY_SHOW = "show"

    private lateinit var sp: SharedPreferences
    private val _showAvatar = MutableStateFlow(false)
    val showAvatar: StateFlow<Boolean> = _showAvatar.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _showAvatar.value = sp.getBoolean(KEY_SHOW, false)
    }

    fun setShowAvatar(show: Boolean) {
        // init guard：极端时序（widget receiver 进程 / 单测漏 init）下避免 lateinit 异常。
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(KEY_SHOW, show).apply()
        _showAvatar.value = show
    }
}
