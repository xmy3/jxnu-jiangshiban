package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Grade
import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 成绩页解析器：`/MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx&Action=Personal`
 *
 * 该页是 ASP.NET 渲染的 HTML。核心数据在 `<table id="_ctl6_dgContent">`，列顺序固定为：
 *
 *   考试时间 | 课程号 | 课程名称 | 所得学分 | 课程成绩 | 补考成绩 | 标准分 | 备注
 *
 * 同一学期的第一行用 `<td rowspan="N">` 占住"考试时间"列，所以同一学期的后续行只剩 7 列。
 * 解析时需要按列数区分并维护"当前学期"。
 * 赴外交流学习成绩另列在五列表中，按表头识别并合并到对应学期，没有课程号或标准分。
 *
 * 江西师大的"标准分"不是 4 分制 GPA，而是按全班排名归一化的 Z-score（可正可负），
 * 这里仍存进 [Grade.gpa] 字段——UI 上的加权平均计算结果就是学生绩点排名指标。
 */
object GradePage {

    /** 学生信息汇总（来自 `<span id="_ctl6_lblMsg">`）。 */
    data class StudentMeta(
        val college: String? = null,
        val major: String? = null,
        val className: String? = null,
        val studentId: String? = null,
        val name: String? = null,
        val totalCredit: Float? = null,
    )

    data class Parsed(
        val meta: StudentMeta,
        val semesters: List<SemesterSummary>,
    )

    fun parse(html: String): Parsed {
        val doc = Jsoup.parse(html)
        val exchangeTables = findExchangeTables(doc)
        // 结构性 fail-fast：个人信息、校内成绩表、交流成绩表均缺失，
        // 说明这不是成绩页 —— 最常见是半失效会话下 jwc 返回 200 的业务壳页
        // （JwcResponseGuard 只能识别登录页形态，拦不住这种）。之前静默解析成
        // 「空 meta + 0 条成绩」还会被 GradeRepository 写进缓存，用户看到的是
        // 无提示的空成绩单。抛 Decode 让 UI 走错误态、缓存保持未污染。
        // 暂无成绩（大一新生）时 lblMsg 仍在；只有交流成绩的页面也能正常解析。
        if (doc.selectFirst("[id$=_lblMsg]") == null &&
            doc.selectFirst("table[id$=_dgContent]") == null &&
            exchangeTables.isEmpty()
        ) {
            throw JwcException(
                JwcError.Decode("成绩页缺少个人信息与成绩表锚点，可能是会话半失效返回的壳页面"),
                "成绩页加载异常，请下拉刷新重试；若持续出现请重新登录",
            )
        }
        val meta = parseMeta(doc)
        val grades = parseGrades(doc) + exchangeTables.flatMapIndexed { index, table ->
            parseExchangeGrades(table, index)
        }
        // groupBy 保留遍历顺序，等同于教务系统的显示顺序（最新学期在前）。
        val semesters = grades.groupBy { it.semester }
            .map { (sem, list) -> SemesterSummary(sem, list) }
        return Parsed(meta, semesters)
    }

    private fun parseMeta(doc: Document): StudentMeta {
        val span = doc.selectFirst("[id$=_lblMsg]") ?: return StudentMeta()
        val text = span.text().replace(' ', ' ').replace('　', ' ')
        fun pick(key: String): String? =
            Regex("$key\\s*[：:]\\s*([^\\s　]+)").find(text)?.groupValues?.getOrNull(1)
        return StudentMeta(
            college = pick("学院"),
            major = pick("专业名称"),
            className = pick("班级名称"),
            studentId = pick("学号"),
            name = pick("姓名"),
            totalCredit = parseTotalCredit(text),
        )
    }

    private fun parseTotalCredit(text: String): Float? {
        // 有交流学分时教务网显示「59 + 4 = 63」，必须取最终累计值，而不是首项 59。
        val number = "\\d+(?:\\.\\d+)?"
        val expression = Regex(
            "累计学分\\s*[：:]\\s*($number(?:\\s*[+＋]\\s*$number)*(?:\\s*[=＝]\\s*$number)?)",
        ).find(text)?.groupValues?.get(1) ?: return null
        val normalized = expression.filterNot { it.isWhitespace() }
            .replace('＋', '+').replace('＝', '=')
        return if ('=' in normalized) {
            normalized.substringAfterLast('=').toFloatOrNull()
        } else {
            normalized.split('+').sumOf { it.toDouble() }.toFloat()
        }
    }

