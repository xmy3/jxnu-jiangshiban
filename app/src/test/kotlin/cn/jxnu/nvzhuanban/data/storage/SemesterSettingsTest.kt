package cn.jxnu.nvzhuanban.data.storage

import android.content.SharedPreferences
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.repository.ScheduleRepository
import cn.jxnu.nvzhuanban.data.widget.ScheduleSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

class SemesterSettingsTest {
    private val spring = LocalDate.of(2026, 3, 1)
    private val autumn = LocalDate.of(2026, 9, 1)
    private val springMonday = LocalDate.of(2026, 3, 9)
    private val autumnMonday = LocalDate.of(2026, 9, 7)
    private val values = mutableMapOf<String, Any>()
    private val prefs = preferences(values)

    @Before
    fun initialize() {
        SemesterSettings.initialize(prefs)
        CourseOverridesStore.initialize(preferences(mutableMapOf()), ScheduleSnapshot.empty())
    }

    @After
    fun clear() {
        SemesterSettings.clearAll()
        CourseOverridesStore.clearAll()
    }

    @Test
    fun `semester settings survive reload and resetting another semester`() {
        SemesterSettings.save(spring, springMonday, 20)
        SemesterSettings.save(autumn, autumnMonday, 16)
        SemesterSettings.initialize(prefs)
        assertEquals(springMonday, SemesterSettings.weekOneMondayFor(spring))
        assertEquals(20, SemesterSettings.totalWeeksFor(spring, 18))
        assertEquals(autumnMonday, SemesterSettings.weekOneMondayFor(autumn))
        SemesterSettings.clear(autumn)
        SemesterSettings.initialize(prefs)
        assertNull(SemesterSettings.overrideFor(autumn))
        assertEquals(20, SemesterSettings.totalWeeksFor(spring, 18))
        SemesterSettings.clearAll()
        SemesterSettings.initialize(prefs)
        assertNull(SemesterSettings.overrideFor(spring))
        assertEquals(18, SemesterSettings.totalWeeksFor(spring, 18))
    }

    @Test
    fun `invalid dates and week counts cannot replace a valid correction`() {
        SemesterSettings.save(spring, springMonday, null)
        assertThrows(IllegalArgumentException::class.java) {
            SemesterSettings.save(spring, springMonday.plusDays(1), 20)
        }
        for (weeks in listOf(0, 31)) {
            assertThrows(IllegalArgumentException::class.java) {
                SemesterSettings.save(spring, springMonday, weeks)
            }
        }
        SemesterSettings.initialize(prefs)
        assertEquals(springMonday, SemesterSettings.weekOneMondayFor(spring))
        assertEquals(17, SemesterSettings.totalWeeksFor(spring, 17))
    }

    @Test
    fun `damaged preferences do not crash startup or apply a non Monday anchor`() {
        values["monday::$spring"] = 10
        values["monday::$autumn"] = "2026-09-08"
        values["weeks::$autumn"] = "broken"
        SemesterSettings.initialize(prefs)
        assertNull(SemesterSettings.overrideFor(spring))
        assertNull(SemesterSettings.overrideFor(autumn))
        assertEquals(LocalDate.of(2026, 8, 31), SemesterSettings.weekOneMondayFor(autumn))
    }

    @Test
    fun `extending a cached semester extends default courses but preserves edited weeks`() {
        val course = Course("c", "高数", "教师", "W1203", 1, 1, 2, (1..18).toList(), 2f)
        val repo = ScheduleRepository()
        ScheduleRepository::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .set(repo, SchedulePage.Parsed("春季", spring, emptyList(), listOf(course)))
        SemesterSettings.save(spring, springMonday, 20)

        assertEquals((1..20).toList(), repo.cachedSchedule()!!.single().weeks)
        assertEquals(SemesterPhase.InProgress(1), repo.currentPhase(springMonday.plusDays(1)))
        assertEquals(spring, repo.currentSemesterStart())
        assertEquals(18, repo.schoolTotalWeeks())

        CourseOverridesStore.set(spring, "高数", listOf(1, 3))
        assertEquals(listOf(1, 3), repo.cachedSchedule()!!.single().weeks)
        SemesterSettings.clear(spring)
        assertEquals(listOf(1, 3), repo.cachedSchedule()!!.single().weeks)
        repo.clearCache()
        assertNull(repo.cachedSchedule())
    }

    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString", "putInt", "putBoolean" -> { values[args!![0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "clear" -> { values.clear(); proxy }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString", "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
