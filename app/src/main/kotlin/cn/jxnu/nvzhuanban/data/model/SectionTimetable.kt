package cn.jxnu.nvzhuanban.data.model

/**
 * 江师大《本科教学作息时间表》单一数据源。
 * 来源：jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=4123（"自2013-2014学年第二学期开始执行"）。
 *
 * 课表网格、widget、"进行中/下一节"高亮统一用这一份；之前 ScheduleScreen 和 TodayScheduleWidget
 * 各维护一份，且都把午休错算成 1 节，下午全部错位 —— 把它们抽到这里防止再次漂移。
 */
object SectionTimetable {
    /** 第 1-12 节的开始时间。下标是 section-1。 */
    val startTimes: List<String> = listOf(
        "08:00", "08:50",         // 1-2
        "09:40", "10:30",         // 3-4
        "11:20",                  // 5
        "14:00", "14:50",         // 6-7
        "15:40", "16:30",         // 8-9
        "19:00", "19:50", "20:40", // 10-12
    )

    const val DURATION_MINUTES: Int = 40

    val SECTION_COUNT: Int get() = startTimes.size

    private val startMinutesArr: IntArray = startTimes.map { t ->
        val (h, m) = t.split(":").map { it.toInt() }
        h * 60 + m
    }.toIntArray()

    /** 第 [section] 节的开始时间（分钟，自 00:00 计）。 */
    fun startMinutes(section: Int): Int =
        startMinutesArr[(section - 1).coerceIn(startMinutesArr.indices)]

    /** 第 [section] 节的结束时间（分钟，自 00:00 计）。 */
    fun endMinutes(section: Int): Int = startMinutes(section) + DURATION_MINUTES

    /** 第 [section] 节的显示开始时间，如 `"14:00"`。 */
    fun startTimeLabel(section: Int): String =
        startTimes[(section - 1).coerceIn(startTimes.indices)]

    /** 第 [section] 节的显示结束时间，如 `"14:40"`（开始时间 + [DURATION_MINUTES] 分钟）。 */
    fun endTimeLabel(section: Int): String {
        val mins = endMinutes(section)
        return "${(mins / 60).toString().padStart(2, '0')}:${(mins % 60).toString().padStart(2, '0')}"
    }
}
