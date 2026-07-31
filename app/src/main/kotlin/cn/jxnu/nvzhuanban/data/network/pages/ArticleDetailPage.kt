package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.InlineStyle
import cn.jxnu.nvzhuanban.data.model.ParagraphAlign
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 教务通知详情页解析器：`Portal/ArticlesView.aspx?id=<id>`。
 *
 * 页面结构（外层 jwc 模板已省略）：
 * ```html
 * <div id="main-content" class="line padding-big-bottom">         <-- 外层
 *   <div class="text-large border-bottom padding text-center">标题</div>
 *   <div class="line text-sub text-center">【时间：YYYY-MM-DD HH:mm:ss】</div>
 *   <div id="main-content" class="line padding">…正文 HTML…</div>   <-- 内层（重复的 id）
 *   <div class="line text-right border-top margin padding">…打印/关闭…</div>
 * </div>
 * ```
 *
 * 关键点：
 * - `#main-content` id **重复出现**两次。外层是包壳，内层才是正文。用 `outer.children` 显式找内层，
 *   避免 `selectFirst` 命中外层自身。
 * - 必须给 [Jsoup.parse] 传 baseUri（请求 URL），否则 `<img src="../files/x.jpg">` / 相对 `<a href>`
 *   解析出来都是空字符串。
 * - jwc 详情页是 Word 复制粘贴出身，DOM 里全是 `<div>` 包 `<div>` + 内联 `<span style>`。解析时把
 *   "块级容器无块级子节点"视作一个段落，"块级容器有块级子节点"则递归下钻；这样能把 Word 嵌套层级
 *   摊平到 Compose LazyColumn 可消费的扁平 [ArticleBlock] 序列。
 */
object ArticleDetailPage {