    private fun parseGrades(doc: Document): List<Grade> {
        val rows = doc.select("table[id$=_dgContent] > tbody > tr, table[id$=_dgContent] > tr")
        var currentSemester: String? = null
        val results = mutableListOf<Grade>()
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) continue
            // 跳过表头：第一列在表头是"考试时间"
            if (cells[0].text().trim() == "考试时间") continue

            // 8 列：当前行是某学期的第一行（首列 rowspan 写学期）；7 列：后续行
            val offset: Int = when (cells.size) {
                8 -> { currentSemester = normalizeSemester(cleanText(cells[0])); 1 }
                7 -> 0
                else -> continue
            }
            val sem = currentSemester ?: continue
            val code = cleanText(cells[offset + 0])
            val name = cleanText(cells[offset + 1])
            val credit = cleanText(cells[offset + 2]).toFloatOrNull() ?: 0f
            val score = cleanText(cells[offset + 3])
            val makeup = cleanText(cells[offset + 4]).takeIf { it.isNotBlank() }
            val gpa = cleanText(cells[offset + 5]).toFloatOrNull()
            val remark = cleanText(cells[offset + 6]).takeIf { it.isNotBlank() }

            results += Grade(
                id = "$sem-$code",
                semester = sem,
                courseName = name,
                courseCode = code,
                credit = credit,
                score = score,
                gpa = gpa,
                makeupScore = makeup,
                remark = remark,
            )
        }
        return results
    }

    private val exchangeHeaders = listOf("学期名称", "学校", "课程名称", "学分", "成绩")

    private data class ExchangeTable(val rows: List<Element>, val headerIndex: Int)

    private fun findExchangeTables(doc: Document): List<ExchangeTable> =
        doc.select("table").mapNotNull { table ->
            // 只看表本身的行，防止嵌套外框把内表再解析一遍。
            val rows = table.select("tr").filter { it.closest("table") == table }
            val headerIndex = rows.indexOfFirst { row -> isExchangeHeader(rowCells(row)) }
            if (headerIndex < 0) null else ExchangeTable(rows, headerIndex)
        }

    private fun rowCells(row: Element): List<Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    private fun headerText(cell: Element): String = cleanText(cell).filterNot { it.isWhitespace() }

    private fun isExchangeHeader(cells: List<Element>): Boolean =
        cells.size == exchangeHeaders.size && cells.map(::headerText).containsAll(exchangeHeaders)

    private data class SpannedCell(val text: String, val rowsLeft: Int)

    private fun parseExchangeGrades(table: ExchangeTable, tableIndex: Int): List<Grade> {
        val headers = rowCells(table.rows[table.headerIndex]).map(::headerText)
        val spans = mutableMapOf<Int, SpannedCell>()
        val results = mutableListOf<Grade>()
        for ((rowIndex, row) in table.rows.withIndex().drop(table.headerIndex + 1)) {
            val cells = rowCells(row)
            if (isExchangeHeader(cells)) {
                spans.clear()
                continue
            }
            // colspan 行是说明/合计/空态，不能当成课程。
            if (cells.isEmpty() || cells.any { (it.attr("colspan").toIntOrNull() ?: 1) != 1 }) continue
            val values = MutableList(headers.size) { "" }
            var cellIndex = 0
            var complete = true
            for (column in headers.indices) {
                val carried = spans[column]
                if (carried != null) {
                    values[column] = carried.text
                    if (carried.rowsLeft == 1) spans.remove(column)
                    else spans[column] = carried.copy(rowsLeft = carried.rowsLeft - 1)
                } else {
                    val cell = cells.getOrNull(cellIndex++)
                    if (cell == null) {
                        complete = false
                        break
                    }
                    values[column] = cleanText(cell)
                    val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1
                    if (rowspan > 1) spans[column] = SpannedCell(values[column], rowspan - 1)
                }
            }
            if (!complete || cellIndex != cells.size) continue
            fun value(header: String): String = values[headers.indexOf(header)]
            val semester = normalizeSemester(value("学期名称"))
            val school = value("学校")
            val name = value("课程名称")
            val credit = value("学分").toFloatOrNull() ?: continue
            if (semester.isBlank() || name.isBlank()) continue
            results += Grade(
                // 交流课没有课程号，源前缀和表/行序号保证同名课程也不会产生重复列表 key。
                id = "exchange-$semester-$school-$name-$tableIndex-$rowIndex",
                semester = semester,
                courseName = name,
                courseCode = "",
                credit = credit,
                score = value("成绩"),
                gpa = null,
                exchangeSchool = school,
            )
        }
        return results
    }

    private fun normalizeSemester(text: String): String =
        text.filterNot { it.isWhitespace() }.replace("学年第", "第")

    private fun cleanText(el: Element): String =
        el.text().replace(' ', ' ').trim()
}
