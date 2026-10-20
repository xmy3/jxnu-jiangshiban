package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 被后台盯着的两类数据。分开存指纹和基线，是因为两类拉取可以一成一败，不该互相拖累。 */
enum class AlertKind(val storageKey: String) {
    GRADES("grades"),
    EXAMS("exams"),
}

/** 最近一次后台检查的结果，只用于「我的」页副标题的诚实展示。 */
enum class AlertCheckResult {
    NEVER,
    OK,
    OFFLINE,
    LOGIN_REQUIRED,
    ;

    companion object {
        fun fromKey(key: String?): AlertCheckResult =
            entries.firstOrNull { it.name == key } ?: NEVER
    }
}

/**
 * 出分 / 考试变更提醒的本地状态。
 *
 * 两类数据各存一份「上次看到的指纹表」：
 *  - [known] = `课程 id → 指纹`（指纹定义见 [cn.jxnu.nvzhuanban.data.model.AlertDiff]）
 *  - [isBaselined] = 是否已经建过基线。**首次检查只落库、不弹通知** —— 否则一开开关就
 *    把整个学期的历史成绩当成"新出分"轰炸一遍。
 *
 * 换号/登出调 [clearKnown]：指纹和基线都清掉，下一个账号的第一轮检查重新静默建基线。
 * **不清 [enabled] / [examReminderEnabled]** —— 那是设备级偏好，跟着用户走会更烦（换号后还得再开一次）。
 */
object AlertPrefs {

    private const val PREF_NAME = "alert_prefs"
    private const val KEY_ENABLED = "watch_enabled"
    private const val KEY_EXAM_REMINDER = "exam_reminder_enabled"
    private const val KEY_LAST_CHECK = "last_check_epoch_ms"
    private const val KEY_LAST_RESULT = "last_result"
    /** SharedPreferences 没有 Map，一项指纹存成 `id\u0001fingerprint` 放进 StringSet。 */
    private const val FIELD_SEP = '\u0001'

    private lateinit var sp: SharedPreferences

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * 考前一天提醒（走 AlarmManager 定点闹钟，不是轮询）。
     *
     * 和 [enabled] 分开的原因：两者频率和打扰程度完全不同 —— 有人只想要「明天有考试」这种
     * 硬提醒、不想被出分通知刷屏。但两者共用同一个后台任务：考试数据得先拉到本地，闹钟才知道
     * 排哪天，所以 [anyEnabled] 才是「后台任务该不该活着」的判据。
     */
    private val _examReminderEnabled = MutableStateFlow(false)
    val examReminderEnabled: StateFlow<Boolean> = _examReminderEnabled.asStateFlow()

    /** 任一提醒开着，后台检查任务就得继续跑。 */
    val anyEnabled: Boolean
        get() = _enabled.value || _examReminderEnabled.value

    private val _lastCheckAt = MutableStateFlow(0L)
    val lastCheckAt: StateFlow<Long> = _lastCheckAt.asStateFlow()

    private val _lastResult = MutableStateFlow(AlertCheckResult.NEVER)
    val lastResult: StateFlow<AlertCheckResult> = _lastResult.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp = prefs
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _examReminderEnabled.value = prefs.getBoolean(KEY_EXAM_REMINDER, false)
        _lastCheckAt.value = prefs.getLong(KEY_LAST_CHECK, 0L)
        _lastResult.value = AlertCheckResult.fromKey(prefs.getString(KEY_LAST_RESULT, null))
    }

    fun setEnabled(value: Boolean) {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setExamReminderEnabled(value: Boolean) {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(KEY_EXAM_REMINDER, value).apply()
        _examReminderEnabled.value = value
    }

    /** `null` = 从未真正建成基线（新开启 / 换号后）。 */
    fun isBaselined(kind: AlertKind): Boolean =
        if (!::sp.isInitialized) false else sp.getBoolean(baselineKey(kind), false)

    fun markBaselined(kind: AlertKind) {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(baselineKey(kind), true).apply()
    }

    fun known(kind: AlertKind): Map<String, String> {
        if (!::sp.isInitialized) return emptyMap()
        // getStringSet 返回的可能是内部实例，必须拷出来再遍历/释放
        val raw = sp.getStringSet(knownKey(kind), null)?.toSet().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return buildMap(raw.size) {
            for (entry in raw) {
                val idx = entry.indexOf(FIELD_SEP)
                // 分隔符损坏（历史脏数据 / 手工改过 prefs）就当没这条，下一轮会重新学一遍
                if (idx <= 0) continue
                put(entry.substring(0, idx), entry.substring(idx + 1))
            }
        }
    }

    /** 整表覆盖，同时把服务端已删除的条目一起丢掉（否则成绩被撤销后永远留在已知集合里）。 */
    fun saveKnown(kind: AlertKind, fingerprints: Map<String, String>) {
        if (!::sp.isInitialized) return
        val encoded = fingerprints.entries
            .map { (id, fingerprint) -> "$id$FIELD_SEP$fingerprint" }
            .toSet()
        sp.edit().putStringSet(knownKey(kind), encoded).apply()
    }

    fun markChecked(epochMs: Long, result: AlertCheckResult) {
        if (!::sp.isInitialized) return
        sp.edit()
            .putLong(KEY_LAST_CHECK, epochMs)
            .putString(KEY_LAST_RESULT, result.name)
            .apply()
        _lastCheckAt.value = epochMs
        _lastResult.value = result
    }

    /** 换号 / 登出：清掉指纹和基线，保留开关状态。 */
    fun clearKnown() {
        if (!::sp.isInitialized) return
        sp.edit()
            .remove(knownKey(AlertKind.GRADES))
            .remove(knownKey(AlertKind.EXAMS))
            .remove(baselineKey(AlertKind.GRADES))
            .remove(baselineKey(AlertKind.EXAMS))
            .remove(KEY_LAST_CHECK)
            .remove(KEY_LAST_RESULT)
            .apply()
        _lastCheckAt.value = 0L
        _lastResult.value = AlertCheckResult.NEVER
    }

    private fun knownKey(kind: AlertKind) = "known_${kind.storageKey}"

    private fun baselineKey(kind: AlertKind) = "baselined_${kind.storageKey}"
}
