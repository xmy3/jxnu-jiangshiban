package cn.jxnu.nvzhuanban.data.widget

import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.CourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleSnapshotTest {

    @Test
    fun filtersCoursesByDateWeekAndWeekday() {
        val start = LocalDate.of(2026, 3, 2)
        val snapshot = ScheduleSnapshot(
            semester = "2025-2026-2",
            totalWeeks = 18,
            semesterStartEpochDay = start.toEpochDay(),
            savedWeek = 1,
            allCourses = listOf(
                course("周一第一周", weekday = 1, weeks = listOf(1)),
                course("周一第二周", weekday = 1, weeks = listOf(2)),
                course("周二第一周", weekday = 2, weeks = listOf(1)),
            ),
            updatedAt = 0L,
        )

        assertEquals(listOf("周一第一周"), snapshot.coursesOn(start).map { it.name })
        assertEquals(listOf("周一第二周"), snapshot.coursesOn(start.plusWeeks(1)).map { it.name })
        assertTrue(snapshot.coursesOn(start.plusWeeks(19)).isEmpty())
    }

    @Test
    fun fallsBackToSavedWeekWhenSemesterStartIsUnknown() {
        val snapshot = ScheduleSnapshot(
            semester = "legacy",
            totalWeeks = 0,
            semesterStartEpochDay = -1L,
            savedWeek = 3,
            allCourses = listOf(course("旧快照课程", weekday = 1, weeks = listOf(3))),
            updatedAt = 0L,
        )

        assertEquals(3, snapshot.weekAt(LocalDate.of(2026, 1, 1)))
        assertEquals(listOf("旧快照课程"), snapshot.coursesOn(LocalDate.of(2026, 1, 5)).map { it.name })
    }

    @Test
    fun toCoursesRestoresFieldsForOfflineDisplay() {
        val snapshot = ScheduleSnapshot(
            semester = "2025-2026-2",
            totalWeeks = 18,
            semesterStartEpochDay = LocalDate.of(2026, 3, 2).toEpochDay(),
            savedWeek = 1,
            allCourses = listOf(course("高等数学", weekday = 1, weeks = listOf(1, 2, 3))),
            updatedAt = 0L,
        )

        val restored = snapshot.toCourses()
        assertEquals(1, restored.size)
        val c = restored[0]
        assertEquals("高等数学", c.name)
        assertEquals("N101", c.location)
        assertEquals("Teacher", c.teacher)
        assertEquals(1, c.weekday)
        assertEquals(1, c.startSection)
        assertEquals(2, c.endSection)
        assertEquals(listOf(1, 2, 3), c.weeks)
    }

    @Test
    fun courseRoundTripSurvivesJsonForOffline() {
        val origin = listOf(
            Course(
                id = "x", name = "高等数学", teacher = "王", location = "N101",
                weekday = 1, startSection = 1, endSection = 2, weeks = listOf(1, 2),
                credit = 4f, type = CourseType.LECTURE,
            ),
            Course(
                id = "y", name = "大学英语", teacher = "李", location = "W201",
                weekday = 3, startSection = 3, endSection = 4, weeks = listOf(14, 15),
                credit = 2f,
            ),
        )

        // fromCourses → toJson → fromJson → toCourses 全程不丢课、关键字段还原
        val json = ScheduleSnapshot.fromCourses("2025-2026-2", 18, LocalDate.of(2026, 3, 2), origin).toJson()
        val restored = ScheduleSnapshot.fromJson(json).toCourses()

        assertEquals(listOf("高等数学", "大学英语"), restored.map { it.name })
        val english = restored.first { it.name == "大学英语" }
        assertEquals(listOf(14, 15), english.weeks)
        assertEquals(3, english.weekday)
        assertEquals("W201", english.location)
        // 同一份还原列表内 id 稳定唯一（UI 高亮/编辑 remember key 依赖）
        assertEquals(restored.size, restored.map { it.id }.toSet().size)
    }

    @Test
    fun nextSemesterStartSurvivesJsonRoundTrip() {
        // 存进快照的是教务名义日（2026/9/1 周二），读取时对齐到第 1 周周一 8/31——
        // widget 的「距开学 N 天」「X月X日 开学」都以真实上课首日为准
        val json = ScheduleSnapshot.fromCourses(
            "2025-2026-2", 18, LocalDate.of(2026, 3, 2),
            emptyList(), nextSemesterStart = LocalDate.of(2026, 9, 1),
        ).toJson()
        assertEquals(LocalDate.of(2026, 8, 31), ScheduleSnapshot.fromJson(json).nextSemesterStart)
    }

    @Test
    fun nextSemesterStartAlignsSundayNominalForwardAndKeepsMonday() {
        // 名义 3/1（周日）→ 3/2 周一；已是周一的名义日原样返回（对齐幂等，
        // 老快照存名义日、未来若改存对齐日都能安全读取）
        val sunday = ScheduleSnapshot.fromCourses(
            "2025-2026-1", 18, LocalDate.of(2025, 9, 1),
            emptyList(), nextSemesterStart = LocalDate.of(2026, 3, 1),
        )
        assertEquals(LocalDate.of(2026, 3, 2), sunday.nextSemesterStart)
        val monday = ScheduleSnapshot.fromCourses(
            "2024-2025-2", 18, LocalDate.of(2025, 3, 2),
            emptyList(), nextSemesterStart = LocalDate.of(2025, 9, 1),
        )
        assertEquals(LocalDate.of(2025, 9, 1), monday.nextSemesterStart)
    }

    @Test
    fun nextSemesterStartIsNullWhenAbsent() {
        // 未传（教务没放出下学期）与老格式 JSON（无该字段）都应回 null，不能抛
        val json = ScheduleSnapshot.fromCourses("2025-2026-2", 18, LocalDate.of(2026, 3, 2), emptyList()).toJson()
        assertEquals(null, ScheduleSnapshot.fromJson(json).nextSemesterStart)
        assertEquals(null, ScheduleSnapshot.fromJson("""{"semester":"legacy"}""").nextSemesterStart)
    }

    private fun course(name: String, weekday: Int, weeks: List<Int>) = SnapshotCourse(
        name = name,
        location = "N101",
        teacher = "Teacher",
        startSection = 1,
        endSection = 2,
        weekday = weekday,
        weeks = weeks,
    )
}
