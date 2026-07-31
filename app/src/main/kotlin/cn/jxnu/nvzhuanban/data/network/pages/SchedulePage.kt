package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.CourseType
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 课表页解析器：`/User/default.aspx?code=111&uctl=MyControl\xfz_kcb.ascx&MyAction=Personal`
 *
 * 页面结构分两块：
 *   1. **视觉网格** (`<div id="_ctl1_NewKcb">` 内的 table)：每个有课的 `<td bgcolor>` 一节课，
 *      内容形如 `课程名<br>( 教室 )<br>班级`。
 *   2. **底部明细表** (`<table id="_ctl1_dgStudentLesson">`)：课程号、课程名、班级、任课老师等，
 *      作为网格的补充信息源。
 *
 * 学期下拉 `<select id="_ctl1_ddlSterm">` 暴露所有可选学期；每个 option 的 value 是学期开始日期，
 * 用来推算"今天是第几周"。
 *
 * 江师大可视化课表**不提供周次**信息，本解析器为所有课程统一填 1..18，
 * 切换周次时 UI 显示一致——这是已知限制，需要拿到周次细分接口后再迭代。
 */
object SchedulePage {

    data class Parsed(
        /** 当前选中的学期标签，如 "25-26第2学期" */
        val semester: String?,
        /** 学期开始日期（来源于 option value），用于推算当前教学周 */
        val semesterStart: LocalDate?,
        /** 所有可选学期 */
        val semesters: List<SemesterOption>,
        val courses: List<Course>,
        val totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
        /**
         * ASP.NET WebForms 隐藏字段，切学期 POST 时要原样回带。
         * 教务系统是 __VIEWSTATE/__VIEWSTATEGENERATOR/__EVENTVALIDATION 三件套。
         */
        val viewState: String = "",
        val viewStateGenerator: String = "",
        val eventValidation: String = "",
        /**
         * 教务网 `<option selected="selected">` 标注的学期 value，表示"这页 HTML 的数据
         * 实际属于哪个学期"。和 [SemesterOption.isCurrent]（按日期推断的"本学期"）是
         * 两个独立概念：仓库层用它判断要不要再 POST 一次切到真正的本学期。
         */
        val serverSelectedValue: String? = null,
        /**
         * ASP.NET 表单控件前缀（不含冒号），如 `_ctl1`（学生课表）/ `_ctl6`（教师课表通过
         * All_Display.aspx 渲染时使用）。来自 `<select name="…:ddlSterm">` 的 name 属性，
         * 提供给仓库层做 POST 表单字段名拼接，确保跨用户类型可复用同一个解析器。
         */
        val controlPrefix: String = "_ctl1",
    ) {
        /**
         * 今天相对这页课表所属学期的相位（进行中第几周 / 未开学 / 已结束）。
         * 无开始日时返回 null。周坐标 = [SemesterPhase.weekOneMonday]（最近周一），
         * 与课表列头日期同一套。
         */
        fun phaseAt(today: LocalDate = LocalDate.now()): SemesterPhase? =
            SemesterPhase.at(semesterStart, totalWeeks, today)
    }

