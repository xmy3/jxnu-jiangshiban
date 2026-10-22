package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.CourseOfferingForm
import cn.jxnu.nvzhuanban.data.model.CourseOfferingTable
import cn.jxnu.nvzhuanban.data.model.FormOption
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 开课安排查询页解析器：`MyControl/Public_Kkap.aspx`。
 *
 * 页面分两态：
 *  - GET / POST 后的表单区：`select#ddlSterm / #ddlCollege / #ddlWeek / #ddlJC` +
 *    `txtJS / txtKc / txtTeacher` 三个文本框 + ASP.NET 三件套；
 *  - POST 成功后的结果区：`<hr/>` 之后的 GridView，带数据时渲染成 `table#gvContent`
 *    （空态只有一个无 id 的裸 `<div>`，页面上不存在其它表格）。
 *
 * 结果表列名由服务端决定、当前未有登录态样张，所以 [parseResult] 走表头驱动：
 * 第一行有 `<th>` 就作列名，之后每行 `<td>` 对齐到列。列结构变化不需要改代码。
 *
 * 另一个已实测的失败态：匿名（或会话失效边缘）POST 会返回 51 字节纯文本
 * 「Error:系统错误，请与系统管理员联系！」——HTTP 200、同域、不重定向，
 * `JwcResponseGuard` 拦不到，需要调用方先用 [isSystemError] 识别。
 */
object CourseOfferingPage {

    /** jwc 应用层被捕获异常的裸文本响应（非 HTML）。 */
    fun isSystemError(html: String): Boolean = html.trimStart().startsWith("Error:")

    fun parseForm(html: String): CourseOfferingForm {
        val doc = Jsoup.parse(html)
        // 空页守卫：表单页 / POST 结果页都会重渲 `select#ddlSterm` 与页面标题；两者全无说明
        // 拿到的是半失效会话的壳页，别把空表单当成"系统真的没有学期可选"。
        PageGuard.requireBusinessPage(doc, "开课查询", hasFormAnchors(doc))
        return CourseOfferingForm(
            semesters = options(doc, "ddlSterm"),
            colleges = options(doc, "ddlCollege"),
            weeks = options(doc, "ddlWeek"),
            sections = options(doc, "ddlJC"),
            viewState = hiddenInput(doc, "__VIEWSTATE"),
            viewStateGenerator = hiddenInput(doc, "__VIEWSTATEGENERATOR"),
            eventValidation = hiddenInput(doc, "__EVENTVALIDATION"),
        )
    }

    fun parseResult(html: String): CourseOfferingTable = parseResultPage(html).value

    /**
     * 带解析告警的结果区解析；[parseResult] 委托给它，结果与旧行为逐条一致。
     *
     * 空结果（`table#gvContent` 与 `form table` 都不存在）在业务页上是合法的「还没查」，
     * 因此只在页面锚点也消失时抛错。
     */
    fun parseResultPage(html: String): PageParse<CourseOfferingTable> {
        val doc = Jsoup.parse(html)
        // 带数据时 GridView 渲染成 table#gvContent；兜底任意 form 内表格（该页无布局表格）。
        val table = doc.selectFirst("table#gvContent")
            ?: doc.selectFirst("form table")
        PageGuard.requireBusinessPage(
            doc = doc,
            pageName = "开课查询",
            anchorFound = table != null || hasFormAnchors(doc),
        )
        if (table == null) return PageParse(CourseOfferingTable(emptyList(), emptyList()))

        val trs = table.select("> tbody > tr, > tr")
        if (trs.isEmpty()) return PageParse(CourseOfferingTable(emptyList(), emptyList()))

        val first = trs.first()!!
        val headerCells = first.children().filter { it.tagName() == "th" }
        val columns = headerCells.map { it.text().trim() }
        val dataRows = if (columns.isNotEmpty()) trs.drop(1) else trs.toList()

        var message: String? = null
        var misaligned = 0
        val rows = dataRows.mapNotNull { tr ->
            val cells = tr.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) return@mapNotNull null
            // 单格行（colspan 的 EmptyDataText / 提示）不算数据，收进 message
            if (cells.size == 1 && columns.size > 1) {
                message = message ?: cells[0].text().trim().takeIf { it.isNotEmpty() }
                return@mapNotNull null
            }
            // 表头驱动：列数对不上的行靠补空/截断对齐，内容可能错位 —— 计数并提示
            if (columns.isNotEmpty() && cells.size != columns.size) misaligned++
            cellsAligned(cells, columns.size)
        }
        val warnings = if (misaligned > 0) {
            listOf("开课查询结果有 $misaligned 行列数与表头不一致，内容可能错位")
        } else {
            emptyList()
        }
        return PageParse(CourseOfferingTable(columns = columns, rows = rows, message = message), warnings)
    }

    /** 页面级锚点：结果页与表单页都会渲染的控件/标题。 */
    private fun hasFormAnchors(doc: Document): Boolean =
        doc.selectFirst("select#ddlSterm") != null || doc.selectFirst("div.text-large") != null

    /** 单元格文本对齐到列数：列未知（columns 空）时原样返回，已知时不足补空、超出截断。 */
    private fun cellsAligned(cells: List<Element>, columnCount: Int): List<String> {
        val texts = cells.map { it.text().trim() }
        if (columnCount <= 0) return texts
        return List(columnCount) { i -> texts.getOrElse(i) { "" } }
    }

    private fun options(doc: Document, id: String): List<FormOption> =
        doc.select("select#$id option").map {
            // value 原样保留（学院代码带尾随空格）；label 归一空白
            FormOption(label = it.text().trim(), value = it.attr("value"))
        }

    private fun hiddenInput(doc: Document, name: String): String =
        doc.selectFirst("input[name=$name]")?.attr("value").orEmpty()
}
