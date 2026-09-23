package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.FormOption
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 公共培养方案页。控件名由 WebForms 生成，按字段语义从实际页面发现。 */
object TrainingPlanSearchPage {
    data class Filter(
        val name: String,
        val label: String,
        val options: List<FormOption>,
        val selectedValue: String,
        val postsBack: Boolean,
    )

    data class Table(val columns: List<String>, val rows: List<List<String>>)

    data class Parsed(
        val filters: List<Filter>,
        val tables: List<Table>,
        val message: String?,
        val hiddenFields: Map<String, String>,
        val submitName: String?,
        val submitValue: String,
    )

    private val filterWords = Regex("年级|年届|入学|年份|学院|单位|专业|培养方案|grade|year|college|major|ddlNJ|ddlXY|ddlZY", RegexOption.IGNORE_CASE)
    private val resultWords = Regex("课程|学分|专业|方案|年级|学时|类别|模块|性质")
    private val searchWords = Regex("查询|检索|搜索")

    fun parse(html: String): Parsed {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form")
        val filters = form?.select("select[name]")?.mapNotNull { select ->
            val label = findLabel(doc, select)
            if (!filterWords.containsMatchIn("$label ${select.attr("name")} ${select.id()}")) return@mapNotNull null
            val options = select.select("option").map { FormOption(it.text().trim(), it.attr("value")) }
            if (options.isEmpty()) return@mapNotNull null
            Filter(
                name = select.attr("name"),
                label = label.ifBlank { inferredLabel(select) },
                options = options,
                selectedValue = select.selectFirst("option[selected]")?.attr("value") ?: options.first().value,
                postsBack = select.attr("onchange").contains("__doPostBack", ignoreCase = true),
            )
        }.orEmpty()
        val submit = form?.select("input[type=submit][name], button[type=submit][name]")
            ?.firstOrNull { searchWords.containsMatchIn(it.attr("value") + it.text()) }
            ?: form?.selectFirst("input[type=submit][name], button[type=submit][name]")
        return Parsed(
            filters = filters,
            tables = parseTables(doc),
            message = doc.select("span[id*=lblMsg], div[id*=lblMsg], span[id*=lblMessage]")
                .map { it.text().trim() }.firstOrNull { it.isNotEmpty() },
            hiddenFields = form?.select("input[type=hidden][name]")
                ?.associate { it.attr("name") to it.attr("value") }.orEmpty(),
            submitName = submit?.attr("name"),
            submitValue = submit?.attr("value")?.ifBlank { submit.text() }.orEmpty(),
        )
    }

    private fun parseTables(doc: Document): List<Table> = doc.select("form table")
        .mapNotNull { table ->
            val rows = table.select("> tbody > tr, > tr").map { tr ->
                tr.children().filter { it.tagName() == "th" || it.tagName() == "td" }
                    .map { it.text().trim() }
            }.filter { it.size > 1 }
            if (rows.size < 2) return@mapNotNull null
            val header = rows.first()
            if (header.none { resultWords.containsMatchIn(it) }) return@mapNotNull null
            Table(header, rows.drop(1).filter { it.size == header.size })
        }
        .filter { it.rows.isNotEmpty() }

    private fun findLabel(doc: Document, select: Element): String {
        val id = select.id()
        val explicit = if (id.isNotEmpty()) doc.selectFirst("label[for=$id]")?.text()?.trim() else null
        return explicit ?: select.parent()?.textNodes()?.joinToString(" ") { it.text() }?.trim()
            ?.take(24).orEmpty()
    }

    private fun inferredLabel(select: Element): String = when {
        Regex("grade|year|nj", RegexOption.IGNORE_CASE).containsMatchIn(select.id() + select.attr("name")) -> "年级"
        Regex("college|xy|unit", RegexOption.IGNORE_CASE).containsMatchIn(select.id() + select.attr("name")) -> "学院"
        Regex("major|zy", RegexOption.IGNORE_CASE).containsMatchIn(select.id() + select.attr("name")) -> "专业"
        else -> "培养方案"
    }
}
