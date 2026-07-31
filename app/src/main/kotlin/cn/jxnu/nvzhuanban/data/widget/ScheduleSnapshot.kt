package cn.jxnu.nvzhuanban.data.widget

import android.content.Context
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.CourseType
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 桌面小部件用的课表快照。
 *
 * 设计目标：
 *  - Widget 进程独立于 App，不能直接共享 ViewModel / 内存缓存
 *  - 每次 App 拉到新课表时把"本学期全部课程 + 学期开学日"序列化到本地小文件
 *  - Widget 启动时直接读文件，**渲染时按"今天"现场算出 weekday / week / 今日课程**，
 *    这样跨日 / 跨周 / 跨节次都不需要 App 重写 snapshot，widget 自己更新即可
 *  - 不发起任何网络请求
 *
 * 历史格式兼容：旧版本只存了"今日"那一份子集（无 weekday/weeks/semesterStartEpochDay 字段），
 * [fromJson] 会优雅降级，下次 App 打开会自动升级为新格式。
 */
data class ScheduleSnapshot(
    val semester: String,
    /** 学期总周数；0 表示未知（回退到默认 18）。 */
    val totalWeeks: Int,
    /** 本学期开学第一天的 epoch day；-1 表示未知（老快照 / 教务网没给）。 */
    val semesterStartEpochDay: Long,
    /**
     * 下一个学期的名义开学日 epoch day；-1 表示未知（教务还没放出下学期选项 / 老快照）。
     * 假期态（本学期已结束）时 widget 用它显示「距开学 N 天」倒计时。
     * 存储保持名义日期（老快照无需迁移），读取一律走 [nextSemesterStart]——它会对齐到第 1 周周一。
     */
    val nextSemesterStartEpochDay: Long = -1L,
    /** 保存这份 snapshot 那一刻是第几教学周；仅作为 [semesterStartEpochDay] 缺失时的回退。 */
    val savedWeek: Int,
    /** 本学期全部课程；widget 渲染时按当天 weekday / week 现场筛选。 */
    val allCourses: List<SnapshotCourse>,
    val updatedAt: Long,
) {
    /**
     * 依据日期算"今天是第几周"；学期未开始 / 学期已结束都返回 0，[semesterStartEpochDay] 未知则回退到 [savedWeek]。
     * 周坐标 = [SemesterPhase.weekOneMonday]（开学名义日对齐最近周一），与课表页同一套，保证 widget 的
     * "第 N 周"和 App 顶栏一致。
     */
    fun weekAt(date: LocalDate = LocalDate.now()): Int {
        if (semesterStartEpochDay < 0) return savedWeek
        val monday = SemesterPhase.weekOneMonday(LocalDate.ofEpochDay(semesterStartEpochDay))
        val days = ChronoUnit.DAYS.between(monday, date).toInt()
        if (days < 0) return 0
        val w = days / 7 + 1
        // 已知 totalWeeks 时，超出范围（寒暑假）按"不在学期内"处理，返回 0
        if (totalWeeks > 0 && w > totalWeeks) return 0
        return w
    }

    /**
     * 当天应上的课，按节次升序。
     *
     * - 已知教学周（week > 0）且这一周在课程的 [SnapshotCourse.weeks] 内才返回；
     * - 未知教学周（week == 0，旧 snapshot 没有学期起始日）→ 不按周次过滤，避免空数据；
     * - 已确认不在学期内（learns weekAt 显式回 0 + totalWeeks 已知）的情况由 [weekAt] 自己处理，
     *   但区分不出"旧 snapshot 缺信息"和"刚好放假"，所以这里再用 [hasSemesterStart] 做一次显式判定。
     */
    fun coursesOn(date: LocalDate = LocalDate.now()): List<SnapshotCourse> {
        val weekday = date.dayOfWeek.value
        val week = weekAt(date)
        val inSemester = !hasSemesterStart || week > 0
        if (!inSemester) return emptyList()
        return allCourses.asSequence()
            .filter { it.weekday == weekday }
            .filter { week <= 0 || week in it.weeks }
            .sortedBy { it.startSection }
            .toList()
    }

    /** 这份 snapshot 是否带有可信的学期起始日（旧版没有）。 */
    val hasSemesterStart: Boolean get() = semesterStartEpochDay >= 0L

    /**
     * 下学期开学日 = 第 1 周周一（存储的名义日经 [SemesterPhase.weekOneMonday] 对齐，如名义
     * 9/1 周二 → 8/31）；未知（教务未放出 / 老快照）返回 null。「距开学 N 天」「X月X日 开学」
     * 都以它为准，与课表页同一套坐标；老快照里存的名义日在这里同样被对齐，对齐幂等（周一原样返回）。
     */
    val nextSemesterStart: LocalDate?
        get() = if (nextSemesterStartEpochDay >= 0L)
            SemesterPhase.weekOneMonday(LocalDate.ofEpochDay(nextSemesterStartEpochDay))
        else null

    /**
     * 把快照还原成完整 [Course] 列表，供课表页「离线兜底」展示（网络失败时显示上次缓存的课表）。
     * 快照为省体积只存了 widget 需要的字段，这里对缺失字段的处理：
     *  - id：合成稳定唯一键（同格 weekday+section 同名课唯一），满足 UI 高亮匹配 / 周次编辑 remember key；
     *  - credit / type / className：快照未存，填默认。课表网格不读这些；课程详情里学分会显示 0（离线降级）。
     */
    fun toCourses(): List<Course> = allCourses.map { c ->
        Course(
            id = "offline-${c.weekday}-${c.startSection}-${c.endSection}-${c.name}",
            name = c.name,
            teacher = c.teacher,
            location = c.location,
            weekday = c.weekday,
            startSection = c.startSection,
            endSection = c.endSection,
            weeks = c.weeks,
            credit = 0f,
            type = CourseType.LECTURE,
        )
    }

    fun toJson(): String {
        val arr = JSONArray()
        allCourses.forEach { c ->
            val weeksArr = JSONArray()
            c.weeks.forEach { weeksArr.put(it) }
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("location", c.location)
                    .put("teacher", c.teacher)
                    .put("start", c.startSection)
                    .put("end", c.endSection)
                    .put("weekday", c.weekday)
                    .put("weeks", weeksArr)
            )
        }
        return JSONObject()
            .put("semester", semester)
            .put("totalWeeks", totalWeeks)
            .put("semesterStartEpochDay", semesterStartEpochDay)
            .put("nextSemesterStartEpochDay", nextSemesterStartEpochDay)
            .put("savedWeek", savedWeek)
            .put("updatedAt", updatedAt)
            .put("courses", arr)
            .toString()
    }

    companion object {
        fun empty(): ScheduleSnapshot = ScheduleSnapshot(
            semester = "",
            totalWeeks = 0,
            semesterStartEpochDay = -1L,
            savedWeek = 0,
            allCourses = emptyList(),
            updatedAt = 0L,
        )

        fun fromCourses(
            semester: String,
            totalWeeks: Int,
            semesterStart: LocalDate?,
            all: List<Course>,
            nextSemesterStart: LocalDate? = null,
        ): ScheduleSnapshot {
            val today = LocalDate.now()
            val startEpoch = semesterStart?.toEpochDay() ?: -1L
            val savedWeek = if (semesterStart != null) {
                val monday = SemesterPhase.weekOneMonday(semesterStart)
                val days = ChronoUnit.DAYS.between(monday, today).toInt()
                val w = (days / 7 + 1).coerceAtLeast(1)
                if (totalWeeks > 0) w.coerceAtMost(totalWeeks) else w
            } else 0
            return ScheduleSnapshot(
                semester = semester,
                totalWeeks = totalWeeks,
                semesterStartEpochDay = startEpoch,
                nextSemesterStartEpochDay = nextSemesterStart?.toEpochDay() ?: -1L,
                savedWeek = savedWeek,
                allCourses = all.map { c ->
                    SnapshotCourse(
                        name = c.name,
                        location = c.location,
                        teacher = c.teacher,
                        startSection = c.startSection,
                        endSection = c.endSection,
                        weekday = c.weekday,
                        weeks = c.weeks,
                    )
                },
                updatedAt = System.currentTimeMillis(),
            )
        }

        fun fromJson(text: String): ScheduleSnapshot = runCatching {
            val o = JSONObject(text)
            // 老格式只存了今天那一份子集：顶层 weekday + 不带 weekday/weeks 的 course
            val legacyGlobalWeekday = o.optInt("weekday", 0)
            val savedWeekField = o.optInt("savedWeek", o.optInt("week", 0))
            val arr = o.optJSONArray("courses")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val weeksJson = c.optJSONArray("weeks")
                val weeks = if (weeksJson == null) (1..18).toList()
                    else (0 until weeksJson.length()).map { weeksJson.getInt(it) }
                SnapshotCourse(
                    name = c.optString("name"),
                    location = c.optString("location"),
                    teacher = c.optString("teacher"),
                    startSection = c.optInt("start"),
                    endSection = c.optInt("end"),
                    weekday = c.optInt("weekday", legacyGlobalWeekday),
                    weeks = weeks,
                )
            }
            ScheduleSnapshot(
                semester = o.optString("semester"),
                totalWeeks = o.optInt("totalWeeks", 0),
                semesterStartEpochDay = o.optLong("semesterStartEpochDay", -1L),
                nextSemesterStartEpochDay = o.optLong("nextSemesterStartEpochDay", -1L),
                savedWeek = savedWeekField,
                allCourses = list,
                updatedAt = o.optLong("updatedAt"),
            )
        }.getOrElse { empty() }
    }
}

