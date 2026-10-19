package cn.jxnu.nvzhuanban.ui.screens.announcement

import cn.jxnu.nvzhuanban.data.model.Announcement
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 通知列表的时间分组。
 *
 * 分区是**互斥且按时间连续**的：今天 → 昨天 → 本周（周一至前天）→ 月份。
 * 列表本身按日期降序，所以同一组的条目一定连续，吸顶分组头才有意义。
 */
sealed interface AnnouncementGroup {
    data object Today : AnnouncementGroup
    data object Yesterday : AnnouncementGroup
    data object ThisWeek : AnnouncementGroup

    /** 更早的日期按自然月归档；跨年时 UI 会带上年份，避免「8月」看着像今年。 */
    data class Month(val year: Int, val month: Int) : AnnouncementGroup
}

/** 稳定 key（不随语言/文案变化），用于 LazyColumn 的 header key。 */
internal val AnnouncementGroup.stableKey: String
    get() = when (this) {
        AnnouncementGroup.Today -> "today"
        AnnouncementGroup.Yesterday -> "yesterday"
        AnnouncementGroup.ThisWeek -> "this-week"
        is AnnouncementGroup.Month -> "month-$year-$month"
    }

/**
 * 铺平后的列表行：分组头 + 条目交替出现。
 *
 * LazyColumn 的 item key 必须全局唯一：header 的 key 带出现序号——正常数据不会重复分组，
 * 但万一上游给了未排序的列表，「8月 → 9月 → 8月」会撞出两个相同 key 直接崩，
 * 序号让最坏情况退化成视觉重复而不是崩溃。
 */
sealed interface AnnouncementRow {
    val key: String

    data class Header(
        val group: AnnouncementGroup,
        val occurrence: Int = 0,
    ) : AnnouncementRow {
        override val key: String get() = "header:${group.stableKey}:$occurrence"
    }

    data class Item(val announcement: Announcement) : AnnouncementRow {
        override val key: String get() = announcement.uniqueKey
    }
}

/**
 * 把已按日期降序排好的 [announcements] 切成「分组头 + 条目」的扁平列表。
 *
 * @param today 当前日期，用于决定今天/昨天/本周的边界（屏幕层在跨零点时会更新它）。
 */
internal fun groupAnnouncements(
    announcements: List<Announcement>,
    today: LocalDate,
): List<AnnouncementRow> {
    if (announcements.isEmpty()) return emptyList()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val rows = ArrayList<AnnouncementRow>(announcements.size + 4)
    val occurrences = HashMap<String, Int>()
    var current: AnnouncementGroup? = null
    for (announcement in announcements) {
        val group = groupOf(announcement.date, today, weekStart)
        if (group != current) {
            val stable = group.stableKey
            val occurrence = occurrences[stable] ?: 0
            occurrences[stable] = occurrence + 1
            rows += AnnouncementRow.Header(group, occurrence)
            current = group
        }
        rows += AnnouncementRow.Item(announcement)
    }
    return rows
}

private fun groupOf(
    date: LocalDate,
    today: LocalDate,
    weekStart: LocalDate,
): AnnouncementGroup = when {
    date == today -> AnnouncementGroup.Today
    date == today.minusDays(1) -> AnnouncementGroup.Yesterday
    // 本周一（含）到今天（不含）之间；今天是周一时这一段自然为空
    !date.isBefore(weekStart) && date.isBefore(today) -> AnnouncementGroup.ThisWeek
    else -> AnnouncementGroup.Month(date.year, date.monthValue)
}
