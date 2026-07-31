package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 课表网格单节课的格高（dp）。
 *
 * 存 Float 而非 Int：捏合 zoom 是连续乘数，整数取整会出现亚 dp「卡顿台阶」。
 * 与 [ScheduleHeightPrefs] 配套；ScheduleScreen 在网格容器上挂双指手势，
 * 手势期间靠本地 transient state 驱动 UI，松手时一次性 [setSectionHeightDp] 落盘。
 */
object ScheduleHeightPrefs {

    const val MIN_DP = 40f
    const val MAX_DP = 96f
    const val DEFAULT_DP = 68f

    private const val PREF_NAME = "schedule_height_prefs"
    private const val KEY_DP = "section_dp"

    private lateinit var sp: SharedPreferences
    private val _sectionHeightDp = MutableStateFlow(DEFAULT_DP)
    val sectionHeightDp: StateFlow<Float> = _sectionHeightDp.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _sectionHeightDp.value = sp.getFloat(KEY_DP, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP)
    }

    fun setSectionHeightDp(dp: Float) {
        // init guard：极端时序（widget receiver 进程 / 单测漏 init）下避免 lateinit 异常。
        if (!::sp.isInitialized) return
        val clamped = dp.coerceIn(MIN_DP, MAX_DP)
        if (clamped == _sectionHeightDp.value) return
        sp.edit().putFloat(KEY_DP, clamped).apply()
        _sectionHeightDp.value = clamped
    }
}