data class SnapshotCourse(
    val name: String,
    val location: String,
    val teacher: String,
    val startSection: Int,
    val endSection: Int,
    /** 1 = 周一 ... 7 = 周日 */
    val weekday: Int,
    /** 该课上课的所有教学周；widget 渲染时用 (today, weekAt) 现场过滤。 */
    val weeks: List<Int>,
)

/**
 * 把 [ScheduleSnapshot] 持久化到 App 内部文件（filesDir/widget_snapshot.json）。
 * Widget 读取该文件时不需要 Context 之外的任何依赖。
 *
 * 写入采用 tmp + atomic rename：断电只会留下完整旧版或完整新版，不会出现部分写。
 * 调用方应在 IO 调度器上调用 [save]（[load] 也涉及 file IO，Glance provideGlance 里需要套
 * `withContext(Dispatchers.IO)`）。
 */
object WidgetSnapshotStore {

    private const val FILE_NAME = "widget_snapshot.json"
    private const val TMP_NAME = "widget_snapshot.json.tmp"
    @Volatile private var writeGeneration: Long = 0L
    @Volatile private var stopped: Boolean = false

    fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun tmpFile(context: Context): File =
        File(context.applicationContext.filesDir, TMP_NAME)

    @Synchronized
    fun generation(): Long = writeGeneration

