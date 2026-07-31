package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Teacher
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 教工检索页解析器：`/User/default.aspx?code=120&uctl=MyControl\all_teacher.ascx`
 *
 * 页面是 ASP.NET WebForms，提交搜索是 POST 回同一 URL 并携带 `__VIEWSTATE` 三件套。
 * [Parsed.viewState] 等字段就是给 [cn.jxnu.nvzhuanban.data.repository.TeacherRepository]
 * 做下一次 POST 时的 donor 用的——本次解析出来的 hidden field 是下次提交的凭据。
 *
 * 结果表 `<table id="_ctl1_dgContent">` 第一行是表头（"所在单位 / 姓名 / 教号 / 性别 / 操作 / 操作2"），
 * 之后每行一条教工。`UserNum` 是教号的 base64，藏在「基本信息」`<a>` 的 javascript: URL 里，
 * 解析层不解码，原样保留——二级页 URL 直接拼接即可。
 */
object TeacherSearchPage {

    data class Parsed(
        val teachers: List<Teacher>,
        /** 页面上 `_ctl1_lblMsg` 里的提示，如 "查询结果：1 条记录"。Seed GET 时通常为空。 */
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
            teachers = parseTeachers(doc),
            message = doc.selectFirst("span[id$=_lblMsg]")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            viewState = hiddenInput(doc, "__VIEWSTATE"),
            viewStateGenerator = hiddenInput(doc, "__VIEWSTATEGENERATOR"),
            eventValidation = hiddenInput(doc, "__EVENTVALIDATION"),
        )
    }

    /** 从 `message` 文本里抽取记录条数；解析失败返回 null（提示文字格式有变化或本次没有消息）。 */
    fun extractCount(message: String?): Int? =
        message?.let { COUNT_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun parseTeachers(doc: Document): List<Teacher> {
        val table = doc.selectFirst("table[id$=_dgContent]") ?: return emptyList()
        val rows = table.select("> tbody > tr, > tr")
        return rows.mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            // 表头：第一行 6 个 td 的文本固定是「所在单位 / 姓名 / 教号 / 性别 / 操作 / 操作2」；
            // 我们用 cells[0] 是否等于「所在单位」做判定，对样式属性 (bgcolor/style) 不依赖
            if (cells.size < 5) return@mapNotNull null
            if (cells[0].text().trim() == "所在单位") return@mapNotNull null

            val actionsHtml = cells.getOrNull(4)?.html().orEmpty()
            val userNum = USER_NUM_REGEX.find(actionsHtml)?.groupValues?.getOrNull(1).orEmpty()
            if (userNum.isEmpty()) return@mapNotNull null  // 没有 UserNum 的行视为不可用，丢弃

            Teacher(
                name = cells[1].text().trim(),
                teacherId = cells[2].text().trim(),
                department = cells[0].text().trim(),
                gender = cells[3].text().trim(),
                userNum = userNum,
            )
        }
    }

    private fun hiddenInput(doc: Document, name: String): String =
        doc.selectFirst("input[name=$name]")?.attr("value").orEmpty()
}
