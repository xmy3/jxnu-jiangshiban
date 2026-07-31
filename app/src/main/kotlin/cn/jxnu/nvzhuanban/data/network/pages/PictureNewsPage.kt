package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 图文新闻列表页解析器：`Portal/ArticlesPictureNews.aspx?page=N`（1-indexed）。
 *
 * 列表里每一张卡片：
 * ```html
 * <div class="border border-red radius padding-small float-left margin-small text-center">
 *   <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13479" target="_blank">
 *     <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202512311732282.png"
 *          border="0" width="240px" height="180px">
 *     <div class="line text-small">
 *       【2026-03-09】 新闻与传播学院召开2022级教育技术学专业实习总结大会暨研习动员大会
 *     </div>
 *   </a>
 * </div>
 * ```
 *
 * 与 [AnnouncementPage] 的差异：
 *   - href 用大写 `ID=`（ASP.NET 大小写不敏感，但解析器统一兜底）
 *   - 日期 `【YYYY-M-D】` 出现在文本**开头**而非结尾
 *   - 每项带 240×180 缩略图，需要解析 `img.src`；存到 [Announcement.thumbnailUrl]
 *
 * 浏览器 "Save As" 保存的样本会把 `img.src` 改写成 `./picture_news_files/...` 的本地路径，
 * 而线上 HTML 是绝对 URL；本解析器对两种都兼容，但 baseUrl 参数会让 [Jsoup.parse] 的
 * `abs:src` 在线上正确解析。
 *
 * 每页固定 9 条；列表共 100+ 页，分页通过 `?page=N` 翻。
 */
object PictureNewsPage {

    /** 日期出现在文本开头：`【YYYY-M-D】`。容忍 1 位或 2 位月/日。 */
    private val DATE_HEAD = Regex("""【(\d{4}-\d{1,2}-\d{1,2})】""")

    /** href 上的 `id=数字` / `ID=数字`，两种大小写都接。 */
    private val ID_REGEX = Regex("""(?i)id=(\d+)""")

    private val WHITESPACE = Regex("""\s+""")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ROOT)

    /**
     * @param baseUrl 用于把图片相对路径解析为绝对 URL。生产环境 img src 已经是绝对路径，
     *                这里主要给浏览器另存的样本兜底。
     */
    fun parse(
        html: String,
        baseUrl: String = "https://jwc.jxnu.edu.cn/Portal/ArticlesPictureNews.aspx",
    ): List<Announcement> {
        val doc = Jsoup.parse(html, baseUrl)
        val results = mutableListOf<Announcement>()
        // 选择器锁定 picture news 特有的红框卡片，避免与导航/footer 上其他 <a> 撞车。
        for (a in doc.select("div.border.border-red > a")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val id = ID_REGEX.find(href)?.groupValues?.get(1) ?: continue
            val text = a.text().trim().replace(WHITESPACE, " ")
            val dateMatch = DATE_HEAD.find(text) ?: continue
            val date = runCatching {
                LocalDate.parse(dateMatch.groupValues[1], DATE_FMT)
            }.getOrNull() ?: continue
            val title = text.removeRange(dateMatch.range).trim()
            if (title.isBlank()) continue
            val img = a.selectFirst("img")
            val thumb = img?.let { it.attr("abs:src").ifBlank { it.attr("src") } }
                ?.takeIf { it.isNotBlank() }
            results += Announcement(
                id = id,
                title = title,
                date = date,
                type = AnnouncementType.PICTURE_NEWS,
                thumbnailUrl = thumb,
            )
        }
        return results
    }
}
