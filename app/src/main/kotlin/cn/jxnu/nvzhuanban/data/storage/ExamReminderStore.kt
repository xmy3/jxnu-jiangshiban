package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import cn.jxnu.nvzhuanban.data.model.ExamReminderDay
import cn.jxnu.nvzhuanban.data.model.ExamReminderItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 「考前一天提醒」已经排上闹钟的考试日存档。
 *
 * 为什么要存一份：**AlarmManager 的闹钟活不过重启**。开机广播来的时候手里没有任何考试数据
 * （那要从教务网拉），只能靠这份存档把闹钟按原样重排一遍 —— 否则重启一次，第二天的考试提醒
 * 就没了。顺带解决两个问题：
 *  - 考试改期 / 取消后旧闹钟还在：`sync` 比对存量和增量，把消失的日子显式 cancel。
 *  - 闹钟响过之后先把这天从存档里摘掉，重复投递也不会弹第二次。
 *
 * 这是**用户派生数据**（里面是课程名），登出 / 换号必须 [clear]，见
 * `AuthRepository.clearAllUserDataOnSignOut`。
 */
object ExamReminderStore {

    private const val PREF_NAME = "exam_reminders"
    private const val KEY_DAYS = "days"

    // 后台 worker 与前台广播接收器会同时读写，读改写必须串起来
    private val lock = Any()

    fun load(context: Context): List<ExamReminderDay> = synchronized(lock) {
        val raw = prefs(context).getString(KEY_DAYS, null) ?: return emptyList()
        runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, days: List<ExamReminderDay>) {
        synchronized(lock) {
            prefs(context).edit().putString(KEY_DAYS, encode(days)).apply()
        }
    }

    fun remove(context: Context, date: LocalDate) {
        synchronized(lock) {
            val remaining = load(context).filterNot { it.date == date }
            prefs(context).edit().putString(KEY_DAYS, encode(remaining)).apply()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            prefs(context).edit().remove(KEY_DAYS).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // region 编解码（字段名是短键：存档只在闹钟重排时读一次，省点体积没意义，可读性优先）

    private fun encode(days: List<ExamReminderDay>): String {
        val array = JSONArray()
        for (day in days) {
            val items = JSONArray()
            for (item in day.items) {
                items.put(
                    JSONObject()
                        .put("name", item.courseName)
                        .put("room", item.location)
                        .put("seat", item.seat ?: ""),
                )
            }
            array.put(JSONObject().put("date", day.date.toString()).put("items", items))
        }
        return array.toString()
    }

    private fun decode(raw: String): List<ExamReminderDay> {
        val array = JSONArray(raw)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val day = array.optJSONObject(i) ?: continue
                // 日期解析失败就当这条脏数据不存在：宁可少一条提醒，也不能让开机重排整体崩掉
                val date = runCatching { LocalDate.parse(day.optString("date")) }.getOrNull() ?: continue
                val items = day.optJSONArray("items") ?: JSONArray()
                val parsed = buildList {
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        val name = item.optString("name")
                        if (name.isBlank()) continue
                        add(
                            ExamReminderItem(
                                courseName = name,
                                location = item.optString("room"),
                                seat = item.optString("seat").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
                if (parsed.isEmpty()) continue
                add(ExamReminderDay(date, parsed))
            }
        }
    }

    // endregion
}
