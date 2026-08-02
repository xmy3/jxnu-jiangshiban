package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate

/**
 * 大一晚自习的领域逻辑单一来源。
 *
 * 背景：江师大只有大一年级有晚自习（大一上 + 大一下），固定 19:00–21:00，一周至多 3 次，
 * 具体周几由各学院自定 → 教务网课表完全不体现，需要用户在 App 里自选周几。
 *
 * 实现取向：晚自习不引入新的网格实体，而是被 [synthesize] 合成为特殊 [Course]
 * （id 前缀 [ID_PREFIX]、占第 [START_SECTION]–[END_SECTION] 节）由 ScheduleRepository
 * 注入课表列表 —— 网格渲染 / 周末列折叠 / widget 快照 / 离线兜底全部自动生效。
 * 「某周该天晚上有正课则晚自习让位」直接编码进合成 Course 的 weeks（去掉被占用的周），
 * 下游按 [Course.isInWeek] 过滤时自然隐藏，无需任何额外冲突判断。
 */
object EveningStudy {

    const val NAME = "晚自习"

    /** 合成 Course 的 id 前缀，后随 weekday（如 `evening-study-3`）。 */
    const val ID_PREFIX = "evening-study-"

    /** 晚自习覆盖的节次区间：第 10–12 节（19:00 起）。 */
    const val START_SECTION = 10
    const val END_SECTION = 12

    const val START_LABEL = "19:00"
    const val END_LABEL = "21:00"

    /**
     * 实际结束时刻 21:00 的当日分钟数。注意它**不是**第 12 节的下课时间（21:20）——
     * 网格「上课中」高亮等 App 内判定用本值；widget 调度层刻意不特判（见方案取舍），
     * 沿用节次边界最多让「进行中」标记多挂 20 分钟。
     */
    const val END_MINUTES = 21 * 60

    /** 可选的周几（1=周一 … 7=周日）：周六没有晚自习。 */
    val ALLOWED_DAYS: List<Int> = listOf(1, 2, 3, 4, 5, 7)

    /** 一周至多几天晚自习。 */
    const val MAX_DAYS = 3

    /** 晚自习教室固定在 W 楼（校方安排），用户只填 [ROOM_DIGITS] 位数字教室号。 */
    const val ROOM_PREFIX = "W"

    /** 教室号位数（如 `1203` → 显示 `W1203`）。 */
    const val ROOM_DIGITS = 4

    /**
     * [digits] 是否是合法的教室号输入（恰好 [ROOM_DIGITS] 位 ASCII 数字）。
     * 刻意不用 [Char.isDigit]——它对全角 '０'..'９' 等 Unicode Nd 类也返回 true，
     * 会把「W１２０３」这类粘贴输入放行进存储。
     */
    fun isValidRoomDigits(digits: String): Boolean =
        digits.length == ROOM_DIGITS && digits.all { it in '0'..'9' }

    /** 教室号 → 完整教室名（`1203` → `W1203`）；非法输入返回 null。 */
    fun roomLabel(digits: String?): String? =
        digits?.takeIf { isValidRoomDigits(it) }?.let { ROOM_PREFIX + it }

    /**
     * 学期是否属于 [studentId] 的大一学年。学号前 4 位是入学年份（与
     * UserDefaultPage.inferGradeFromStudentId 同源），大一学年 = [入学年 8/1, 次年 8/1)。
     * 学号异常（教工号、降级 profile 的任意用户名）或开学日缺失一律 false —— 功能整体不出现。
     */
    fun isFreshmanSemester(studentId: String?, semesterStart: LocalDate?): Boolean {
        if (studentId == null || semesterStart == null) return false
        val enrollYear = studentId.take(4).toIntOrNull() ?: return false
        if (enrollYear !in 2000..2099) return false
        return !semesterStart.isBefore(LocalDate.of(enrollYear, 8, 1)) &&
            semesterStart.isBefore(LocalDate.of(enrollYear + 1, 8, 1))
    }

