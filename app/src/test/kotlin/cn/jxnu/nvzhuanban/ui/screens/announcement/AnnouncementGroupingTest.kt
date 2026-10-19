package cn.jxnu.nvzhuanban.ui.screens.announcement

import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知列表时间分组的纯逻辑测试。
 *
 * 分组决定吸顶标题与条目顺序，改规则时这里必须先红：
 * 分区必须互斥且按时间连续，否则「今天」下面会冒出「本周」再回到「今天」。
 */
class AnnouncementGroupingTest {

    @Test
    fun `today yesterday this week and months form consecutive sections`() {
        // 2026-10-21 是周三 → 本周一 = 2026-10-19
        val today = LocalDate.of(2026, 10, 21)
        val dates = listOf(
            LocalDate.of(2026, 10, 21), // 今天
            LocalDate.of(2026, 10, 20), // 昨天
            LocalDate.of(2026, 10, 19), // 本周一
            LocalDate.of(2026, 10, 18), // 上周日 → 本月
            LocalDate.of(2025, 12, 31), // 去年 → 带年份的月份
        )
        val rows = groupAnnouncements(dates.mapIndexed { index, date -> item("a$index", date) }, today)

        assertEquals(
            listOf(
                AnnouncementGroup.Today,
                AnnouncementGroup.Yesterday,
                AnnouncementGroup.ThisWeek,
                AnnouncementGroup.Month(2026, 10),
                AnnouncementGroup.Month(2025, 12),
            ),
            rows.filterIsInstance<AnnouncementRow.Header>().map { it.group },
        )
        // 每个日期一条，header 与 item 交替
        assertEquals(dates.size * 2, rows.size)
        assertEquals(5, rows.filterIsInstance<AnnouncementRow.Item>().size)
    }

    @Test
    fun `monday has no this-week bucket between yesterday and last week`() {
        // 2026-10-19 是周一：本周一就是今天，昨天（周日）已经是上一周，
        // 「本周」这一档必须整档缺席，而不是把周日收进去
        val today = LocalDate.of(2026, 10, 19)
        val rows = groupAnnouncements(
            listOf(
                item("today", today),
                item("yesterday", today.minusDays(1)),
                item("saturday", today.minusDays(2)),
            ),
            today,
        )

        assertEquals(
            listOf(
                AnnouncementGroup.Today,
                AnnouncementGroup.Yesterday,
                AnnouncementGroup.Month(2026, 10),
            ),
            rows.filterIsInstance<AnnouncementRow.Header>().map { it.group },
        )
        assertTrue(rows.none { it is AnnouncementRow.Header && it.group == AnnouncementGroup.ThisWeek })
    }

    @Test
    fun `header keys stay unique even when input is out of order`() {
        // 正常上游按日期降序；万一乱序导致同一分组被拆成两段，key 也不能撞
        val today = LocalDate.of(2026, 10, 21)
        val rows = groupAnnouncements(
            listOf(
                item("a", LocalDate.of(2026, 10, 21)),
                item("b", LocalDate.of(2026, 9, 1)),
                item("c", LocalDate.of(2026, 10, 20)),
            ),
            today,
        )

        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `empty list produces no rows`() {
        assertTrue(groupAnnouncements(emptyList(), LocalDate.of(2026, 10, 21)).isEmpty())
    }

    @Test
    fun `item keys use the cross type unique key`() {
        val today = LocalDate.of(2026, 10, 21)
        val rows = groupAnnouncements(
            listOf(item("42", today, AnnouncementType.PICTURE_NEWS)),
            today,
        )

        assertEquals("PICTURE_NEWS:42", rows.filterIsInstance<AnnouncementRow.Item>().single().key)
    }

    private fun item(
        id: String,
        date: LocalDate,
        type: AnnouncementType = AnnouncementType.NOTIFICATION,
    ) = Announcement(id = id, title = "标题 $id", date = date, type = type)
}