    @Synchronized
    fun resumeWrites() {
        stopped = false
        writeGeneration++
    }

    fun save(context: Context, snapshot: ScheduleSnapshot) {
        save(context, snapshot, generation())
    }

    @Synchronized
    fun save(context: Context, snapshot: ScheduleSnapshot, generation: Long) {
        if (stopped || generation != writeGeneration) return
        val target = file(context)
        val tmp = tmpFile(context)
        runCatching {
            tmp.writeText(snapshot.toJson())
            if (!tmp.renameTo(target)) {
                // 同卷 rename 在 Android 上原子；目标已存在时部分 FS 不允许覆盖，降级到先删后改
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure {
            // 写失败：清掉 tmp 避免堆积；保留旧 target 不动
            runCatching { tmp.delete() }
        }
    }

    @Synchronized
    fun load(context: Context): ScheduleSnapshot {
        val f = file(context)
        if (!f.exists()) return ScheduleSnapshot.empty()
        return runCatching {
            ScheduleSnapshot.fromJson(f.readText())
        }.getOrDefault(ScheduleSnapshot.empty())
    }

    /** 退出登录时删除磁盘 snapshot，让 widget 立刻显示"打开 App 加载课表"。 */
    @Synchronized
    fun clear(context: Context) {
        stopped = true
        writeGeneration++
        runCatching { file(context).delete() }
        runCatching { tmpFile(context).delete() }
    }
}
