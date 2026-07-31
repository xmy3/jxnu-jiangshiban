package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单门课周次的本地覆盖。
 *
 * 教务网 HTML 课表不区分周次，所有课都默认 1..18 周。学生实际遇到的情况是某些课其实只上 1-8 周
 * 或者只上单/双周，但教务老师不会更新。这里给用户一个本地覆盖：以课程名为 key 存一份用户改过的周次。
 *
 * Key = 课程名（来自 [cn.jxnu.nvzhuanban.data.model.Course.name]）。同名课程（不同上课时间、不同教室）
 * 共享同一份覆盖。这是用户心智里的"一门课"。
 *
 * 写入 SharedPreferences；通过 [overrides] StateFlow 暴露给 UI/Repository。
 */
object CourseOverridesStore {

    private const val PREF_NAME = "course_overrides"
    /** 单项 key 前缀。SharedPreferences 没法存集合，所以一门课一个 key。 */
    private const val KEY_PREFIX = "weeks::"

    private lateinit var sp: SharedPreferences

    private val _overrides = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val overrides: StateFlow<Map<String, List<Int>>> = _overrides.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _overrides.value = readAll()
    }

    /**
     * 读 [name] 的用户覆盖；null 表示用户没改过这门课，调用方应保留教务网原始周次（默认 1..18）。
     */
    fun get(name: String): List<Int>? = _overrides.value[name]

    /** 当前所有覆盖的快照（不会随后续更改）。 */
    fun current(): Map<String, List<Int>> = _overrides.value

    /**
     * 设置 [name] 的周次覆盖。[weeks] = null 或空列表表示"恢复默认"——清掉这条覆盖，
     * 等同于教务网的 1..18。
     */
    fun set(name: String, weeks: List<Int>?) {
        // init guard：和 [clearAll] 一致，防极端时序（widget receiver 进程 / 单测）下 lateinit 异常。
        if (!::sp.isInitialized) return
        val key = KEY_PREFIX + name
        if (weeks.isNullOrEmpty()) {
            sp.edit().remove(key).apply()
            _overrides.value = _overrides.value - name
        } else {
            // 排序去重，存成 "1,2,3" 形式
            val normalized = weeks.toSortedSet().toList()
            sp.edit().putString(key, normalized.joinToString(",")).apply()
            _overrides.value = _overrides.value + (name to normalized)
        }
    }

    private fun readAll(): Map<String, List<Int>> {
        val all = sp.all ?: return emptyMap()
        val out = mutableMapOf<String, List<Int>>()
        for ((k, v) in all) {
            if (!k.startsWith(KEY_PREFIX) || v !is String) continue
            val name = k.removePrefix(KEY_PREFIX)
            val weeks = v.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (weeks.isNotEmpty()) out[name] = weeks
        }
        return out
    }

    /** 退出登录时清空所有覆盖；避免下一用户继承上一用户给同名课程做的本地修正。 */
    fun clearAll() {
        if (!::sp.isInitialized) return
        sp.edit().clear().apply()
        _overrides.value = emptyMap()
    }
}
