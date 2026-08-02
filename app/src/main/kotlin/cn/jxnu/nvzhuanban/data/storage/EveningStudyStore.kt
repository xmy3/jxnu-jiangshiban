package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import cn.jxnu.nvzhuanban.data.model.EveningStudy
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 大一晚自习「周几 + W 楼教室号」的本地存储。教务网完全不体现晚自习，这份数据只存在于本机。
 *
 * Key = 学期开学日 ISO 串（如 `2024-09-01`，来自 SemesterOption.startDate）——大一上 / 大一下
 * 各自独立设置。不用学期 option value 当 key：value 含 `\` 与空格，且离线快照侧只有
 * semesterStartEpochDay 没有 value，开学日是两侧唯一共同坐标。
 * 教室存 4 位数字号（楼栋锁死 W 楼，见 [EveningStudy.ROOM_PREFIX]），选填、全部晚自习天共用。
 *
 * 写入模式与 [CourseOverridesStore] 一致：SharedPreferences + 内存 StateFlow 镜像同步读，
 * 消费方（ScheduleRepository.applyEveningStudy）走同步快照 [daysFor] / [roomFor]。
 */
object EveningStudyStore {

    private const val PREF_NAME = "evening_study"
    private const val KEY_PREFIX = "days::"
    private const val ROOM_KEY_PREFIX = "room::"

    private lateinit var sp: SharedPreferences

    private val _bySemester = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    private val _roomBySemester = MutableStateFlow<Map<String, String>>(emptyMap())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        readAll()
    }

    /** [semesterStart] 学期已设置的晚自习周几；空集 = 未设置。 */
    fun daysFor(semesterStart: LocalDate): Set<Int> =
        _bySemester.value[semesterStart.toString()].orEmpty()

    /** [semesterStart] 学期的 W 楼教室号（4 位数字串，如 `1203`）；null = 未填。 */
    fun roomFor(semesterStart: LocalDate): String? =
        _roomBySemester.value[semesterStart.toString()]

    /**
     * 保存 [semesterStart] 学期的晚自习设置。[days] 空集 = 清除整个设置（教室一并删除，
     * 回到「未设置」占位态）。[roomDigits] null 或非法（非 4 位数字）= 不设教室——
     * **没有默认值**：调用方必须显式表态，防止只想改周几的新调用点把已存教室静默删掉。
     * 防御性过滤：days 只保留 [EveningStudy.ALLOWED_DAYS] 中的值并截断至 [EveningStudy.MAX_DAYS] 天。
     */
    fun set(semesterStart: LocalDate, days: Set<Int>, roomDigits: String?) {
        // init guard：与 CourseOverridesStore 一致，防极端时序（widget receiver 进程 / 单测）。
        if (!::sp.isInitialized) return
        val semesterKey = semesterStart.toString()
        val daysKey = KEY_PREFIX + semesterKey
        val roomKey = ROOM_KEY_PREFIX + semesterKey
        val normalized = days.filter { it in EveningStudy.ALLOWED_DAYS }
            .sorted()
            .take(EveningStudy.MAX_DAYS)
        val room = roomDigits?.takeIf { EveningStudy.isValidRoomDigits(it) }
        if (normalized.isEmpty()) {
            sp.edit().remove(daysKey).remove(roomKey).apply()
            _bySemester.value = _bySemester.value - semesterKey
            _roomBySemester.value = _roomBySemester.value - semesterKey
        } else {
            val editor = sp.edit().putString(daysKey, normalized.joinToString(","))
            if (room != null) editor.putString(roomKey, room) else editor.remove(roomKey)
            editor.apply()
            _bySemester.value = _bySemester.value + (semesterKey to normalized.toSet())
            _roomBySemester.value =
                if (room != null) _roomBySemester.value + (semesterKey to room)
                else _roomBySemester.value - semesterKey
        }
    }

    private fun readAll() {
        val all = sp.all ?: return
        val days = mutableMapOf<String, Set<Int>>()
        val rooms = mutableMapOf<String, String>()
        for ((k, v) in all) {
            if (v !is String) continue
            when {
                k.startsWith(KEY_PREFIX) -> {
                    val parsed = v.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in EveningStudy.ALLOWED_DAYS }
                        .toSet()
                    if (parsed.isNotEmpty()) days[k.removePrefix(KEY_PREFIX)] = parsed
                }
                k.startsWith(ROOM_KEY_PREFIX) -> {
                    if (EveningStudy.isValidRoomDigits(v)) rooms[k.removePrefix(ROOM_KEY_PREFIX)] = v
                }
            }
        }
        _bySemester.value = days
        // 教室是周几的附属：没有周几的学期不保留教室（脏数据防御）
        _roomBySemester.value = rooms.filterKeys { it in days }
    }

    /** 退出登录 / 换账号时清空；晚自习设置属于用户数据，不能跨账号残留。 */
    fun clearAll() {
        if (!::sp.isInitialized) return
        sp.edit().clear().apply()
        _bySemester.value = emptyMap()
        _roomBySemester.value = emptyMap()
    }
}
