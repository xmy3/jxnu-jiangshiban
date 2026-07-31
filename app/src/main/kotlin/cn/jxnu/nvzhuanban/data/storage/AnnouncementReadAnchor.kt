package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「上次见到的最新一条通知」锚点。
 *
 * 值是 [cn.jxnu.nvzhuanban.data.model.Announcement.uniqueKey]（形如 `NOTIFICATION:12345`）。
 * 用于底部导航「通知」tab 的小红点判定：当 Repository.latestList 顶部的 uniqueKey
 * 与该锚点不同（含锚点为 null 时），说明用户没看过新通知，应当亮红点。
 *
 * 写入时机：用户进入通知 tab 并完成 load/refresh 后，把列表顶部条目的 uniqueKey 落盘。
 * 永远不要在 loadMore 之后写入——那拿到的是更老的页，会回退锚点。
 */
object AnnouncementReadAnchor {

    private const val PREF_NAME = "announcement_read_prefs"
    private const val KEY_ANCHOR = "anchor"

    private lateinit var sp: SharedPreferences
    private val _anchor = MutableStateFlow<String?>(null)
    val anchor: StateFlow<String?> = _anchor.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _anchor.value = sp.getString(KEY_ANCHOR, null)
    }

    fun setAnchor(uniqueKey: String) {
        if (!::sp.isInitialized) return
        if (_anchor.value == uniqueKey) return
        sp.edit().putString(KEY_ANCHOR, uniqueKey).apply()
        _anchor.value = uniqueKey
    }

    /** 退出登录时清空锚点，避免下一用户继承上一用户的"最后已读" → 永远不亮红点。 */
    fun clear() {
        if (!::sp.isInitialized) return
        sp.edit().remove(KEY_ANCHOR).apply()
        _anchor.value = null
    }
}