    private val ATTACHMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "rar", "7z", "txt", "csv",
    )

    /**
     * 教务网时间外壳：「【时间：YYYY-MM-DD HH:mm:ss】」。冒号兼容中英文。
     * 抓不到时回退到原文 trim（部分历史 fixture 没带这层壳）。
     */
    private val POSTED_REGEX = Regex("""【\s*时间\s*[:：]\s*(.+?)\s*】""")

    /**
     * jwc 无会话时 `ArticlesView.aspx` 返回的占位页标题特征：
     * 「对不起，该文档需要登录后再查看!」。命中后整页按"需要登录"处理，不再把页内那条
     * 指向网页登录的「登录」超链接渲染成外链（避免点击跳出 app 去官方教务处网页）。
     * 真实通知标题不会出现这些短语，误判风险可忽略。
     */
    private val LOGIN_REQUIRED_TITLE = Regex("""需要登录|登录后再?查看|请登录""")

    /** `<font color>` / `style="color:..."` 共用：六位 hex（带或不带 #），或最常见的英文色名。 */
    private val HEX_COLOR = Regex("""#?([0-9a-fA-F]{6})""")
    private val NAMED_COLORS = mapOf(
        "red" to 0xFFFF0000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "green" to 0xFF008000.toInt(),
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
    )

    /** `style="color:#abcdef; font-weight: bold"` 里抽 color。其他样式当前不支持。 */
    private val STYLE_COLOR = Regex("""(?i)color\s*:\s*([^;]+)""")

    /** `style="text-align: center"`。Word 粘贴正文的居中小标题 / 右对齐落款靠它还原。 */
    private val STYLE_TEXT_ALIGN = Regex("""(?i)text-align\s*:\s*([a-z-]+)""")

    /** 块级 HTML 标签集合，用于判断一个容器内是否有块级子元素。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "blockquote", "center",
        "table", "thead", "tbody", "tr", "td", "th",
        "ul", "ol", "li", "hr", "img",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    fun parse(html: String, baseUri: String): ArticleDetail {
        val doc = Jsoup.parse(html, baseUri)
        // 教务网每个详情页都有 form#aspnetForm > div.main-container > div#main-content（外层），
        // selectFirst 命中的就是这个外壳。
        val outer = doc.selectFirst("div#main-content")
            ?: return ArticleDetail(title = "", postedAt = "", blocks = emptyList())

        val title = outer.selectFirst(".text-large.border-bottom")?.text()?.trim().orEmpty()
        val dateRaw = outer.selectFirst(".text-sub.text-center")?.text().orEmpty()
        val postedAt = POSTED_REGEX.find(dateRaw)?.groupValues?.get(1)?.trim()
            ?: dateRaw.trim()

        // 未登录占位页：标题即「…需要登录后再查看!」。短路返回，blocks 置空，交给 UI 引导内部登录。
        if (LOGIN_REQUIRED_TITLE.containsMatchIn(title)) {
            return ArticleDetail(title, postedAt, blocks = emptyList(), requiresLogin = true)
        }

        // 显式从外层 children 里找内层 #main-content，不用 selectFirst 防止把外层自己拿回来
        val body = outer.children().firstOrNull { it.id() == "main-content" }
            ?: return ArticleDetail(title, postedAt, emptyList())

        val blocks = stripAttachmentWrappers(BodyWalker().walk(body))
        return ArticleDetail(title, postedAt, blocks)
    }

    /**
     * jwc CMS 会在正文末尾追加「【上传的文件：<a>xxx.pdf</a>】」外壳。附件 `<a>` 被抽成独立的
     * [ArticleBlock.Attachment] 后，外壳文字断成「【上传的文件：」和「】」两个孤立段落，渲染出来
     * 像残缺文本。附件卡片自带图标语义，这层外壳直接剥掉。
     *
     * 两重防误伤（缺一不剥，未命中一律原样保留＝改动前行为）：
     * - **成对命中**：附件串前一段以未闭合的「【…」结尾，且后一段以「】」开头（允许前导纯标点，
     *   兼容「。】」「、】」这类尾随分隔符）；
     * - **样板词白名单**：opener 残片必须含「上传的文件」。纯结构判定会把作者手写的
     *   「【申报表：<a>…</a>】」里的标签一并删掉（信息丢失）；白名单代价是 CMS 未来改措辞时
     *   剥离静默失效、退回残片显示，属可接受降级。
     *
     * 附件之间夹的纯分隔符段（如「、」）在命中外壳时一并清掉。
     */
    private fun stripAttachmentWrappers(blocks: List<ArticleBlock>): List<ArticleBlock> {
        val out = mutableListOf<ArticleBlock>()
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            if (block !is ArticleBlock.Attachment) {
                out += block
                i++
                continue
            }

            // 收拢连续附件串 [i..end]；attachments 只收附件本身，夹在中间的分隔符段不进来——
            // 命中外壳时 out += attachments 就把它们清掉，未命中则按原序整段搬运保留
            var end = i
            val attachments = mutableListOf<ArticleBlock>(block)
            while (end + 1 < blocks.size) {
                val next = blocks[end + 1]
                if (next is ArticleBlock.Attachment) {
                    attachments += next
                    end++
                    continue
                }
                if (next is ArticleBlock.Paragraph && isSeparatorParagraph(next) &&
                    end + 2 < blocks.size && blocks[end + 2] is ArticleBlock.Attachment
                ) {
                    attachments += blocks[end + 2]
                    end += 2
                    continue
                }
                break
            }

            // prev 从 out 尾部取而不是 blocks[i-1]：两个外壳背靠背时（「】【上传的文件：」合成一段），
            // 上一轮剥剩的「【上传的文件：」正好是本轮的 opener
            val prev = out.lastOrNull() as? ArticleBlock.Paragraph
            val next = blocks.getOrNull(end + 1) as? ArticleBlock.Paragraph
            val strippedPrev = prev?.let(::stripTrailingOpener)
            val strippedNext = next?.let(::stripLeadingCloser)
            if (strippedPrev != null && strippedNext != null) {
                out.removeAt(out.lastIndex)
                if (strippedPrev.runs.isNotEmpty()) out += strippedPrev
                out += attachments
                if (strippedNext.runs.isNotEmpty()) out += strippedNext
                i = end + 2
            } else {
                for (j in i..end) out += blocks[j]
                i = end + 1
            }
        }
        return out
    }

    /**
     * CMS 外壳 opener：段落尾部未闭合的「【…上传的文件…」残片。必须含样板词「上传的文件」，
     * 见 [stripAttachmentWrappers] 的白名单说明；`[^【】]*` 保证不会越过一对已闭合的括号往前吞。
     */
    private val WRAPPER_OPENER = Regex("""【[^【】]*上传的文件[^【】]*$""")

    /**
     * 命中返回剥掉残片后的段落（runs 可能为空，调用方负责丢弃空段），未命中返回 null。
     *
     * 匹配/剥除都作用在段落**尾部连续 Text runs 的扁平文本**上：外壳可能被内联样式拆成多个
     * run（如「【」+ 带色的「上传的文件：」），只看最后一个 run 会漏。
     */
    private fun stripTrailingOpener(paragraph: ArticleBlock.Paragraph): ArticleBlock.Paragraph? {
        val runs = paragraph.runs
        var tailStart = runs.size
        while (tailStart > 0 && runs[tailStart - 1] is InlineRun.Text) tailStart--
        val tail = runs.subList(tailStart, runs.size).map { it as InlineRun.Text }
        val tailText = tail.joinToString("") { it.text }
        val match = WRAPPER_OPENER.find(tailText) ?: return null
        val kept = runs.subList(0, tailStart) + takeLeadingChars(tail, match.range.first)
        return ArticleBlock.Paragraph(trimEdgeRuns(kept), paragraph.align)
    }

    /**
     * closer 判定：段落开头连续 Text runs 的扁平文本里，「】」之前只允许标点/空白
     * （兼容「。】」「、】」这类 CMS 尾随分隔符），出现任何字母数字/汉字都不算闭合段。
     */
    private fun stripLeadingCloser(paragraph: ArticleBlock.Paragraph): ArticleBlock.Paragraph? {
        val runs = paragraph.runs
        var headEnd = 0
        while (headEnd < runs.size && runs[headEnd] is InlineRun.Text) headEnd++
        val head = runs.subList(0, headEnd).map { it as InlineRun.Text }
        val headText = head.joinToString("") { it.text }
        val closerIndex = headText.indexOf('】')
        if (closerIndex < 0) return null
        if (headText.take(closerIndex).any { it.isLetterOrDigit() }) return null
        val kept = dropLeadingChars(head, closerIndex + 1) + runs.subList(headEnd, runs.size)
        return ArticleBlock.Paragraph(trimEdgeRuns(kept), paragraph.align)
    }

    /** 连续 Text runs 按扁平字符数截断：保留前 [count] 个字符，样式跟原 run 走。 */
    private fun takeLeadingChars(runs: List<InlineRun.Text>, count: Int): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        var remaining = count
        for (run in runs) {
            if (remaining <= 0) break
            if (run.text.length <= remaining) {
                out += run
                remaining -= run.text.length
            } else {
                out += run.copy(text = run.text.take(remaining))
                remaining = 0
            }
        }
        // 剥完残片后裸露的尾随空白（如「请下载 【…」剩「请下载 」）顺手修掉
        (out.lastOrNull() as? InlineRun.Text)?.let { out[out.lastIndex] = it.copy(text = it.text.trimEnd()) }
        return out
    }

    /** 连续 Text runs 按扁平字符数截断：丢弃前 [count] 个字符，样式跟原 run 走。 */
    private fun dropLeadingChars(runs: List<InlineRun.Text>, count: Int): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        var remaining = count
        for (run in runs) {
            if (remaining >= run.text.length) {
                remaining -= run.text.length
            } else {
                out += if (remaining > 0) run.copy(text = run.text.drop(remaining)) else run
                remaining = 0
            }
        }
        (out.firstOrNull() as? InlineRun.Text)?.let { out[0] = it.copy(text = it.text.trimStart()) }
        return out
    }

    /** 剥完残片后段落边缘可能裸露 LineBreak / 空白 Text，照 normalise 的口径修掉。 */
    private fun trimEdgeRuns(runs: List<InlineRun>): List<InlineRun> {
        val list = runs.toMutableList()
        while (list.isNotEmpty() && list.first().isEdgeJunk()) list.removeAt(0)
        while (list.isNotEmpty() && list.last().isEdgeJunk()) list.removeAt(list.lastIndex)
        return list
    }

    private fun InlineRun.isEdgeJunk(): Boolean =
        this is InlineRun.LineBreak || (this is InlineRun.Text && text.isBlank())

    /** 附件之间的纯标点/空白段（如「、」）——不含任何字母数字/汉字才算。 */
    private fun isSeparatorParagraph(paragraph: ArticleBlock.Paragraph): Boolean =
        paragraph.runs.all { it is InlineRun.Text || it is InlineRun.LineBreak } &&
            paragraph.runs.filterIsInstance<InlineRun.Text>()
                .joinToString("") { it.text }
                .none { it.isLetterOrDigit() }

    /**
     * 递归把 jwc 内层 #main-content 的子树摊平成 [ArticleBlock] 列表。
     *
     * 设计要点：
     * - 累加器模式：内联 runs 收在 [pendingRuns] 里，碰到块级 boundary（`<p>` / `<div>` 关闭、
     *   `<img>` / `<table>` / `<hr>` 出现）就 flush 成一个 [ArticleBlock.Paragraph]。
     * - 样式继承：`<b>` / `<strong>` / `<i>` / `<em>` / `<u>` / `<font color>` / `style="color:"`
     *   通过参数 [InlineStyle] 传递；离开 element 时自动恢复（依赖 Kotlin 函数栈，不需要显式 push/pop）。
     * - 表格特殊化：[parseTable] 用 [BodyWalker] 子实例隔离 pendingRuns，避免单元格内的 runs
     *   污染外层段落状态。**单元格用 `inlineOnly = true` 实例**——Word 复制粘贴的 HTML 经常把
     *   单元格内容包在 `<p>` / `<div>` 里，原本的 flushParagraph 会把这些文本踢去 `blocks`，而
     *   cell walker 不读 blocks → 单元格全空。inlineOnly 模式把 flush 改成插 LineBreak，文本
     *   留在 pendingRuns 里被 collectCellInline 收回。
     */
    private class BodyWalker(private val inlineOnly: Boolean = false) {
        private val blocks = mutableListOf<ArticleBlock>()
        private val pendingRuns = mutableListOf<InlineRun>()

        fun walk(body: Element): List<ArticleBlock> {
            val rootAlign = alignOf(body, ParagraphAlign.START)
            walkChildren(body, InlineStyle(), rootAlign)
            flushParagraph(rootAlign)
            return blocks.toList()
        }

        private fun walkChildren(element: Element, style: InlineStyle, align: ParagraphAlign) {
            for (node in element.childNodes()) walkNode(node, style, align)
        }

        private fun walkNode(node: Node, style: InlineStyle, align: ParagraphAlign) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotEmpty()) pendingRuns += InlineRun.Text(text, style)
                }
                is Element -> walkElement(node, style, align)
                else -> Unit
            }
        }

        private fun walkElement(el: Element, style: InlineStyle, align: ParagraphAlign) {
            when (el.tagName().lowercase()) {
                "img" -> {
                    flushParagraph(align)
                    val src = el.absUrl("src")
                    if (src.isNotBlank()) {
                        blocks += ArticleBlock.Image(
                            src = src,
                            alt = el.attr("alt").takeIf { it.isNotBlank() },
                            widthPx = parseImgDimension(el.attr("width")),
                            heightPx = parseImgDimension(el.attr("height")),
                        )
                    }
                }
                "table" -> {
                    flushParagraph(align)
                    val rows = parseTable(el)
                    if (rows.isNotEmpty()) blocks += ArticleBlock.Table(rows)
                }
                "hr" -> {
                    flushParagraph(align)
                    blocks += ArticleBlock.Divider
                }
                "br" -> pendingRuns += InlineRun.LineBreak

                "a" -> {
                    val href = el.absUrl("href").trim()
                    if (isAttachment(href)) {
                        flushParagraph(align)
                        val name = el.text().trim().ifBlank { hrefFileName(href) }
                        blocks += ArticleBlock.Attachment(name, href)
                    } else {
                        val text = el.text()
                        // 只把真正的 http(s) 外链做成可点击 Link；
                        // javascript:/相对锚点的 anchor 直接降级为文本，避免点击落空。
                        if ((href.startsWith("http://", ignoreCase = true) ||
                                href.startsWith("https://", ignoreCase = true)) &&
                            text.isNotBlank()
                        ) {
                            pendingRuns += InlineRun.Link(text.trim(), href, style)
                        } else {
                            walkChildren(el, style, align)
                        }
                    }
                }

                "p", "div", "section", "article", "blockquote", "center" -> {
                    val merged = mergeStyle(style, el)
                    // 对齐沿块级容器继承——Word 常把 text-align 写在外层包壳 div 上
                    val mergedAlign = alignOf(el, align)
                    val hasBlockChildren = el.children().any { it.tagName().lowercase() in BLOCK_TAGS }
                    if (hasBlockChildren) {
                        // 容器之前累积的散排文本属于外层语境，用外层对齐 flush
                        flushParagraph(align)
                        walkChildren(el, merged, mergedAlign)
                        flushParagraph(mergedAlign)
                    } else {
                        // 纯内联容器：把内容收进 pendingRuns 后立刻 flush，
                        // 维持「div 之间天然换段」语义。
                        walkChildren(el, merged, mergedAlign)
                        flushParagraph(mergedAlign)
                    }
                }

                "ul", "ol" -> {
                    flushParagraph(align)
                    walkChildren(el, style, align)
                    flushParagraph(align)
                }
                "li" -> {
                    val mergedAlign = alignOf(el, align)
                    flushParagraph(align)
                    pendingRuns += InlineRun.Text("• ", style)
                    walkChildren(el, style, mergedAlign)
                    flushParagraph(mergedAlign)
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    // 标题在通知正文里很罕见；当作加粗段落即可，省一类型
                    val mergedAlign = alignOf(el, align)
                    flushParagraph(align)
                    walkChildren(el, style.copy(bold = true), mergedAlign)
                    flushParagraph(mergedAlign)
                }

                "b", "strong" -> walkChildren(el, style.copy(bold = true), align)
                "i", "em" -> walkChildren(el, style.copy(italic = true), align)
                "u" -> walkChildren(el, style.copy(underline = true), align)

                "font" -> {
                    val color = parseHtmlColor(el.attr("color"))
                    walkChildren(el, if (color != null) style.copy(color = color) else style, align)
                }
                "span" -> walkChildren(el, mergeStyle(style, el), align)

                else -> walkChildren(el, style, align)
            }
        }

        private fun parseTable(table: Element): List<List<List<InlineRun>>> {
            val rows = mutableListOf<List<List<InlineRun>>>()
            for (tr in table.select("tr")) {
                val row = mutableListOf<List<InlineRun>>()
                for (cell in tr.children()) {
                    val tag = cell.tagName().lowercase()
                    if (tag != "td" && tag != "th") continue
                    // inlineOnly = true:单元格内的 <p>/<div> 不会切段,文本留在 pendingRuns 里
                    row += BodyWalker(inlineOnly = true).collectCellInline(cell)
                }
                if (row.isNotEmpty()) rows += row
            }
            return rows
        }

        private fun collectCellInline(cell: Element): List<InlineRun> {
            walkChildren(cell, InlineStyle(), ParagraphAlign.START)
            // 单元格内允许 `<br>` 换行但不需要分段，所以不调 flushParagraph
            val collected = normalise(pendingRuns.toList())
            pendingRuns.clear()
            return collected
        }

        private fun flushParagraph(align: ParagraphAlign = ParagraphAlign.START) {
            if (inlineOnly) {
                // 单元格模式：原本会切段的位置改成在 pendingRuns 里塞一个 LineBreak，
                // 文本不离开 pendingRuns。collectCellInline 末尾交给 normalise 清理首尾 LB。
                if (pendingRuns.isNotEmpty() && pendingRuns.lastOrNull() !is InlineRun.LineBreak) {
                    pendingRuns += InlineRun.LineBreak
                }
                return
            }
            if (pendingRuns.isEmpty()) return
            val normalised = normalise(pendingRuns.toList())
            pendingRuns.clear()
            if (normalised.isNotEmpty()) blocks += ArticleBlock.Paragraph(normalised, align)
        }

        /**
         * 段内规范化：
         *  - 合并相邻、样式相同的 Text；
         *  - 多空白塌缩成单个空格（HTML 渲染语义）；
         *  - 去掉首尾纯空白 Text **以及首尾的 LineBreak**（单元格模式下 flushParagraph 会留尾 LB）；
         *  - 全为空白则返回空列表（这样上游 flush 不会塞空段）。
         */
        private fun normalise(runs: List<InlineRun>): List<InlineRun> {
            if (runs.isEmpty()) return emptyList()
            val collapsed = runs.map { run ->
                when (run) {
                    is InlineRun.Text -> run.copy(text = run.text.replace(WHITESPACE, " "))
                    else -> run
                }
            }
            val merged = mutableListOf<InlineRun>()
            for (run in collapsed) {
                val last = merged.lastOrNull()
                if (run is InlineRun.Text && last is InlineRun.Text && last.style == run.style) {
                    merged[merged.lastIndex] = last.copy(text = last.text + run.text)
                } else {
                    merged += run
                }
            }
            // 修剪首尾空白文本 / LineBreak
            while (merged.isNotEmpty()) {
                val first = merged.first()
                if (first is InlineRun.LineBreak) { merged.removeAt(0); continue }
                if (first is InlineRun.Text && first.text.isBlank()) { merged.removeAt(0); continue }
                break
            }
            while (merged.isNotEmpty()) {
                val last = merged.last()
                if (last is InlineRun.LineBreak) { merged.removeAt(merged.lastIndex); continue }
                if (last is InlineRun.Text && last.text.isBlank()) { merged.removeAt(merged.lastIndex); continue }
                break
            }
            if (merged.all { it is InlineRun.Text && it.text.isBlank() }) return emptyList()
            return merged
        }

        private fun mergeStyle(base: InlineStyle, el: Element): InlineStyle {
            val styleAttr = el.attr("style")
            if (styleAttr.isBlank()) return base
            val color = STYLE_COLOR.find(styleAttr)?.groupValues?.get(1)?.let { parseHtmlColor(it) }
            return if (color != null) base.copy(color = color) else base
        }

        /**
         * 元素自身声明的对齐：`style="text-align:…"` 优先，退回传统 `align` 属性，
         * `<center>` 标签天然居中。声明缺失或值不认识时沿用 [inherited]（对齐随块级容器
         * 继承，Word 常写在外层包壳上）；显式 left/start/justify 是**重置**语义，不回退继承值。
         */
        private fun alignOf(el: Element, inherited: ParagraphAlign): ParagraphAlign {
            val declared = STYLE_TEXT_ALIGN.find(el.attr("style"))?.groupValues?.get(1)
                ?: el.attr("align").trim().ifBlank { null }
                ?: if (el.tagName().lowercase() == "center") "center" else null
            return when (declared?.lowercase()) {
                "center" -> ParagraphAlign.CENTER
                "right", "end" -> ParagraphAlign.END
                "left", "start", "justify" -> ParagraphAlign.START
                else -> inherited
            }
        }

        private fun isAttachment(href: String): Boolean {
            if (href.isBlank()) return false
            val lower = href.lowercase()
            if ("download.aspx" in lower) return true
            val pathOnly = lower.substringBefore('?').substringBefore('#')
            val ext = pathOnly.substringAfterLast('.', "")
            return ext.isNotBlank() && ext in ATTACHMENT_EXTENSIONS
        }

        private fun hrefFileName(href: String): String {
            val path = href.substringBefore('?').substringBefore('#')
            val tail = path.substringAfterLast('/', missingDelimiterValue = path)
            return tail.ifBlank { href }
        }

        /**
         * `<img width="554">` / `width="554px"` → 554；百分比（`100%`）、`auto` 等
         * 非像素值返回 null，UI 层退回最小高度占位而不是错误的宽高比。
         */
        private fun parseImgDimension(value: String): Int? =
            value.trim().removeSuffix("px").trim().toIntOrNull()?.takeIf { it > 0 }

        private fun parseHtmlColor(value: String): Int? {
            val v = value.trim().lowercase()
            if (v.isEmpty()) return null
            NAMED_COLORS[v]?.let { return it }
            val hex = HEX_COLOR.find(v)?.groupValues?.get(1) ?: return null
            return runCatching { (0xFF000000.toInt()) or hex.toLong(16).toInt() }.getOrNull()
        }

        companion object {
            private val WHITESPACE = Regex("""\s+""")
        }
    }
}
