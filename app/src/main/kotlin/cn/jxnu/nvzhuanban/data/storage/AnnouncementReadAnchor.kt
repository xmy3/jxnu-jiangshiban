package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 已读锚点：用户上次在通知 tab 看到的「列表顶部」条目。
 *
 * [uniqueKey] 形如 `NOTIFICATION:12345`，用于底部导航红点与列表内未读标记；
 * [date] 是这条的发布日期，用于**按条目**推导未读——列表首屏是两阶段流式
 * （partial 只有通知/通告/风采，图文晚 1~2 秒），如果只按 key 在列表里找位置，
 * partial 阶段锚点常常不在列表里，整页都会被误判成未读。按日期比较则天然稳定：
 * 比锚点新的条目才是未读，同一天里排在锚点之前的条目也是未读。
 */
data class AnnouncementReadMarker(
    val uniqueKey: String,
    val date: LocalDate,
)

/**
 * 「上次见到的最新一条通知」锚点的持久化。
 *
 * 写入时机：用户进入通知 tab 并完成 load/refresh 后，把列表顶部条目的 key + 日期落盘。
 * 永远不要在 loadMore 之后写入——那拿到的是更老的页，会回退锚点。
 */
object AnnouncementReadAnchor {

    private const val PREF_NAME = "announcement_read_prefs"
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_ANCHOR_DATE = "anchor_date"

    private lateinit var sp: SharedPreferences
    private val _anchor = MutableStateFlow<String?>(null)
    val anchor: StateFlow<String?> = _anchor.asStateFlow()
    private val _date = MutableStateFlow<LocalDate?>(null)

    /**
     * 锚点 + 日期。旧版本只写过 key（没有日期）时返回 null——调用方按「无未读」降级，
     * 本次 load 会立刻把日期补写进去，下一轮就恢复正常。
     */
    val current: AnnouncementReadMarker?
        get() {
            val key = _anchor.value ?: return null
            val date = _date.value ?: return null
            return AnnouncementReadMarker(key, date)
        }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _anchor.value = sp.getString(KEY_ANCHOR, null)
        _date.value = sp.getString(KEY_ANCHOR_DATE, null)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }
    }

    fun setAnchor(uniqueKey: String, date: LocalDate) {
        if (!::sp.isInitialized) return
        if (_anchor.value == uniqueKey && _date.value == date) return
        sp.edit()
            .putString(KEY_ANCHOR, uniqueKey)
            .putString(KEY_ANCHOR_DATE, date.toString())
            .apply()
        _anchor.value = uniqueKey
        _date.value = date
    }

    /** 退出登录时清空锚点，避免下一用户继承上一用户的"最后已读" → 永远不亮红点。 */
    fun clear() {
        if (!::sp.isInitialized) return
        sp.edit().remove(KEY_ANCHOR).remove(KEY_ANCHOR_DATE).apply()
        _anchor.value = null
        _date.value = null
    }
}
