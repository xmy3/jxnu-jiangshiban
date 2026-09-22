package cn.jxnu.nvzhuanban.data.storage

import android.content.SharedPreferences
import cn.jxnu.nvzhuanban.data.widget.ScheduleSnapshot
import cn.jxnu.nvzhuanban.data.widget.SnapshotCourse
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

class CourseOverridesStoreTest {
    private val spring = LocalDate.of(2026, 3, 1)
    private val autumn = LocalDate.of(2026, 9, 1)

    @Test
    fun `same named courses remain independent across semesters and reloads`() {
        val prefs = preferences(mutableMapOf())
        CourseOverridesStore.initialize(prefs, ScheduleSnapshot.empty())
        CourseOverridesStore.set(spring, "大学体育", listOf(2, 1, 2))
        assertTrue(CourseOverridesStore.current(autumn).isEmpty())
        CourseOverridesStore.set(autumn, "大学体育", listOf(9, 10))
        CourseOverridesStore.initialize(prefs, ScheduleSnapshot.empty())
        assertEquals(listOf(1, 2), CourseOverridesStore.current(spring)["大学体育"])
        assertEquals(listOf(9, 10), CourseOverridesStore.get(autumn, "大学体育"))
        CourseOverridesStore.set(spring, "大学体育", null)
        assertNull(CourseOverridesStore.get(spring, "大学体育"))
        assertEquals(listOf(9, 10), CourseOverridesStore.get(autumn, "大学体育"))
        CourseOverridesStore.clearAll()
        CourseOverridesStore.initialize(prefs, ScheduleSnapshot.empty())
        assertTrue(CourseOverridesStore.current(autumn).isEmpty())
    }

    @Test
    fun `legacy settings migrate once only to snapshot semester and known courses`() {
        val values = mutableMapOf<String, Any>("weeks::大学体育" to "1,2", "weeks::未知课程" to "3")
        val prefs = preferences(values)
        val snapshot = ScheduleSnapshot.empty().copy(
            semesterStartEpochDay = spring.toEpochDay(),
            allCourses = listOf(SnapshotCourse("大学体育", "", "", 1, 2, 1, listOf(1, 2))),
        )
        CourseOverridesStore.initialize(prefs, snapshot)
        assertEquals(listOf(1, 2), CourseOverridesStore.get(spring, "大学体育"))
        assertTrue(CourseOverridesStore.current(autumn).isEmpty())
        assertFalse(values.containsKey("weeks::大学体育"))
        assertTrue(values.containsKey("weeks::未知课程"))
        CourseOverridesStore.set(spring, "大学体育", null)
        CourseOverridesStore.initialize(prefs, snapshot.copy(
            semesterStartEpochDay = autumn.toEpochDay(),
            allCourses = listOf(SnapshotCourse("未知课程", "", "", 1, 2, 1, listOf(3))),
        ))
        assertTrue(CourseOverridesStore.current(autumn).isEmpty())
        assertNull(CourseOverridesStore.get(spring, "大学体育"))
    }

    @Test
    fun `legacy data without snapshot is preserved but never applied to new semesters`() {
        val values = mutableMapOf<String, Any>("weeks::大学体育" to "1,2")
        val prefs = preferences(values)
        CourseOverridesStore.initialize(prefs, ScheduleSnapshot.empty())
        assertTrue(CourseOverridesStore.current(spring).isEmpty())
        assertTrue(values.containsKey("weeks::大学体育"))
    }

    // Android interfaces backed by an in-memory map, so persistence paths run in JVM tests.
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString", "putBoolean" -> { values[args!![0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "clear" -> { values.clear(); proxy }
                "apply" -> null
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString", "getBoolean" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
