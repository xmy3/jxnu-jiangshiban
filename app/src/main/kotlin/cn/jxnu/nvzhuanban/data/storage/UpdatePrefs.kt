package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 更新检查的本地状态：上次成功检查的时间戳（用于 24h 限频）+ 用户主动跳过的版本号 tag。
 *
 * 关键约定：
 *  - 退出登录**不清** —— 这两项跟身份无关，清掉会让 24h 限频窗口失效、用户已跳过的版本
 *    重新弹提示，体验更糟。
 *  - 失败的检查不调用 [setLastCheck]：见 [cn.jxnu.nvzhuanban.data.repository.UpdateRepository.checkIfDue]
 *    的注释，避免一次瞬断让用户等 24h 才有第二次机会。
 */
object UpdatePrefs {

    private const val PREF_NAME = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_epoch_ms"
    private const val KEY_SKIPPED_VERSION = "skipped_version"

    private lateinit var sp: SharedPreferences

    private val _lastCheckEpochMs = MutableStateFlow(0L)
    val lastCheckEpochMs: StateFlow<Long> = _lastCheckEpochMs.asStateFlow()

    private val _skippedVersion = MutableStateFlow<String?>(null)
    val skippedVersion: StateFlow<String?> = _skippedVersion.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _lastCheckEpochMs.value = sp.getLong(KEY_LAST_CHECK, 0L)
        _skippedVersion.value = sp.getString(KEY_SKIPPED_VERSION, null)
    }

    fun setLastCheck(epochMs: Long) {
        if (!::sp.isInitialized) return
        sp.edit().putLong(KEY_LAST_CHECK, epochMs).apply()
        _lastCheckEpochMs.value = epochMs
    }

    /** 传 null 或空字符串等价于"取消跳过"。 */
    fun setSkippedVersion(tag: String?) {
        if (!::sp.isInitialized) return
        if (tag.isNullOrBlank()) {
            sp.edit().remove(KEY_SKIPPED_VERSION).apply()
            _skippedVersion.value = null
        } else {
            sp.edit().putString(KEY_SKIPPED_VERSION, tag).apply()
            _skippedVersion.value = tag
        }
    }
}
