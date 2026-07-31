package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.model.CalendarFileType
import org.jsoup.Jsoup

/**
 * 教学周历（校历）索引页解析器：`https://jwc.jxnu.edu.cn/Jxzl_Index.htm`。
 *
 * 页面是一个简单静态 HTML，主体如下：
 * ```html
 * <ul class="list-group">
 *   <li><a href="https://jwc.jxnu.edu.cn/wnl.htm" class="button bg-red button-large icon-calendar"> 万年历 </a></li>
 *   <li>
 *     <a href="https://jwc.jxnu.edu.cn/Jxzl_20260901.pdf" class="button bg-red button-small">2026-2027学年第一学期</a>
 *   </li>
 *   <li>
 *     <a href="https://jwc.jxnu.edu.cn/Jxzl_20250901.pdf" class="button bg-red button-small">2025-2026学年第一学期</a>
 *     <a href="https://jwc.jxnu.edu.cn/Jxzl_20260301.pdf" class="button bg-red button-small">2025-2026学年第二学期</a>
 *   </li>
 *   <li>
 *     ... 2019-2020 二学期还会出现 bg-blue 标记的「(调整)」版本 ...
 *   </li>
 * </ul>
 * ```
 *
 * 入参 [html] 必须是已经从 GBK 正确解码到 UTF-8 的字符串。解码由 Repository 负责。
 *
 * 只挑 `Jxzl_*` 与 `wnl.htm` 两类链接，过滤掉浏览器扩展（DeepL / picviewer）注入的 `<a>` 噪声。
 */
object CalendarPage {

    private val WHITESPACE = Regex("""\s+""")

    fun parse(html: String, baseUri: String = ""): List<CalendarEntry> {
        // baseUri 让 Jsoup 的 `abs:href` 能把相对 href 解析为完整 https URL。
        // 不传 base 时 abs:href 返回空字符串，下方会 fallback 到 raw href，
        // 一旦 jwc 用了相对路径（如 `Jxzl_20260901.pdf`），entry.url 就没有 scheme，
        // 后续 Intent.ACTION_VIEW + Uri.parse 会 resolve 不到任何 Activity。
        val doc = Jsoup.parse(html, baseUri)
        val results = mutableListOf<CalendarEntry>()
        // 限定 ul.list-group 范围，避免扫到页面底部插件注入的 a 标签
        for (a in doc.select("ul.list-group a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.isCalendarLink()) continue
            val title = a.text().trim().replace(WHITESPACE, " ")
            if (title.isBlank()) continue
            val classAttr = a.attr("class")
            results += CalendarEntry(
                title = title,
                url = href,
                fileType = fileTypeOf(href),
                isPerpetual = href.endsWith("wnl.htm", ignoreCase = true),
                isCorrection = "bg-blue" in classAttr,
            )
        }
        return results
    }

    private fun String.isCalendarLink(): Boolean {
        // 大小写不敏感匹配 `jxzl_` 或 `wnl.htm`。jwc 自身全是绝对 URL，但为了兼容相对路径
        // （以及未来可能的 query 变化）这里不强求前导 `/`。
        val lower = lowercase()
        return "jxzl_" in lower || lower.endsWith("wnl.htm")
    }

    private fun fileTypeOf(href: String): CalendarFileType {
        // 取去掉 query/fragment 后的小写后缀。教务网静态文件不带 query，但保险起见还是处理一下。
        val cleaned = href.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleaned.endsWith(".pdf") -> CalendarFileType.PDF
            cleaned.endsWith(".doc") || cleaned.endsWith(".docx") -> CalendarFileType.DOC
            cleaned.endsWith(".xls") || cleaned.endsWith(".xlsx") -> CalendarFileType.XLS
            cleaned.endsWith(".jpg") || cleaned.endsWith(".jpeg") || cleaned.endsWith(".png") -> CalendarFileType.JPG
            cleaned.endsWith(".htm") || cleaned.endsWith(".html") -> CalendarFileType.HTM
            else -> CalendarFileType.OTHER
        }
    }
}
