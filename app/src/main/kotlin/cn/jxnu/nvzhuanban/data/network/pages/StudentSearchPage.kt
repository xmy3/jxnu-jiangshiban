package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Student
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 学生检索页解析器：`/User/default.aspx?code=119&uctl=MyControl\all_searchstudent.ascx`
 *
 * 表单字段、ASP.NET 三件套行为与 [TeacherSearchPage] 完全一致——
 * 唯一区别是结果表 `_ctl1_dgContent` 有 6 列：
 *   所在单位 / 班级名称 / 姓名 / 学号 / 性别 / 操作（合并 基本信息+发送短信+课表 三个链接）
 *
 * UserNum 仍是学号的 base64，原样保留传给 [cn.jxnu.nvzhuanban.data.network.JxnuUrls.studentDetailUrl]。
 */
object StudentSearchPage {

    data class Parsed(
        val students: List<Student>,
        /** `_ctl1_lblMsg` 文本，如 "查询结果：10 条记录" 或 "查询结果过多，只显示前：10 条记录"；seed GET 通常为空。 */
        val message: String?,
        val viewState: String,
        val viewStateGenerator: String,
        val eventValidation: String,
    )

    private val USER_NUM_REGEX = Regex("""UserNum=([A-Za-z0-9+/=]+)""")
    private val COUNT_REGEX = Regex("""(\d+)\s*条记录""")

    fun parse(html: String): Parsed {
        val doc = Jsoup.parse(html)
        return Parsed(
            students = parseStudents(doc),
            message = doc.selectFirst("span[id$=_lblMsg]")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            viewState = hiddenInput(doc, "__VIEWSTATE"),
            viewStateGenerator = hiddenInput(doc, "__VIEWSTATEGENERATOR"),
            eventValidation = hiddenInput(doc, "__EVENTVALIDATION"),
        )
    }

    /** 从 `message` 抽取记录条数；解析失败返回 null。 */
    fun extractCount(message: String?): Int? =
        message?.let { COUNT_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun parseStudents(doc: Document): List<Student> {
        val table = doc.selectFirst("table[id$=_dgContent]") ?: return emptyList()
        val rows = table.select("> tbody > tr, > tr")
        return rows.mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            // 跳过表头：6 个 td 的第一格固定是「所在单位」
            if (cells.size < 6) return@mapNotNull null
            if (cells[0].text().trim() == "所在单位") return@mapNotNull null

            val actionsHtml = cells.getOrNull(5)?.html().orEmpty()
            val userNum = USER_NUM_REGEX.find(actionsHtml)?.groupValues?.getOrNull(1).orEmpty()
            if (userNum.isEmpty()) return@mapNotNull null  // 没有 UserNum 的行视为不可用

            Student(
                department = cells[0].text().trim(),
                className = cells[1].text().trim(),
                name = cells[2].text().trim(),
                studentId = cells[3].text().trim(),
                gender = cells[4].text().trim(),
                userNum = userNum,
            )
        }
    }

    private fun hiddenInput(doc: Document, name: String): String =
        doc.selectFirst("input[name=$name]")?.attr("value").orEmpty()
}
