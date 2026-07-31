package cn.jxnu.nvzhuanban.ui.widget

import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归保护：Glance `LazyColumn.items(itemId)` 把 `[Long.MIN_VALUE, Long.MIN_VALUE / 2]`
 * 留作保留区。任何落进这个区间的 itemId 会让整个 widget 显示 "Can't show content"
 * （已实际发生过；详见 [snapshotCourseItemId] 注释）。
 */
class SnapshotCourseItemIdTest {

    /** Glance 1.x 的硬编码下界，落在或低于该值的 itemId 都被拒。 */
    private val glanceReservedEnd: Long = Long.MIN_VALUE / 2

    @Test
    fun idIsAlwaysNonNegative() {
        // 多项式 hash 在 Long 域上极容易溢出到负数，长课程名 / 多字段累乘都触发过。
        // 用一组实际有可能出现的课程名 + 各种节次组合扫一遍，全部必须 >= 0。
        val names = listOf(
            "高等数学",
            "马克思主义基本原理概论",
            "Java 程序设计 (双语)",
            "大学英语Ⅳ",
            "中国近现代史纲要",
            "毛泽东思想和中国特色社会主义理论体系概论",
            "形势与政策",
            "C", // 单字符也要 OK
            "", // 空名称（防御性，理论上不会出现）
        )
        for (name in names) {
            for (weekday in 1..7) {
                for (start in 1..12) {
                    val id = snapshotCourseItemId(course(name = name, weekday = weekday, startSection = start, endSection = start))
                    assertTrue("itemId 必须非负：name=$name weekday=$weekday section=$start id=$id", id >= 0L)
                    assertTrue("itemId 必须大于 Glance 保留段上界：id=$id", id > glanceReservedEnd)
                }
            }
        }
    }

    @Test
    fun sameCourseProducesSameId() {
        val a = course(name = "数据结构", weekday = 2, startSection = 3, endSection = 4)
        val b = course(name = "数据结构", weekday = 2, startSection = 3, endSection = 4)
        assertEquals(snapshotCourseItemId(a), snapshotCourseItemId(b))
    }

    @Test
    fun differentCoursesProduceDifferentIds() {
        val base = course(name = "数据结构", weekday = 2, startSection = 3, endSection = 4)
        assertNotEquals(snapshotCourseItemId(base), snapshotCourseItemId(base.copy(name = "操作系统")))
        assertNotEquals(snapshotCourseItemId(base), snapshotCourseItemId(base.copy(weekday = 3)))
        assertNotEquals(snapshotCourseItemId(base), snapshotCourseItemId(base.copy(startSection = 5)))
        assertNotEquals(snapshotCourseItemId(base), snapshotCourseItemId(base.copy(endSection = 5)))
    }

    private fun course(
        name: String,
        weekday: Int,
        startSection: Int,
        endSection: Int,
    ) = SnapshotCourse(
        name = name,
        location = "N101",
        teacher = "T",
        startSection = startSection,
        endSection = endSection,
        weekday = weekday,
        weeks = listOf(1),
    )
}
