package cn.jxnu.nvzhuanban.data.model

/**
 * 「出分 / 考试变更」提醒的**纯比对层**。
 *
 * 这个 App 没有自建服务端，教务系统也不会推送，所以「有人出了新成绩」这件事只能由客户端
 * 周期性地把整页数据拉回来、和上次记下的指纹比。比对逻辑（尤其是"什么算变化"）是这里唯一
 * 容易做错、又最容易悄悄做错的部分 —— 它不碰 Android / 网络，可以直接在 JVM 单测里跑，
 * 见 `AlertDiffTest`。
 *
 * 指纹的设计原则：**只包含会出现在通知文案里的字段**。
 *  - 成绩：分数 / 标准分 / 补考分。备注、交流学校变了不打扰用户。
 *  - 考试：日期 / 地点 / 座位 / 备注 / 考核方式。教务网那串不可靠的 `HH:mm` 不进指纹，
 *    否则它会自己抖一次就报一次"考试变更"（见 `Exam.startTime` 的注释）。
 */

/** 一次比对的结果。顺序沿用输入列表的原始顺序，通知文案取前几条即可。 */
data class AlertChange<T>(
    val added: List<T> = emptyList(),
    val changed: List<T> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty()
    val count: Int get() = added.size + changed.size
}

object AlertDiff {

    fun gradeFingerprint(grade: Grade): String = buildString {
        append(grade.score)
        append('|').append(grade.gpa?.toString() ?: "-")
        append('|').append(grade.makeupScore ?: "-")
    }

    fun examFingerprint(exam: Exam): String = buildString {
        append(exam.startTime.toLocalDate())
        append('|').append(exam.location)
        append('|').append(exam.seat ?: "-")
        append('|').append(exam.remark ?: "-")
        append('|').append(exam.examType)
    }

    fun gradeFingerprints(grades: List<Grade>): Map<String, String> =
        grades.distinctBy { it.id }.associate { it.id to gradeFingerprint(it) }

    fun examFingerprints(exams: List<Exam>): Map<String, String> =
        exams.distinctBy(::examKey).associate { examKey(it) to examFingerprint(it) }

    /** 新出现的课程 = 出分；同一门课指纹变了 = 成绩被修改 / 补考出分。 */
    fun grades(known: Map<String, String>, current: List<Grade>): AlertChange<Grade> =
        diff(known, current, { it.id }, ::gradeFingerprint)

    /** 新出现的考试 = 教务新排的；指纹变了 = 改期 / 换考场 / 换座位。 */
    fun exams(known: Map<String, String>, current: List<Exam>): AlertChange<Exam> =
        diff(normalizeExamKeys(known), current, ::examKey, ::examFingerprint)

    private fun examKey(exam: Exam): String =
        if (exam.courseCode.isNotBlank()) "course:${exam.courseCode}" else "id:${exam.id}"

    // 旧版本的基线 key 是「课程号-完整时间」，升级后仍需识别，避免整批误报新增。
    private val legacyExamKey = Regex("""^(.+)-\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""")

    private fun normalizeExamKeys(known: Map<String, String>): Map<String, String> =
        known.mapKeys { (key, _) ->
            legacyExamKey.matchEntire(key)?.groupValues?.get(1)?.let { "course:$it" } ?: key
        }

    private fun <T> diff(
        known: Map<String, String>,
        current: List<T>,
        idOf: (T) -> String,
        fingerprintOf: (T) -> String,
    ): AlertChange<T> {
        val added = mutableListOf<T>()
        val changed = mutableListOf<T>()
        // 服务端理论上不会给重复 id；真给了也只在第一次出现时判定，避免同一条重复进通知
        val seen = HashSet<String>(current.size)
        for (item in current) {
            val id = idOf(item)
            if (!seen.add(id)) continue
            val previous = known[id]
            when {
                previous == null -> added += item
                previous != fingerprintOf(item) -> changed += item
            }
        }
        return AlertChange(added = added, changed = changed)
    }
}
