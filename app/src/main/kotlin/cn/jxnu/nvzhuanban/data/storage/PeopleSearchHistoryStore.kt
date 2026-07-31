package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条师生查询的浏览历史。字段是 [cn.jxnu.nvzhuanban.data.model.Teacher] /
 * [cn.jxnu.nvzhuanban.data.model.Student] 检索结果里"重新打开详情页所需"的最小集合：
 * UI 层用它原样重建 PersonResult，点历史项走与点搜索结果完全相同的导航路径
 * （学生详情的 department 额外路由段也从这里带）。
 *
 * [isTeacher] false 即学生；[className] 仅学生有值。
 */
data class PeopleSearchHistoryEntry(
    val isTeacher: Boolean,
    val name: String,
    val gender: String,
    val userNum: String,
    val department: String,
    val idText: String,
    val className: String,
)

/**
 * 师生查询的浏览历史（最近查看过详情的人）。
 *
 * 只记"点开过详情"的人、不记搜索关键词——历史的用途是"快速回到之前查过的某个人"，
 * 关键词还得再点一次搜索，直达详情更短。上限 [MAX_ENTRIES] 条，重复查看同一人时上移到最前。
 *
 * 存储为单 key 的 JSON 数组（`org.json`，Android 自带）。条目含学号/姓名属于用户查询痕迹，
 * **必须**挂在 [cn.jxnu.nvzhuanban.data.repository.AuthRepository] 的 clearAllUserDataOnSignOut
 * 里，换账号不能把上一用户查过谁泄给下一用户。
 */
object PeopleSearchHistoryStore {

    private const val PREF_NAME = "people_search_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    private lateinit var sp: SharedPreferences

    private val _entries = MutableStateFlow<List<PeopleSearchHistoryEntry>>(emptyList())
    val entries: StateFlow<List<PeopleSearchHistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _entries.value = readAll()
    }

    /** 记录一次详情查看。同一人（type + userNum）已存在时移到最前；超过上限截尾。 */
    fun record(entry: PeopleSearchHistoryEntry) {
        // init guard：防极端时序（单测 / 非主进程）lateinit 异常，与其他 storage 单例一致。
        if (!::sp.isInitialized) return
        val next = buildList {
            add(entry)
            _entries.value.forEach { old ->
                if (!(old.isTeacher == entry.isTeacher && old.userNum == entry.userNum)) add(old)
            }
        }.take(MAX_ENTRIES)
        persist(next)
    }

    /** 删除单条历史（左滑/长按等入口暂未做，先留给"清空"以外的细粒度管理）。 */
    fun remove(entry: PeopleSearchHistoryEntry) {
        if (!::sp.isInitialized) return
        persist(_entries.value.filterNot { it.isTeacher == entry.isTeacher && it.userNum == entry.userNum })
    }

    /** 用户点「清空」或退出登录时调用。 */
    fun clearAll() {
        if (!::sp.isInitialized) return
        sp.edit().remove(KEY_ENTRIES).apply()
        _entries.value = emptyList()
    }

    private fun persist(list: List<PeopleSearchHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("teacher", e.isTeacher)
                    .put("name", e.name)
                    .put("gender", e.gender)
                    .put("userNum", e.userNum)
                    .put("department", e.department)
                    .put("idText", e.idText)
                    .put("className", e.className),
            )
        }
        sp.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        _entries.value = list
    }

    private fun readAll(): List<PeopleSearchHistoryEntry> = runCatching {
        val raw = sp.getString(KEY_ENTRIES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                add(
                    PeopleSearchHistoryEntry(
                        isTeacher = o.optBoolean("teacher"),
                        name = name,
                        gender = o.optString("gender"),
                        userNum = o.optString("userNum"),
                        department = o.optString("department"),
                        idText = o.optString("idText"),
                        className = o.optString("className"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList()) // 损坏的 JSON 当空历史处理，不让启动链路崩
}