    /** [course] 的晚间节次是否与晚自习时段（第 10–12 节）重叠。 */
    private fun overlapsEvening(course: Course): Boolean =
        course.startSection <= END_SECTION && course.endSection >= START_SECTION

    /**
     * [day] 晚间（第 10–12 节）被正课占用的周集合。晚自习合成卡自身被排除——
     * 调用方传入的列表可能已含注入结果（如编辑面板用全学期课表算禁用天），不能自阻塞。
     */
    private fun blockedWeeks(day: Int, courses: List<Course>): Set<Int> =
        courses.asSequence()
            .filter { !it.isEveningStudy && it.weekday == day && overlapsEvening(it) }
            .flatMap { it.weeks }
            .toSet()

    /**
     * 整学期晚间都被正课占满、晚自习完全排不进的天（教务默认周次 1..18 时一门晚间课就够）。
     * 编辑面板用它**禁用对应天格**——否则用户勾了全占天保存后一张卡都不生成，而占位卡又因
     * 「已设置」不再显示，改/清设置的入口就此消失（设置死角）。
     */
    fun fullyBlockedDays(totalWeeks: Int, courses: List<Course>): Set<Int> {
        if (totalWeeks <= 0) return emptySet()
        return ALLOWED_DAYS.filterTo(mutableSetOf()) { day ->
            val blocked = blockedWeeks(day, courses)
            (1..totalWeeks).all { it in blocked }
        }
    }

    /**
     * 把用户选的周几合成为待注入课表的晚自习 Course 列表。
     *
     * 每天一张卡：weeks = 1..[totalWeeks] 减去该天晚间（第 10–12 节）有正课的周 ——
     * 「正课优先自动让位」。整学期都被占的天不生成卡。[days] 中不在 [ALLOWED_DAYS]
     * 的值（防御脏数据）被忽略。[roomDigits] 是用户填的 W 楼教室号（选填）——
     * 合法时写进 location（`W1203`），卡片 / widget / 离线快照沿既有地点通路自动展示。
     */
    fun synthesize(
        days: Set<Int>,
        totalWeeks: Int,
        courses: List<Course>,
        roomDigits: String? = null,
    ): List<Course> {
        if (days.isEmpty() || totalWeeks <= 0) return emptyList()
        val location = roomLabel(roomDigits).orEmpty()
        return days.filter { it in ALLOWED_DAYS }.sorted().mapNotNull { day ->
            val blocked = blockedWeeks(day, courses)
            val weeks = (1..totalWeeks).filter { it !in blocked }
            if (weeks.isEmpty()) return@mapNotNull null
            Course(
                id = "$ID_PREFIX$day",
                name = NAME,
                teacher = "",
                location = location,
                weekday = day,
                startSection = START_SECTION,
                endSection = END_SECTION,
                weeks = weeks,
                credit = 0f,
            )
        }
    }

    /**
     * 「设置晚自习」引导占位卡应落在哪一列：按 周一→周五→周日 找第一个
     * 未被折叠、且当周晚间（第 10–12 节）无正课的天；全部不可用返回 null（不显示占位）。
     * [visibleCourses] 是**当前选中周**已过滤的课表（晚自习卡不算占用）。
     */
    fun placeholderDay(visibleCourses: List<Course>, foldedDays: Set<Int>): Int? =
        ALLOWED_DAYS.firstOrNull { day ->
            day !in foldedDays &&
                visibleCourses.none { !it.isEveningStudy && it.weekday == day && overlapsEvening(it) }
        }
}

/**
 * 这张卡是不是晚自习。id 前缀是主判据；name 兜底覆盖离线快照往返后 id 被合成为
 * `offline-…` 的场景（教务正课不可能叫「晚自习」——教务不排晚自习正是本功能的由来）。
 */
val Course.isEveningStudy: Boolean
    get() = id.startsWith(EveningStudy.ID_PREFIX) || name == EveningStudy.NAME