    data class SemesterOption(
        val label: String,
        /** ddlSterm option 的 value，格式 `2026/3/1 0:00:00`，是学期开始日期 */
        val value: String,
        /**
         * 按日期推断的"本学期"：第 1 周周一（[SemesterPhase.weekOneMonday] 对齐名义
         * [startDate]）<= today 且在所有候选里最晚。用对齐后的真实上课日比较，保证开学首日
         * （如名义 9/1 是周二时的 8/31 周一）当天就切入新学期，而不是干等名义日期；反向
         * 名义日落在周日（如 3/1）时，前一天也不会提前切换。不依赖服务器的 `selected`
         * attr —— 服务器有时会停留在上一学期或下一学期，我们用日期更可靠。
         */
        val isCurrent: Boolean,
    ) {
        val startDate: LocalDate? by lazy {
            val datePart = value.substringBefore(' ')
            // 教务页里 option value 用 `\` 当日期分隔符（`2026\3\1 0:00:00`），
            // 但样本里也见过 `/`；两种都兼容
            val normalized = datePart.replace('\\', '/')
            // 显式 Locale.ROOT：阿拉伯/孟加拉等 locale 的 DateTimeFormatter 默认会用本地数字字符，
            // 而服务端永远发 ASCII 数字 —— 不锁 locale 会让那些用户解析直接失败。
            runCatching {
                LocalDate.parse(normalized, DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT))
            }.getOrNull()
        }
    }

    /** 默认学期周数。江师大瑶湖校区一般 16-18 周。 */
    private const val DEFAULT_TOTAL_WEEKS = 18
    private val DEFAULT_WEEKS: List<Int> = (1..DEFAULT_TOTAL_WEEKS).toList()

    /**
     * @param today 「今天」，决定 [SemesterOption.isCurrent] 的推断。默认真实当天；
     *   测试注入固定日期以保证断言不随运行日期漂移。
     */
    fun parse(html: String, today: LocalDate = LocalDate.now()): Parsed {
        val doc = Jsoup.parse(html)
        // 锚点 fail-fast：ddlSterm 学期下拉是课表页固定控件，真实空课表（学生本学期没课）也有它。
        // 解析不到 = 这根本不是课表页。jwc 对无效会话的报错形态一变再变（2026-07 曾以 200 纯文本
        // 穿透 Guard），没有锚点时本函数会把垃圾输入静默解析成「合法空课表」写进缓存——UI 呈现
        // Success 空网格、无错误无重试无重登入口。宁可抛 Decode 走错误态，也不产出以假乱真的空模型。
        if (doc.selectFirst("select[id$=_ddlSterm]") == null) {
            throw JwcException(
                JwcError.Decode("课表页缺少学期下拉锚点，可能是会话半失效返回的壳页面"),
                "课表加载异常，请下拉刷新重试；若持续出现请重新登录",
            )
        }
        val semesters = parseSemesterOptions(doc, today)
        val serverSelectedValue = doc.selectFirst("select[id$=_ddlSterm] > option[selected]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
        // 顶栏显示的学期 = 这页 HTML 实际渲染的学期（serverSelectedValue），
        // 而不是"按日期推断的本学期"。两者不一致时，仓库层会再 POST 一次切到本学期，
        // 第二次解析的 serverSelectedValue 自然就是本学期了。
        val activeForLabel = semesters.firstOrNull { it.value == serverSelectedValue }
            ?: semesters.firstOrNull { it.isCurrent }
            ?: semesters.firstOrNull()
        val courseRowInfo = parseCourseList(doc)
        val courses = mergeConsecutiveSlots(parseGrid(doc, courseRowInfo))
        val controlPrefix = doc.selectFirst("select[id$=_ddlSterm]")
            ?.attr("name")
            ?.substringBefore(":", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it.startsWith("_ctl") }
            ?: "_ctl1"
        return Parsed(
            semester = activeForLabel?.label,
            semesterStart = activeForLabel?.startDate,
            semesters = semesters,
            courses = courses,
            viewState = hiddenInput(doc, "__VIEWSTATE"),
            viewStateGenerator = hiddenInput(doc, "__VIEWSTATEGENERATOR"),
            eventValidation = hiddenInput(doc, "__EVENTVALIDATION"),
            serverSelectedValue = serverSelectedValue,
            controlPrefix = controlPrefix,
        )
    }

    private fun hiddenInput(doc: Document, name: String): String =
        doc.selectFirst("input[name=$name]")?.attr("value").orEmpty()

    private fun parseSemesterOptions(doc: Document, today: LocalDate): List<SemesterOption> {
        val raw = doc.select("select[id$=_ddlSterm] > option").map { el ->
            SemesterOption(
                label = el.text().trim(),
                value = el.attr("value"),
                isCurrent = false,
            )
        }
        // "本学期" = 第 1 周周一（weekOneMonday 对齐后）<= today 中最近的一个。
        // 暑/寒假期间会指向刚结束的学期（而不是下一学期）——上层用 SemesterPhase 区分假期态。
        // 推论：isCurrent 学期的相位不可能是 NotStarted（两边用同一套周一坐标）。
        val currentValue = raw
            .mapNotNull { opt -> opt.startDate?.let { opt to SemesterPhase.weekOneMonday(it) } }
            .filter { !it.second.isAfter(today) }
            .maxByOrNull { it.second }?.first?.value
        return if (currentValue == null) raw
        else raw.map { if (it.value == currentValue) it.copy(isCurrent = true) else it }
    }

    private data class CourseRowInfo(val code: String, val teacher: String, val className: String)

    private fun parseCourseList(doc: Document): Map<String, CourseRowInfo> {
        val table = doc.selectFirst("table[id$=_dgStudentLesson]") ?: return emptyMap()
        val rows = table.select("> tbody > tr, > tr")
        val result = mutableMapOf<String, CourseRowInfo>()
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 5) continue
            if (cells[0].text().trim() == "课程号") continue
            val code = cells[0].text().trim()
            val name = cells[1].text().trim()
            val className = cleanText(cells[3])
            val teacher = cleanText(cells[4])
            result[name] = CourseRowInfo(code, teacher, className)
        }
        return result
    }

    /**
     * 把视觉网格里因为节次分块（1-2 / 3 / 4 / 5 / 6-7 / 8-9 / 晚）而被拆成多张 Course 的
     * 同一门课合并起来。这样 UI 渲染时连排的几节看起来就是一整块，没有 2dp gap。
     *
     * 合并键：(weekday, name, location, teacher)。同名但教室/老师不同 → 不合并（说明是两门
     * 实际不同的课）。同名同教室同老师但节次不相邻 → 也不合并（中间有空节）。
     */
    private fun mergeConsecutiveSlots(courses: List<Course>): List<Course> {
        if (courses.size < 2) return courses
        val merged = courses
            .groupBy { listOf(it.weekday, it.name, it.location, it.teacher) }
            .flatMap { (_, group) ->
                val sorted = group.sortedBy { it.startSection }
                val out = mutableListOf<Course>()
                for (c in sorted) {
                    val last = out.lastOrNull()
                    if (last != null && c.startSection == last.endSection + 1) {
                        out[out.lastIndex] = last.copy(endSection = c.endSection)
                    } else {
                        out += c
                    }
                }
                out
            }
        // 保持稳定的展示顺序：按 weekday、起始节次排序
        return merged.sortedWith(compareBy({ it.weekday }, { it.startSection }))
    }

    private fun parseGrid(doc: Document, codeMap: Map<String, CourseRowInfo>): List<Course> {
        val gridTable = doc.selectFirst("div[id$=_NewKcb] table") ?: return emptyList()
        val rows = gridTable.select("> tbody > tr, > tr")

        val result = mutableListOf<Course>()
        var seq = 0

        for (row in rows) {
            val tds = row.children().filter { it.tagName() == "td" }
            if (tds.isEmpty()) continue
            // 按内容识别节次标签格 —— 之前用 `bgcolor=#cccccc` 判定，
            // HTML4 属性在教务网换主题（暗色/响应式）时极易消失。改用语义匹配：
            // 节次格内容固定是 "1 2"/"3"/"4"/"5"/"6 7"/"8 9"/"晚 上"，能被 [parseSection] 解析的就是它。
            // 顺带过滤掉整行都是星期表头（"星期一/二/..."）的情况——它们没有任何能解析成节次的 td。
            var labelIdx = -1
            var startSec = 0
            var endSec = 0
            for ((idx, td) in tds.withIndex()) {
                val parsed = parseSection(td) ?: continue
                labelIdx = idx
                startSec = parsed.first
                endSec = parsed.second
                break
            }
            if (labelIdx < 0) continue

            val weekdayCells = tds.subList(labelIdx + 1, tds.size).take(7)
            for ((dayIdx, cell) in weekdayCells.withIndex()) {
                val weekday = dayIdx + 1
                val course = parseCell(cell, weekday, startSec, endSec, codeMap, seq) ?: continue
                result += course
                seq++
            }
        }
        return result
    }

    /**
     * 节次标签解析。固定结构（江师大瑶湖通用）：
     *
     *   `1 2`  → 1-2 节   |   `3`     → 3 节
     *   `4`    → 4 节     |   `5`     → 5 节
     *   `6 7`  → 6-7 节   |   `8 9`   → 8-9 节
     *   `晚 上` → 10-11 节（晚间统一段）
     *
     * 关于 "晚上"：晚间段在网格里同时存在两种格子：
     *   - 段标签格：`<td><div>晚上</div></td>`（与 "上午"/"下午" 同列）
     *   - 节次标签格：`<td><div>晚<br>上</div></td>`（与 "1 2"/"6 7" 同列）
     * cleanText 之后两者文本都是 "晚 上"，正则去空白后都是 "晚上"——光看文本无法区分。
     * 这里用 `<br>` 是否存在做判定：只有节次标签格才含 `<br>`，段标签格直接返回 null
     * 让 [parseGrid] 的循环继续找下一格（也就是真正的节次标签）。
     */
    private fun parseSection(td: Element): Pair<Int, Int>? {
        val s = cleanText(td).replace(Regex("\\s+"), "")
        return when {
            s.isEmpty() -> null
            "晚" in s -> if (td.selectFirst("br") != null) 10 to 11 else null
            s == "12" -> 1 to 2
            s == "67" -> 6 to 7
            s == "89" -> 8 to 9
            else -> s.toIntOrNull()?.let { it to it }
        }
    }

    private fun parseCell(
        cell: Element,
        weekday: Int,
        startSec: Int,
        endSec: Int,
        codeMap: Map<String, CourseRowInfo>,
        seq: Int,
    ): Course? {
        // 不依赖 bgcolor 区分有课/无课/段标签（"上午/下午/晚上"）：
        //   - 有课的格子 = 有 <div> 子元素且内部含课程名+教室+班级
        //   - 无课的格子 / 段标签格 = 没有 <div>（或 <div> 内容为空），下面的 selectFirst 就直接返回 null
        // 这套判据对 HTML4 属性消失也免疫。
        val div = cell.selectFirst("div") ?: return null
        // 用 <br> 切分内容；每段再用 Jsoup 解码实体（&nbsp; 等）
        val parts = div.html().split("<br>", "<BR>", "<br/>", "<br />")
            .map { Jsoup.parse(it).text().trim().trimNbsp() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        val name = parts[0]
        val location = parts.getOrNull(1)
            ?.removePrefix("(")?.removeSuffix(")")?.trim()
            .orEmpty()
        val classText = parts.getOrNull(2).orEmpty()

        val info = codeMap[name]
        val teacher = info?.teacher?.takeIf { it.isNotBlank() }
            ?: extractTeacherFromClassText(classText)
        val code = info?.code.orEmpty()

        return Course(
            id = "$weekday-$startSec-${if (code.isNotEmpty()) code else seq.toString()}",
            name = name,
            teacher = teacher,
            location = location,
            weekday = weekday,
            startSection = startSec,
            endSection = endSec,
            weeks = DEFAULT_WEEKS,
            credit = 0f,  // 课表网格不含学分信息
            type = inferType(name, location),
            className = classText,
        )
    }

    /** 兜底：从班级文本里抓老师名（取首段 2-4 个连续汉字）。 */
    private fun extractTeacherFromClassText(s: String): String =
        Regex("[\\u4e00-\\u9fa5]{2,4}").find(s)?.value.orEmpty()

    private fun inferType(name: String, location: String): CourseType = when {
        name.contains("实验") -> CourseType.LAB
        name.contains("体育") -> CourseType.PE
        name.contains("慕课") || name.contains("MOOC", ignoreCase = true) -> CourseType.ONLINE
        location.startsWith("X", ignoreCase = true) -> CourseType.LAB  // 瑶湖 X 楼是实验楼
        else -> CourseType.LECTURE
    }

    private fun cleanText(el: Element): String = el.text().trimNbsp().trim()

    private fun String.trimNbsp(): String = replace(' ', ' ').trim()
}
