package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Grade
import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 成绩页解析器：`/MyControl/All_Display.aspx?UserControl=xfz_cj3.ascx&Action=Personal`
 *
 * 该页是 ASP.NET 渲染的 HTML。核心数据在 `<table id="_ctl6_dgContent">`，列顺序固定为：
 *
 *   考试时间 | 课程号 | 课程名称 | 所得学分 | 课程成绩 | 补考成绩 | 标准分 | 备注
 *
 * 同一学期的第一行用 `<td rowspan="N">` 占住"考试时间"列，所以同一学期的后续行只剩 7 列。
 * 解析时需要按列数区分并维护"当前学期"。
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
        // 结构性 fail-fast：lblMsg（个人信息）和 dgContent（成绩表）两个锚点同时缺失，
        // 说明这不是成绩页 —— 最常见是半失效会话下 jwc 返回 200 的业务壳页
        // （JwcResponseGuard 只能识别登录页形态，拦不住这种）。之前静默解析成
        // 「空 meta + 0 条成绩」还会被 GradeRepository 写进缓存，用户看到的是
        // 无提示的空成绩单。抛 Decode 让 UI 走错误态、缓存保持未污染。
        // 注意用「两个都缺」判定：真实成绩页哪怕暂无成绩（大一新生），lblMsg 也在。
        if (doc.selectFirst("[id$=_lblMsg]") == null &&
            doc.selectFirst("table[id$=_dgContent]") == null
        ) {
            throw JwcException(
                JwcError.Decode("成绩页缺少个人信息与成绩表锚点，可能是会话半失效返回的壳页面"),
                "成绩页加载异常，请下拉刷新重试；若持续出现请重新登录",
            )
        }
        val meta = parseMeta(doc)
        val grades = parseGrades(doc)
        // groupBy 保留遍历顺序，等同于教务系统的显示顺序（最新学期在前）。
        val semesters = grades.groupBy { it.semester }
            .map { (sem, list) -> SemesterSummary(sem, list) }
        return Parsed(meta, semesters)
    }

    private fun parseMeta(doc: Document): StudentMeta {
        val span = doc.selectFirst("[id$=_lblMsg]") ?: return StudentMeta()
        val text = span.text().replace(' ', ' ')
        fun pick(key: String): String? =
            Regex("$key\\s*[：:]\\s*([^\\s　]+)").find(text)?.groupValues?.getOrNull(1)
        return StudentMeta(
            college = pick("学院"),
            major = pick("专业名称"),
            className = pick("班级名称"),
            studentId = pick("学号"),
            name = pick("姓名"),
            totalCredit = pick("累计学分")?.toFloatOrNull(),
        )
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
                8 -> { currentSemester = cleanText(cells[0]); 1 }
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

    private fun cleanText(el: org.jsoup.nodes.Element): String =
        el.text().replace(' ', ' ').trim()
}
