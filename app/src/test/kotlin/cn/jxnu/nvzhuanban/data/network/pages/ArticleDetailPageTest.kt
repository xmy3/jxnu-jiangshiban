package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.ParagraphAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知详情页 parser 回归测试。
 *
 * Fixture: `app/src/test/resources/samples/article_detail.html` —— 仿真一篇 jwc 通知，
 * 标题 / 时间 / 多段正文 / 加粗 / 内联颜色 / 表格 / 图片 / 附件 / 外链 / `javascript:` 都各出现一次。
 *
 * baseUri 取 `https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=99999`，
 * 用来验证相对路径 `../files/...` → `https://jwc.jxnu.edu.cn/files/...` 的 abs URL 解析。
 */
class ArticleDetailPageTest {

    private val baseUri = "https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=99999"

    @Test
    fun `parses title and posted time stripped of bracket wrapper`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)

        assertEquals("关于2026年春季学期期末考试安排的通知", parsed.title)
        // 「【时间：YYYY-MM-DD HH:mm:ss】」外壳应被剥掉
        assertEquals("2026-05-22 16:28:25", parsed.postedAt)
    }

    @Test
    fun `parses body into expected block sequence`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)

        // 至少应包含：段落 / 表格 / 段落 / 段落 / 图片 / 分割线 / 落款段落
        val kinds = parsed.blocks.map { it::class.simpleName }
        assertTrue("缺少 Paragraph: $kinds", "Paragraph" in kinds)
        assertTrue("缺少 Table: $kinds", "Table" in kinds)
        assertTrue("缺少 Attachment: $kinds", "Attachment" in kinds)
        assertTrue("缺少 Image: $kinds", "Image" in kinds)
        assertTrue("缺少 Divider: $kinds", "Divider" in kinds)
    }

    @Test
    fun `paragraph keeps inline bold and colored runs`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()

        val withBold = paragraphs.firstOrNull { p ->
            p.runs.any { it is InlineRun.Text && it.text.contains("期末考试") && it.style.bold }
        }
        assertNotNull("应有一段含『期末考试』的加粗 Text run", withBold)

        val withRed = paragraphs.firstOrNull { p ->
            p.runs.any { it is InlineRun.Text && it.text.contains("第18周") && it.style.color != null }
        }
        assertNotNull("应有一段含『第18周』的着色 Text run", withRed)
    }

    @Test
    fun `external https link becomes clickable Link, javascript anchor degrades to text`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
        val allLinks = paragraphs.flatMap { it.runs }.filterIsInstance<InlineRun.Link>()

        // 教育部考试政策网应作为 Link
        val externalLink = allLinks.firstOrNull { it.url == "https://www.example.com/policy" }
        assertNotNull("外链未识别为 Link: $allLinks", externalLink)
        assertEquals("教育部考试政策网", externalLink!!.text)

        // javascript: 链接不应进 Link
        assertTrue(
            "javascript: 链接被错误识别为 Link: $allLinks",
            allLinks.none { it.url.startsWith("javascript", ignoreCase = true) },
        )
    }

    @Test
    fun `attachment with pdf extension is extracted as Attachment block`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val attachment = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().firstOrNull()

        assertNotNull("应识别出 PDF 附件", attachment)
        assertEquals("2026春季期末考试安排表.pdf", attachment!!.name)
        // baseUri 解析后变绝对 URL
        assertEquals("https://jwc.jxnu.edu.cn/files/exam_2026_spring.pdf", attachment.url)
    }

    @Test
    fun `image src resolves to absolute url via baseUri`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val img = parsed.blocks.filterIsInstance<ArticleBlock.Image>().firstOrNull()

        assertNotNull("应解析出图片块", img)
        assertEquals("https://jwc.jxnu.edu.cn/files/exam_room_map.jpg", img!!.src)
        assertEquals("考场分布图", img.alt)
        // fixture 里的 img 不带 width/height 属性 → 无宽高信息，UI 退回最小高度占位
        assertNull(img.widthPx)
        assertNull(img.heightPx)
        assertNull(img.aspectRatio)
    }

    /**
     * `<img>` 的像素 width/height 属性（Word 粘贴产物通常带）要进模型，
     * 供 UI 在加载前按宽高比精确预占位；百分比 / auto 等非像素值不算数。
     */
    @Test
    fun `img pixel width and height attributes are captured, non-pixel values ignored`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-05-22 16:28:25】</div>
                <div id="main-content" class="line padding">
                  <p><img src="../files/a.jpg" width="554" height="312"></p>
                  <p><img src="../files/b.jpg" width="100%" height="auto"></p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val images = parsed.blocks.filterIsInstance<ArticleBlock.Image>()
        assertEquals(2, images.size)

        assertEquals(554, images[0].widthPx)
        assertEquals(312, images[0].heightPx)
        assertEquals(554f / 312f, images[0].aspectRatio!!, 1e-6f)

        assertNull(images[1].widthPx)
        assertNull(images[1].heightPx)
        assertNull(images[1].aspectRatio)
    }

    @Test
    fun `table extracts header + data rows`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val table = parsed.blocks.filterIsInstance<ArticleBlock.Table>().firstOrNull()

        assertNotNull("应解析出表格块", table)
        assertEquals("3 行（含表头）", 3, table!!.rows.size)
        assertEquals(3, table.rows[0].size)  // 课程 / 时间 / 地点
        // 表头第一格应是「课程」
        val headerFirstCell = table.rows[0][0]
        assertEquals(
            "课程",
            headerFirstCell.filterIsInstance<InlineRun.Text>().joinToString("") { it.text }.trim(),
        )
        // 数据行第一行第一格「高等数学」(<td><p>...</p></td> Word-wrap 形式)
        val firstRow = table.rows[1][0]
        assertEquals(
            "高等数学",
            firstRow.filterIsInstance<InlineRun.Text>().joinToString("") { it.text }.trim(),
        )
    }

    /**
     * 回归：jwc 通知的表格单元格经常是 Word 复制粘贴出来的 `<td><p>x</p></td>` 或
     * `<td><div>x</div></td>`，曾经因为 cell walker 内部 flushParagraph 把文本踢去
     * 不被读取的 blocks 列表，导致单元格在 UI 上全空白（只剩表格框）。
     */
    @Test
    fun `table cells with nested p or div still extract their text`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val table = parsed.blocks.filterIsInstance<ArticleBlock.Table>().firstOrNull()
        assertNotNull(table)

        fun flatten(runs: List<InlineRun>): String = runs.joinToString("") { r ->
            when (r) {
                is InlineRun.Text -> r.text
                is InlineRun.Link -> r.text
                InlineRun.LineBreak -> "\n"
            }
        }.trim()

        // 第 2 行（<td><p>...</p></td>）
        assertEquals("高等数学", flatten(table!!.rows[1][0]))
        assertEquals("6月22日 上午", flatten(table.rows[1][1]))
        assertEquals("文科楼A301", flatten(table.rows[1][2]))

        // 第 3 行（<td><div>...</div></td>，且第二格还带 <br/>）
        assertEquals("大学英语", flatten(table.rows[2][0]))
        assertEquals("6月23日\n下午", flatten(table.rows[2][1]))
        assertEquals("文科楼A302", flatten(table.rows[2][2]))
    }

    @Test
    fun `ul and ol list items become bulleted paragraphs`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-06-01 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <ul><li>第一条注意事项</li><li>第二条注意事项</li></ul>
                  <ol><li>有序第一项</li></ol>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphTexts = parsed.blocks
            .filterIsInstance<ArticleBlock.Paragraph>()
            .map { p -> p.runs.filterIsInstance<InlineRun.Text>().joinToString("") { it.text } }
        val bulleted = paragraphTexts.filter { it.startsWith("• ") }
        assertEquals("每个 li 应各成一段并带「• 」前缀: $paragraphTexts", 3, bulleted.size)
        assertTrue(bulleted.any { "第一条注意事项" in it })
        assertTrue(bulleted.any { "第二条注意事项" in it })
        assertTrue(bulleted.any { "有序第一项" in it })
    }

    @Test
    fun `download aspx link is attachment regardless of extension`() {
        // isAttachment 的另一形态：不带文件后缀、走 Download.aspx 的附件链接
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-06-01 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <p><a href="/Portal/Download.aspx?fileid=123">考试安排明细</a></p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val attachment = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().firstOrNull()
        assertNotNull("Download.aspx 链接应识别为附件: ${parsed.blocks}", attachment)
        assertEquals("考试安排明细", attachment!!.name)
        assertEquals("https://jwc.jxnu.edu.cn/Portal/Download.aspx?fileid=123", attachment.url)
    }

    @Test
    fun `returns empty ArticleDetail when no main-content`() {
        val parsed = ArticleDetailPage.parse("<html><body><p>hi</p></body></html>", baseUri)
        assertEquals("", parsed.title)
        assertEquals("", parsed.postedAt)
        assertTrue(parsed.blocks.isEmpty())
    }

    @Test
    fun `null-tolerant when inner main-content is missing`() {
        // 仅外层包壳，内层缺失（404 / 未登录页接近这种形状）
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">文档不存在！</div>
                <div class="line text-sub text-center"></div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertEquals("文档不存在！", parsed.title)
        assertEquals("", parsed.postedAt)
        assertTrue(parsed.blocks.isEmpty())
    }

    /**
     * 回归：jwc 无会话访问 ArticlesView.aspx 时返回的占位页（HTTP 200、同域、不重定向，
     * 所以不会被 JwcResponseGuard 当成会话过期）。标题即「对不起，该文档需要登录后再查看!」，
     * 页内只有一条指向网页登录的「登录」超链接。应被标记 requiresLogin 并清空正文，
     * 避免把那条外链渲染成可点击的「登录」→ 跳浏览器官方教务处。
     */
    @Test
    fun `login-required placeholder is flagged and body suppressed`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">对不起，该文档需要登录后再查看!</div>
                <div class="line text-sub text-center">【时间：2026-05-27 11:18:48】</div>
                <div id="main-content" class="line padding">
                  <a href="https://uis.jxnu.edu.cn/cas/login?service=https%3A%2F%2Fjwc.jxnu.edu.cn">登录</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertTrue("未识别为需要登录占位页", parsed.requiresLogin)
        assertEquals("对不起，该文档需要登录后再查看!", parsed.title)
        assertEquals("2026-05-27 11:18:48", parsed.postedAt)
        assertTrue("需要登录占位页不应保留正文块（含那条外链）", parsed.blocks.isEmpty())
    }

    @Test
    fun `normal article is not flagged as login-required`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        assertFalse(parsed.requiresLogin)
        assertTrue("正常文章应有正文块", parsed.blocks.isNotEmpty())
    }

    @Test
    fun `does not surface print or close buttons as blocks`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val flatText = parsed.blocks
            .filterIsInstance<ArticleBlock.Paragraph>()
            .joinToString("\n") { flatten(it) }
        // 「关闭窗口」位于外层 .border-top 容器里，不在内层 #main-content
        assertNull("『关闭窗口』被错误纳入正文", flatText.lineSequence().firstOrNull { "关闭窗口" in it })
    }

    /**
     * 回归：jwc CMS 在正文末尾追加「【上传的文件：<a>xxx.pdf</a>】」外壳。附件 `<a>` 被抽成
     * 独立卡片后，「【上传的文件：」和「】」曾残留为两个孤立段落，渲染成残缺文本。
     * fixture 末尾即这一形态，应只留附件卡片、不留外壳残片。
     */
    @Test
    fun `fixture CMS attachment wrapper leaves only the attachment card`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)

        val attachments = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>()
        assertEquals("正文附件 + CMS 外壳附件共两个: $attachments", 2, attachments.size)
        assertEquals("考试通知原文.pdf", attachments[1].name)
        assertEquals("https://jwc.jxnu.edu.cn/files/exam_notice_2026.pdf", attachments[1].url)

        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("外壳「【上传的文件：」应被剥掉: $paragraphTexts", paragraphTexts.none { "上传的文件" in it })
        assertTrue("孤立「】」应被剥掉: $paragraphTexts", paragraphTexts.none { "】" in it })
        // 作者自己写的「完整名单请参见附件：」不带外壳括号，不许误伤
        assertTrue("正文附件引导语应保留: $paragraphTexts", paragraphTexts.any { "完整名单请参见附件" in it })
    }

    @Test
    fun `wrapper stripping keeps surrounding prose outside the brackets`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>请各单位查收。【上传的文件：<a href="../files/a.pdf">名单.pdf</a>】请于7月20日前反馈。</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertEquals(1, parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().size)

        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("外壳前的正文应保留: $paragraphTexts", paragraphTexts.any { it == "请各单位查收。" })
        assertTrue("外壳后的正文应保留: $paragraphTexts", paragraphTexts.any { it == "请于7月20日前反馈。" })
        assertTrue("括号残片不应残留: $paragraphTexts", paragraphTexts.none { "【" in it || "】" in it })
    }

    @Test
    fun `wrapper with multiple attachments strips separators too`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>【上传的文件：<a href="../files/a.pdf">a.pdf</a>、<a href="../files/b.doc">b.doc</a><br/><a href="../files/c.xlsx">c.xlsx</a>】</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val attachments = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>()
        assertEquals("三个附件都应保留: ${parsed.blocks}", 3, attachments.size)
        assertEquals(listOf("a.pdf", "b.doc", "c.xlsx"), attachments.map { it.name })
        // 外壳文字和附件间的「、」分隔符都应清掉，不留任何段落
        assertTrue(
            "不应残留任何段落: ${parsed.blocks}",
            parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().isEmpty(),
        )
    }

    @Test
    fun `plain prose around attachment without brackets is left untouched`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>详见附件：<a href="../files/a.pdf">名单.pdf</a>敬请查收。</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        // 单侧无括号 → 成对判定不命中 → 作者正文一个字都不动
        assertTrue("「详见附件：」应保留: $paragraphTexts", paragraphTexts.any { it == "详见附件：" })
        assertTrue("「敬请查收。」应保留: $paragraphTexts", paragraphTexts.any { it == "敬请查收。" })
    }

    /**
     * 白名单负向：作者手写的「【申报表：<a>…</a>】」结构上与 CMS 外壳同构，但标签是
     * 有信息量的正文（「申报表」不在文件名里就真丢了）——不含样板词「上传的文件」必须整段不动。
     */
    @Test
    fun `author-written bracket label around attachment is not stripped`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>请下载【申报表：<a href="../files/sb.xlsx">2026年申报表.xlsx</a>】并填写。</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("作者标签「请下载【申报表：」应原样保留: $paragraphTexts", paragraphTexts.any { it == "请下载【申报表：" })
        assertTrue("「】并填写。」应原样保留: $paragraphTexts", paragraphTexts.any { it == "】并填写。" })
    }

    /** 单侧命中（opener 命中、closer 缺）也必须整段不动——钉住「成对才剥」的 && 不变量。 */
    @Test
    fun `opener without matching closer is left untouched`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>见【上传的文件：<a href="../files/a.pdf">a.pdf</a> 请查收</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("opener 残片应原样保留: $paragraphTexts", paragraphTexts.any { it == "见【上传的文件：" })
        assertTrue("附件后的正文应原样保留: $paragraphTexts", paragraphTexts.any { it == "请查收" })
    }

    /** WRAPPER_OPENER 的 `[^【】]*` 防越界：已闭合的「【附件1】」不许被当成 opener 往前吞。 */
    @Test
    fun `closed bracket pair before attachment is not swallowed as opener`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>见【附件1】：<a href="../files/a.pdf">a.pdf</a>】</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("「见【附件1】：」应原样保留: $paragraphTexts", paragraphTexts.any { it == "见【附件1】：" })
    }

    /** 背靠背双外壳：中间段「】【上传的文件：」先被上一轮剥剩 opener、再供下一轮消费。 */
    @Test
    fun `back-to-back double wrappers both stripped`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>【上传的文件：<a href="../files/a.pdf">a.pdf</a>】【上传的文件：<a href="../files/b.doc">b.doc</a>】</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val attachments = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>()
        assertEquals("两个外壳的附件都应保留: ${parsed.blocks}", listOf("a.pdf", "b.doc"), attachments.map { it.name })
        assertTrue(
            "双外壳应剥净、不残留任何段落: ${parsed.blocks}",
            parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().isEmpty(),
        )
    }

    /** 外壳被内联样式拆成多 run（「【」+ 带色「上传的文件：」）时也要能剥——匹配基于扁平文本。 */
    @Test
    fun `wrapper split across styled runs is still stripped`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>【<span style="color:#FF0000">上传的文件：</span><a href="../files/x.pdf">x.pdf</a>】</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertEquals(1, parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().size)
        assertTrue(
            "跨 run 外壳应剥净: ${parsed.blocks}",
            parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().isEmpty(),
        )
    }

    /** 闭合段首的纯标点（「。】」这类尾随分隔符）不阻断剥离；带实质文字（「并填写。】」）则阻断。 */
    @Test
    fun `closer with leading punctuation still strips, with real text does not`() {
        fun bodyWith(inner: String) = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">$inner</div>
              </div>
            </body></html>
        """.trimIndent()

        val punctuation = ArticleDetailPage.parse(
            bodyWith("""<div>【上传的文件：<a href="../files/a.pdf">a.pdf</a>。】</div>"""),
            baseUri,
        )
        assertTrue(
            "「。】」应连标点一起剥净: ${punctuation.blocks}",
            punctuation.blocks.filterIsInstance<ArticleBlock.Paragraph>().isEmpty(),
        )

        val realText = ArticleDetailPage.parse(
            bodyWith("""<div>【上传的文件：<a href="../files/a.pdf">a.pdf</a>如有疑问联系教务处】</div>"""),
            baseUri,
        )
        val texts = realText.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("】前有实质文字时应整段不动: $texts", texts.any { it == "【上传的文件：" })
        assertTrue("实质文字应原样保留: $texts", texts.any { it == "如有疑问联系教务处】" })
    }

    /** 「】」剥掉后，闭合段里尾随的 Link 等非 Text run 要作为独立段落存活。 */
    @Test
    fun `runs after the closer bracket survive stripping`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div>【上传的文件：<a href="../files/a.pdf">a.pdf</a>】<a href="https://ex.com/x">详情</a></div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertEquals(1, parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().size)
        val links = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
            .flatMap { it.runs }.filterIsInstance<InlineRun.Link>()
        assertEquals("」后的外链应存活: ${parsed.blocks}", listOf("详情"), links.map { it.text })
        val paragraphTexts = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { flatten(it) }
        assertTrue("「】」本身应剥掉: $paragraphTexts", paragraphTexts.none { "】" in it })
    }

    /**
     * 段落对齐还原：Word 粘贴正文常见的 `style="text-align:center"` 居中小标题、
     * `align="right"` 右对齐落款（教务处 + 日期），无声明时保持 START。
     */
    @Test
    fun `paragraph alignment parsed from style and legacy align attribute`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <p style="TEXT-ALIGN: center;">关于考试安排的补充说明</p>
                  <p>正文普通段落</p>
                  <p align="right">教务处</p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
        assertEquals(ParagraphAlign.CENTER, paragraphs.first { flatten(it) == "关于考试安排的补充说明" }.align)
        assertEquals(ParagraphAlign.START, paragraphs.first { flatten(it) == "正文普通段落" }.align)
        assertEquals(ParagraphAlign.END, paragraphs.first { flatten(it) == "教务处" }.align)
    }

    /** 对齐沿块级容器继承（Word 常写在外层包壳 div 上）；显式 left 是重置而非回退继承。 */
    @Test
    fun `alignment inherits through wrapper div and explicit left resets it`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <div style="text-align:center">
                    <p>继承居中的段落</p>
                    <p style="text-align:left">被显式重置回左对齐</p>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
        assertEquals(ParagraphAlign.CENTER, paragraphs.first { flatten(it) == "继承居中的段落" }.align)
        assertEquals(ParagraphAlign.START, paragraphs.first { flatten(it) == "被显式重置回左对齐" }.align)
    }

    /** 老派 `<center>` 标签（早期 CMS 编辑器产物）也应还原为居中；justify 视作 START。 */
    @Test
    fun `center tag centers and justify maps to start`() {
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">测试通知</div>
                <div class="line text-sub text-center">【时间：2026-07-13 10:00:00】</div>
                <div id="main-content" class="line padding">
                  <center>居中的附件标题</center>
                  <p style="text-align:justify">两端对齐按普通段落处理</p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
        assertEquals(ParagraphAlign.CENTER, paragraphs.first { flatten(it) == "居中的附件标题" }.align)
        assertEquals(ParagraphAlign.START, paragraphs.first { flatten(it) == "两端对齐按普通段落处理" }.align)
    }

    private fun flatten(paragraph: ArticleBlock.Paragraph): String =
        paragraph.runs.joinToString("") { run ->
            when (run) {
                is InlineRun.Text -> run.text
                is InlineRun.Link -> run.text
                InlineRun.LineBreak -> "\n"
            }
        }.trim()
}
