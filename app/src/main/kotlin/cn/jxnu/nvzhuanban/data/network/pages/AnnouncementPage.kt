package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 教务通知/通告列表页解析器：`Portal/ArticlesList.aspx?type={Jwtz|Jwgg}`。
 *
 * 列表中每一条都是：
 * ```html
 * <a href='ArticlesView.aspx?id=13616' target=_blank>
 *   <div class="line border-bottom  margin-bottom text-big padding">
 *     【教务字〔2026〕66号】 关于申报校级课程思政研究专项课题的通知 【2026-05-13】
 *   </div>
 * </a>
 * ```
 *
 * 文本结构：(可选文号)(标题)(`【YYYY-MM-DD】` 日期)。文号有的没有（如 Jwgg 通告），所以
 * 解析时只强抓末尾的 `【YYYY-MM-DD】`，剩下统一作 title（文号留在 title 里展示）。
 *
 * 每页固定 10 条，分页通过 `?page=N` 翻；翻页逻辑在 [cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository]
 * 里实现，UI 端 `AnnouncementViewModel.loadMore` 触发滚到底部自动加载。
 */
object AnnouncementPage {

    /** 提取末尾的 【YYYY-M-D】或【YYYY-MM-DD】。`$` 锚定文本结尾；月/日两位都支持。 */
    private val DATE_TAIL = Regex("""【(\d{4}-\d{1,2}-\d{1,2})】$""")

    /** 文章 id 来自 href 上的 `id=数字`。 */
    private val ID_REGEX = Regex("""id=(\d+)""")

    /** 多空白合并成单空格，避免 HTML 缩进噪声进到 title。 */
    private val WHITESPACE = Regex("""\s+""")

    /** 显式 pattern：教务网现行格式是零填充的 yyyy-MM-dd，但允许 1 位月/日兜底。Locale.ROOT 锁 ASCII 数字。 */
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ROOT)

    fun parse(html: String, type: AnnouncementType): List<Announcement> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<Announcement>()
        // CSS 选择器：`<a>` 包含 class 同时有 `line` `border-bottom` `text-big` 的 div。
        // jsoup 的 `:has()` 支持嵌套选择。
        for (a in doc.select("a:has(div.line.border-bottom.text-big)")) {
            val href = a.attr("href")
            val id = ID_REGEX.find(href)?.groupValues?.get(1) ?: continue
            val text = a.text().trim().replace(WHITESPACE, " ")
            val dateMatch = DATE_TAIL.find(text) ?: continue
            val dateStr = dateMatch.groupValues[1]
            val date = runCatching { LocalDate.parse(dateStr, DATE_FMT) }.getOrNull() ?: continue
            val title = text.removeSuffix(dateMatch.value).trim()
            if (title.isBlank()) continue
            results += Announcement(id = id, title = title, date = date, type = type)
        }
        return results
    }
}
