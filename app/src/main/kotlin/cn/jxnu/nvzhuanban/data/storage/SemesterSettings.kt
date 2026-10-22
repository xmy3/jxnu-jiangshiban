package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 学期周坐标的用户覆盖（「第 1 周从哪天算起」「本学期一共几周」）。
 *
 * 为什么需要它：教务网 `<select id="…_ddlSterm">` 的 option value 是**名义日期**
 * （每年 3/1、9/1），与星期无关；[SemesterPhase.weekOneMonday] 只能把名义日对齐到最近
 * 周一近似推算。名义日与真实校历差一周时（补课/延期开学/校历调整），全 app 的「第 N 周」
 * 会整体错位 —— 用户可以在这里一次性校正。
 *
 * 作用范围刻意**按学期隔离**（[Override.nominalStart] 只对同一名义开学日生效）：
 *  - 切换历史/未来学期时，覆盖不会串到别的学期上；
 *  - 假期里「距下学期开学」的倒计时仍按教务名义日推算，不受本学期覆盖影响。
 *
 * 存储用 SharedPreferences 单文件（与项目其余 prefs 一致，见 [ScheduleHeightPrefs]）；
 * [revision] 是进程内变更信号，课表页观察它重算周次并刷新桌面小组件。
 */
object SemesterSettings {

    /** 教务不提供周次时的回退值（与 `SchedulePage.DEFAULT_TOTAL_WEEKS` 同源）。 */
    const val DEFAULT_TOTAL_WEEKS = 18

    /** 可选总周数范围：上限刻意放宽到 30，覆盖小学期/暑期课程。 */
    const val MIN_TOTAL_WEEKS = 1
    const val MAX_TOTAL_WEEKS = 30

    private const val PREF_NAME = "semester_settings"
    private const val MONDAY_PREFIX = "monday::"
    private const val WEEKS_PREFIX = "weeks::"

    /**
     * 一次用户覆盖。
     *
     * @param nominalStart 该覆盖归属的学期 —— 教务 option value 的开学日（可能不是周一）。
     * @param weekOneMonday 用户指定的第 1 周周一。
     * @param totalWeeks 用户指定的总周数；null = 跟随教务（解析不到时回退 [DEFAULT_TOTAL_WEEKS]）。
     */
    data class Override(
        val nominalStart: LocalDate,
        val weekOneMonday: LocalDate,
        val totalWeeks: Int?,
    )

    private lateinit var sp: SharedPreferences

    private val _revision = MutableStateFlow(0)

    /** 每次保存/恢复默认自增；UI 观察它重算（值本身无意义）。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    private var current: Map<LocalDate, Override> = emptyMap()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        initialize(context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))
    }

    internal fun initialize(preferences: SharedPreferences) {
        sp = preferences
        current = readAll()
    }

    /** 按名义开学日保存独立设置，编辑另一个学期不会覆盖之前的校正。 */
    fun overrideFor(nominalStart: LocalDate?): Override? = current[nominalStart]

    /**
     * 生效的第 1 周周一：同一学期的用户覆盖优先，否则按名义日对齐最近周一。
     * [nominalStart] 为 null（教务没给/未加载）时返回 null。
     */
    fun weekOneMondayFor(nominalStart: LocalDate?): LocalDate? {
        if (nominalStart == null) return null
        val o = overrideFor(nominalStart)
        if (o != null) return o.weekOneMonday
        return SemesterPhase.weekOneMonday(nominalStart)
    }

    /**
     * 生效的总周数：[parsed] 是教务/解析层给出的值（≤0 视为未知）。
     * 同一学期的用户覆盖优先于 [parsed]。
     */
    fun totalWeeksFor(nominalStart: LocalDate?, parsed: Int): Int {
        val fallback = if (parsed in MIN_TOTAL_WEEKS..MAX_TOTAL_WEEKS) parsed else DEFAULT_TOTAL_WEEKS
        if (nominalStart == null) return fallback
        val o = overrideFor(nominalStart)
        if (o?.totalWeeks != null) return o.totalWeeks
        return fallback
    }

    /**
     * 写入覆盖。[totalWeeks] 为 null = 跟随教务；给出时必须落在
     * [MIN_TOTAL_WEEKS]..[MAX_TOTAL_WEEKS]，否则抛 [IllegalArgumentException]。
     *
     * 同一学期的两个字段一次写入，周一起点必须确实是周一。
     */
    @Synchronized
    fun save(nominalStart: LocalDate, weekOneMonday: LocalDate, totalWeeks: Int?) {
        require(weekOneMonday.dayOfWeek == DayOfWeek.MONDAY) { "第 1 周起始日期必须是周一" }
        if (totalWeeks != null && totalWeeks !in MIN_TOTAL_WEEKS..MAX_TOTAL_WEEKS) {
            throw IllegalArgumentException("学期总周数必须在 $MIN_TOTAL_WEEKS..$MAX_TOTAL_WEEKS 之间")
        }
        if (!::sp.isInitialized) return
        // 统一存 ISO（yyyy-MM-dd）：LocalDate.toString 即 ISO，解析用 parse 对称，避免本地化格式。
        sp.edit()
            .putString(MONDAY_PREFIX + nominalStart, weekOneMonday.toString())
            .putInt(WEEKS_PREFIX + nominalStart, totalWeeks ?: 0)
            .apply()
        current = current + (nominalStart to Override(nominalStart, weekOneMonday, totalWeeks))
        _revision.update { it + 1 }
    }

    /** 只恢复指定学期的默认值，保留其他学期的设置。 */
    @Synchronized
    fun clear(nominalStart: LocalDate) {
        if (!::sp.isInitialized) return
        sp.edit()
            .remove(MONDAY_PREFIX + nominalStart)
            .remove(WEEKS_PREFIX + nominalStart)
            .apply()
        current = current - nominalStart
        _revision.update { it + 1 }
    }

    /** 退出登录或换号时清空用户校正。 */
    @Synchronized
    fun clearAll() {
        if (!::sp.isInitialized) return
        sp.edit().clear().apply()
        current = emptyMap()
        _revision.update { it + 1 }
    }

    /** 损坏的条目按未设置处理，错误的 prefs 类型也不能阻断启动。 */
    private fun readAll(): Map<LocalDate, Override> {
        val values = sp.all ?: return emptyMap()
        return values.mapNotNull { (key, value) ->
            if (!key.startsWith(MONDAY_PREFIX)) return@mapNotNull null
            val nominal = parseDate(key.removePrefix(MONDAY_PREFIX)) ?: return@mapNotNull null
            val monday = (value as? String)?.let(::parseDate) ?: return@mapNotNull null
            if (monday.dayOfWeek != DayOfWeek.MONDAY) return@mapNotNull null
            val weeks = (values[WEEKS_PREFIX + nominal] as? Int)
                ?.takeIf { it in MIN_TOTAL_WEEKS..MAX_TOTAL_WEEKS }
            nominal to Override(nominal, monday, weeks)
        }.toMap()
    }

    private fun parseDate(text: String): LocalDate? =
        runCatching { LocalDate.parse(text) }.getOrNull()
}
