package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EveningStudyTest {

    // ---- isFreshmanSemester：大一学年 = [入学年 8/1, 次年 8/1) ----

    @Test
    fun `大一上（入学年秋季学期）命中`() {
        assertTrue(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2024, 9, 1)))
    }

    @Test
    fun `大一下（次年春季学期）命中`() {
        assertTrue(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2025, 3, 1)))
    }

    @Test
    fun `大二上不命中`() {
        assertFalse(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2025, 9, 1)))
    }

    @Test
    fun `入学前的学期不命中`() {
        assertFalse(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2024, 3, 1)))
    }

    @Test
    fun `学年边界：8月1日起算、次年8月1日截止`() {
        assertTrue(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2024, 8, 1)))
        assertFalse(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2025, 8, 1)))
        assertFalse(EveningStudy.isFreshmanSemester("2024050001", LocalDate.of(2024, 7, 31)))
    }

    @Test
    fun `异常学号一律不命中`() {
        // 教工号（前 4 位 0200 不是合法入学年份）
        assertFalse(EveningStudy.isFreshmanSemester("020001", LocalDate.of(2024, 9, 1)))
        // 非数字（降级 profile 的任意用户名）
        assertFalse(EveningStudy.isFreshmanSemester("abcd050001", LocalDate.of(2024, 9, 1)))
        assertFalse(EveningStudy.isFreshmanSemester("", LocalDate.of(2024, 9, 1)))
        assertFalse(EveningStudy.isFreshmanSemester(null, LocalDate.of(2024, 9, 1)))
    }

    @Test
    fun `开学日缺失不命中`() {
        assertFalse(EveningStudy.isFreshmanSemester("2024050001", null))
    }

    // ---- synthesize：合成注入 + 正课让位编码进 weeks ----

    private fun course(
        weekday: Int,
        startSection: Int,
        endSection: Int,
        weeks: List<Int> = (1..18).toList(),
        name: String = "正课",
    ) = Course(
        id = "c-$weekday-$startSection",
        name = name,
        teacher = "张三",
        location = "教学楼101",
        weekday = weekday,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        credit = 0f,
    )

    @Test
    fun `无冲突时每天一张卡且覆盖全学期`() {
        val cards = EveningStudy.synthesize(setOf(1, 3, 7), totalWeeks = 18, courses = emptyList())
        assertEquals(listOf(1, 3, 7), cards.map { it.weekday })
        cards.forEach { c ->
            assertEquals(EveningStudy.NAME, c.name)
            assertEquals("${EveningStudy.ID_PREFIX}${c.weekday}", c.id)
            assertEquals(EveningStudy.START_SECTION, c.startSection)
            assertEquals(EveningStudy.END_SECTION, c.endSection)
            assertEquals((1..18).toList(), c.weeks)
            assertTrue(c.isEveningStudy)
        }
    }

    @Test
    fun `晚间有正课的周被让位`() {
        // 周一晚 10-11 节正课只在第 5、6 周上 → 晚自习卡的 weeks 缺这两周，其余周照常
        val evening = course(weekday = 1, startSection = 10, endSection = 11, weeks = listOf(5, 6))
        val cards = EveningStudy.synthesize(setOf(1), 18, listOf(evening))
        assertEquals((1..18).filter { it != 5 && it != 6 }, cards.single().weeks)
    }

    @Test
    fun `让位只看晚间区间重叠的课`() {
        // 第 1-9 节的课与晚自习（10-12）不重叠，不让位；跨到第 10 节 / 只占第 12 节的都让位
        val daytime = course(weekday = 2, startSection = 1, endSection = 9, weeks = listOf(3))
        val crossing = course(weekday = 2, startSection = 9, endSection = 10, weeks = listOf(4))
        val lastOnly = course(weekday = 2, startSection = 12, endSection = 12, weeks = listOf(7))
        val cards = EveningStudy.synthesize(setOf(2), 18, listOf(daytime, crossing, lastOnly))
        assertEquals((1..18).filter { it != 4 && it != 7 }, cards.single().weeks)
    }

    @Test
    fun `让位不跨天`() {
        // 周二晚上的正课不影响周四的晚自习
        val tuesdayEvening = course(weekday = 2, startSection = 10, endSection = 12, weeks = listOf(1, 2))
        val cards = EveningStudy.synthesize(setOf(4), 18, listOf(tuesdayEvening))
        assertEquals((1..18).toList(), cards.single().weeks)
    }

    @Test
    fun `整学期晚间全被占的天不生成卡`() {
        val full = course(weekday = 1, startSection = 10, endSection = 12, weeks = (1..18).toList())
        val cards = EveningStudy.synthesize(setOf(1, 3), 18, listOf(full))
        assertEquals(listOf(3), cards.map { it.weekday })
    }

    @Test
    fun `让位计算无视已注入的晚自习卡（不自阻塞）`() {
        // 传入的课表可能已含上一轮注入的合成卡（如面板用全学期课表算禁用天）——
        // 晚自习卡自己不能算"占用"，否则重算时会把自己让位掉
        val previous = EveningStudy.synthesize(setOf(1), 18, emptyList()).single()
        val cards = EveningStudy.synthesize(setOf(1), 18, listOf(previous))
        assertEquals((1..18).toList(), cards.single().weeks)
        assertEquals(1, EveningStudy.placeholderDay(listOf(previous), emptySet()))
    }

    // ---- fullyBlockedDays：编辑面板禁用「整学期全占」天 ----

    @Test
    fun `fullyBlockedDays 只报整学期被占满的天`() {
        val fullMonday = course(weekday = 1, startSection = 10, endSection = 12, weeks = (1..18).toList())
        val partialTuesday = course(weekday = 2, startSection = 10, endSection = 11, weeks = listOf(5, 6))
        val blocked = EveningStudy.fullyBlockedDays(18, listOf(fullMonday, partialTuesday))
        assertEquals(setOf(1), blocked)
    }

    @Test
    fun `fullyBlockedDays 可由多门课拼满`() {
        // 周三晚由两门课分别覆盖 1..9 与 10..18 → 整学期无空
        val first = course(weekday = 3, startSection = 10, endSection = 11, weeks = (1..9).toList())
        val second = course(weekday = 3, startSection = 12, endSection = 12, weeks = (10..18).toList())
        assertEquals(setOf(3), EveningStudy.fullyBlockedDays(18, listOf(first, second)))
    }

    @Test
    fun `fullyBlockedDays 无视晚自习卡与白天课`() {
        val evening = EveningStudy.synthesize(setOf(1), 18, emptyList()).single()
        val daytime = course(weekday = 2, startSection = 1, endSection = 9, weeks = (1..18).toList())
        assertEquals(emptySet<Int>(), EveningStudy.fullyBlockedDays(18, listOf(evening, daytime)))
    }

    @Test
    fun `非法周几被忽略`() {
        // 周六（6）与越界值不生成卡
        val cards = EveningStudy.synthesize(setOf(1, 6, 0, 8), 18, emptyList())
        assertEquals(listOf(1), cards.map { it.weekday })
    }

    @Test
    fun `空选择或非法周数返回空`() {
        assertTrue(EveningStudy.synthesize(emptySet(), 18, emptyList()).isEmpty())
        assertTrue(EveningStudy.synthesize(setOf(1), 0, emptyList()).isEmpty())
    }

    // ---- 教室（锁死 W 楼，4 位数字号，选填） ----

    @Test
    fun `教室号校验：恰好四位数字才合法`() {
        assertTrue(EveningStudy.isValidRoomDigits("1203"))
        assertTrue(EveningStudy.isValidRoomDigits("0001"))
        assertFalse(EveningStudy.isValidRoomDigits(""))
        assertFalse(EveningStudy.isValidRoomDigits("120"))
        assertFalse(EveningStudy.isValidRoomDigits("12034"))
        assertFalse(EveningStudy.isValidRoomDigits("12a3"))
        assertFalse(EveningStudy.isValidRoomDigits("W123"))
        // 全角数字（粘贴常见）：Char.isDigit 会放行 Unicode Nd 类，这里必须拒绝——
        // 否则「W１２０３」进存储后卡片/widget 显示混排字形
        assertFalse(EveningStudy.isValidRoomDigits("１２０３"))
    }

    @Test
    fun `roomLabel 拼 W 前缀且拒绝非法输入`() {
        assertEquals("W1203", EveningStudy.roomLabel("1203"))
        assertNull(EveningStudy.roomLabel("120"))
        assertNull(EveningStudy.roomLabel(""))
        assertNull(EveningStudy.roomLabel(null))
    }

    @Test
    fun `合成卡携带教室，未填或非法则地点为空`() {
        val withRoom = EveningStudy.synthesize(setOf(1), 18, emptyList(), roomDigits = "1203")
        assertEquals("W1203", withRoom.single().location)
        val noRoom = EveningStudy.synthesize(setOf(1), 18, emptyList(), roomDigits = null)
        assertEquals("", noRoom.single().location)
        val badRoom = EveningStudy.synthesize(setOf(1), 18, emptyList(), roomDigits = "12")
        assertEquals("", badRoom.single().location)
    }

    @Test
    fun `合成卡按周过滤后与正课互斥`() {
        // 端到端语义：第 5 周（有正课）过滤后无晚自习，第 6 周有
        val evening = course(weekday = 1, startSection = 10, endSection = 12, weeks = listOf(5))
        val all = listOf(evening) + EveningStudy.synthesize(setOf(1), 18, listOf(evening))
        val week5 = all.filter { it.isInWeek(5) }
        val week6 = all.filter { it.isInWeek(6) }
        assertEquals(listOf("正课"), week5.map { it.name })
        assertEquals(listOf(EveningStudy.NAME), week6.map { it.name })
    }

    // ---- isEveningStudy 判据 ----

    @Test
    fun `判据认 id 前缀也认名称`() {
        val synthesized = EveningStudy.synthesize(setOf(1), 18, emptyList()).single()
        assertTrue(synthesized.isEveningStudy)
        // 离线快照往返后 id 会被合成为 offline-…，靠 name 兜底
        assertTrue(synthesized.copy(id = "offline-1-10-12-晚自习").isEveningStudy)
        assertFalse(course(weekday = 1, startSection = 10, endSection = 12).isEveningStudy)
    }

    // ---- placeholderDay：占位卡落点 ----

    @Test
    fun `空课表落在周一`() {
        assertEquals(1, EveningStudy.placeholderDay(emptyList(), emptySet()))
    }

    @Test
    fun `周一晚被占时顺延到周二`() {
        val monday = course(weekday = 1, startSection = 10, endSection = 12)
        assertEquals(2, EveningStudy.placeholderDay(listOf(monday), emptySet()))
    }

    @Test
    fun `白天的课不影响落点`() {
        val monday = course(weekday = 1, startSection = 1, endSection = 9)
        assertEquals(1, EveningStudy.placeholderDay(listOf(monday), emptySet()))
    }

    @Test
    fun `跳过被折叠的列且永不落在周六`() {
        // 工作日晚间全被占、周日被折叠 → 无处可放
        val busy = (1..5).map { course(weekday = it, startSection = 10, endSection = 12) }
        assertNull(EveningStudy.placeholderDay(busy, foldedDays = setOf(6, 7)))
        // 周日未折叠时落在周日
        assertEquals(7, EveningStudy.placeholderDay(busy, foldedDays = emptySet()))
    }

    // ---- 常量一致性 ----

    @Test
    fun `实际结束时刻是 21 点而非第 12 节下课`() {
        assertEquals(21 * 60, EveningStudy.END_MINUTES)
        // 第 12 节 20:40 + 40min = 21:20，晚自习实际 21:00 结束——两者刻意不同
        assertTrue(EveningStudy.END_MINUTES < SectionTimetable.endMinutes(EveningStudy.END_SECTION))
        // 但开始时刻与第 10 节严格对齐
        assertEquals(
            SectionTimetable.startMinutes(EveningStudy.START_SECTION),
            19 * 60,
        )
    }

    @Test
    fun `可选天不含周六且上限三天`() {
        assertFalse(6 in EveningStudy.ALLOWED_DAYS)
        assertEquals(setOf(1, 2, 3, 4, 5, 7), EveningStudy.ALLOWED_DAYS.toSet())
        assertEquals(3, EveningStudy.MAX_DAYS)
    }
}
