package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.CalendarFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 教学周历（校历）索引页解析回归测试。
 *
 * 真实样本走 `app/src/test/resources/samples/calendar.html`（保留原始 GBK 字节，由测试自己解码），
 * 因为生产路径就是「OkHttp 拿字节 → 显式 GBK decode」。
 *
 * fixture 字符串覆盖边角情形：标题双空格、bg-blue 调整版、wnl.htm、各文件类型后缀、插件注入噪声。
 */
class CalendarPageTest {

    private val GBK: Charset = Charset.forName("GBK")

    @Test
    fun `parses 2026-2027 first semester pdf entry from real sample`() {
        val html = loadGbkSample()
        val entries = CalendarPage.parse(html)
        val target = entries.firstOrNull { it.url.endsWith("Jxzl_20260901.pdf") }
        assertNotNull("应解析出 2026-2027 第一学期", target)
        assertEquals("2026-2027学年第一学期", target!!.title)
        assertEquals(CalendarFileType.PDF, target.fileType)
        assertFalse(target.isPerpetual)
        assertFalse(target.isCorrection)
    }

    @Test
    fun `parses 2019-2020 correction marked with bg-blue from real sample`() {
        val html = loadGbkSample()
        val entries = CalendarPage.parse(html)
        // 调整版只有 1 条
        val corrections = entries.filter { it.isCorrection }
        assertEquals(1, corrections.size)
        val c = corrections.single()
        assertTrue("调整版标题含 '调整'", c.title.contains("调整"))
        assertTrue("调整版指向 Jxzl_20200301.pdf", c.url.endsWith("Jxzl_20200301.pdf"))
    }

    @Test
    fun `parses perpetual calendar wnl_htm from real sample`() {
        val html = loadGbkSample()
        val entries = CalendarPage.parse(html)
        val wnl = entries.firstOrNull { it.isPerpetual }
        assertNotNull("应解析出万年历", wnl)
        assertEquals("万年历", wnl!!.title)
        assertEquals(CalendarFileType.HTM, wnl.fileType)
        assertTrue(wnl.url.endsWith("wnl.htm"))
    }

    @Test
    fun `assigns file type from url suffix for pdf doc xls jpg htm`() {
        val entries = CalendarPage.parse(FIXTURE_FILETYPES)
        val byUrl = entries.associateBy { it.url }
        assertEquals(CalendarFileType.PDF, byUrl.getValue("https://jwc.jxnu.edu.cn/Jxzl_a.pdf").fileType)
        assertEquals(CalendarFileType.DOC, byUrl.getValue("https://jwc.jxnu.edu.cn/Jxzl_b.doc").fileType)
        assertEquals(CalendarFileType.XLS, byUrl.getValue("https://jwc.jxnu.edu.cn/Jxzl_c.xls").fileType)
        assertEquals(CalendarFileType.JPG, byUrl.getValue("https://jwc.jxnu.edu.cn/Jxzl_d.jpg").fileType)
        assertEquals(CalendarFileType.HTM, byUrl.getValue("https://jwc.jxnu.edu.cn/wnl.htm").fileType)
    }

    @Test
    fun `does not include extension or unrelated anchor noise`() {
        val entries = CalendarPage.parse(FIXTURE_NOISE)
        // 唯一合法项是 Jxzl_20260901.pdf
        assertEquals(1, entries.size)
        assertEquals("2026-2027学年第一学期", entries.single().title)
    }

    @Test
    fun `normalizes title whitespace`() {
        // 真实样本里 2012-2013 二学期就是 "2012-2013学年  第二学期" 双空格
        val entries = CalendarPage.parse(loadGbkSample())
        val target = entries.firstOrNull { it.url.endsWith("Jxzl_20130301.doc") }
        assertNotNull("应解析出 2012-2013 第二学期", target)
        // 多空格应被合并为单空格，且不应有首尾空白
        assertEquals("2012-2013学年 第二学期", target!!.title)
    }

    @Test
    fun `real sample contains a reasonable number of entries`() {
        val entries = CalendarPage.parse(loadGbkSample())
        // 真实样本至少几十条；上限设宽松点，防止学校加几条还要改测试
        assertTrue("校历条目数应在 30~80 之间，实际=${entries.size}", entries.size in 30..80)
        // 都必须是 https
        assertTrue(entries.all { it.url.startsWith("https://") })
        // 都必须有非空 title
        assertTrue(entries.all { it.title.isNotBlank() })
    }

    @Test
    fun `entries without href or with blank text are skipped`() {
        val entries = CalendarPage.parse(FIXTURE_EMPTY)
        // FIXTURE_EMPTY 里 3 个 a：缺 href / 空文本 / 合法。只保留 1 条
        assertEquals(1, entries.size)
        assertEquals("唯一合法条目", entries.single().title)
    }

    @Test
    fun `parser does not return entries for unknown file types`() {
        // 实际样本没出现 OTHER；但解析器若遇到 .zip 之类应仍能收录并标记为 OTHER
        val entries = CalendarPage.parse(FIXTURE_UNKNOWN_TYPE)
        val unknown = entries.firstOrNull { it.url.endsWith("Jxzl_weird.zip") }
        assertNotNull("Jxzl_weird.zip 应被收录", unknown)
        assertEquals(CalendarFileType.OTHER, unknown!!.fileType)
    }

    @Test
    fun `bg-red entries are not flagged as corrections`() {
        // 默认大多数链接都是 bg-red，不应被误标为调整版
        val entries = CalendarPage.parse(loadGbkSample())
        val nonCorrectionPdfs = entries.filter { it.fileType == CalendarFileType.PDF && !it.isCorrection }
        assertTrue("正常 PDF 至少 10 条", nonCorrectionPdfs.size >= 10)
        // 万年历是 htm 而不是 pdf，所以一定要单独检查
        val wnl = entries.first { it.isPerpetual }
        assertFalse(wnl.isCorrection)
    }

    @Test
    fun `gbk decoding produces no replacement characters for chinese labels`() {
        // 这条测试间接验证 GBK 解码路径：如果有人改用 UTF-8 解码同一份字节，
        // 大量替换字符 (U+FFFD) 或类似 mojibake (例如「ѧ」「��」) 会出现在标题里
        val entries = CalendarPage.parse(loadGbkSample())
        val joined = entries.joinToString("|") { it.title }
        assertFalse("标题里不应出现 U+FFFD 替换字符", joined.contains('�'))
        // 抽几个常见的 mojibake 字符兜底
        assertFalse("标题里不应残留西里尔字母 'ѧ'", joined.contains('ѧ'))
        // 取一条标准词作为反向佐证
        assertTrue("应能正常出现「学年」二字", joined.contains("学年"))
    }

    private fun loadGbkSample(): String {
        val stream = javaClass.getResourceAsStream("/samples/calendar.html")
            ?: error("找不到测试资源 /samples/calendar.html")
        val bytes = stream.use { it.readBytes() }
        return String(bytes, GBK)
    }

    /** 防止误用：当 wnl 标识同时考察 perpetual 与 fileType。 */
    @Test
    fun `wnl_htm is both perpetual and HTM type`() {
        val entries = CalendarPage.parse(FIXTURE_FILETYPES)
        val wnl = entries.first { it.isPerpetual }
        assertEquals(CalendarFileType.HTM, wnl.fileType)
    }

    @Test
    fun `relative href is resolved to absolute when baseUri is provided`() {
        // CalendarRepository 实际会传 jwc 的索引页 URL 作为 base，让 abs:href 能把
        // 相对 href 解析为完整 https URL。否则 Intent.ACTION_VIEW + 无 scheme URI 会
        // 让 Activity resolution 失败，「No apps can perform this action」。
        val entries = CalendarPage.parse(
            FIXTURE_RELATIVE,
            "https://jwc.jxnu.edu.cn/Jxzl_Index.htm",
        )
        assertEquals(1, entries.size)
        assertEquals("https://jwc.jxnu.edu.cn/Jxzl_20260901.pdf", entries.single().url)
    }

    @Test
    fun `caters for missing class attribute`() {
        // 罕见场景：jwc 某天去掉了 class —— 不应让解析失败
        val entries = CalendarPage.parse(FIXTURE_NO_CLASS)
        assertEquals(1, entries.size)
        assertFalse("缺 class 视作非调整版", entries.single().isCorrection)
    }

    @Test
    fun `target attribute is ignored`() {
        // 解析器不读 target，所以 _blank 不影响结果
        val entries = CalendarPage.parse(FIXTURE_BLANK_TARGET)
        assertEquals(1, entries.size)
        assertEquals("标题", entries.single().title)
    }

    private companion object {
        val FIXTURE_FILETYPES = """
            <html><body>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/wnl.htm" class="button bg-red">万年历</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_a.pdf" class="button bg-red">PDF 条目</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_b.doc" class="button bg-red">DOC 条目</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_c.xls" class="button bg-red">XLS 条目</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_d.jpg" class="button bg-red">JPG 条目</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val FIXTURE_NOISE = """
            <html><body>
            <a href="https://example.com/foo.pdf" class="ext-noise">不在 list-group 里的链接</a>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_20260901.pdf" class="button bg-red">2026-2027学年第一学期</a></li>
              <li><a href="chrome-extension://deepl/whatever" class="dl-fake">DeepL 注入噪声</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/some-unrelated-page.aspx" class="button bg-red">无 Jxzl 前缀</a></li>
            </ul>
            <a href="https://jwc.jxnu.edu.cn/Jxzl_zzz.pdf">list 外的链接也不算</a>
            </body></html>
        """.trimIndent()

        val FIXTURE_EMPTY = """
            <html><body>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_a.pdf" class="button bg-red">   </a></li>
              <li><a class="button bg-red">Jxzl 无 href</a></li>
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_ok.pdf" class="button bg-red">唯一合法条目</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val FIXTURE_UNKNOWN_TYPE = """
            <html><body>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_weird.zip" class="button bg-red">奇怪格式</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val FIXTURE_RELATIVE = """
            <html><body>
            <ul class="list-group">
              <li><a href="Jxzl_20260901.pdf" class="button bg-red">2026-2027学年第一学期</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val FIXTURE_NO_CLASS = """
            <html><body>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_x.pdf">无 class 标签</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val FIXTURE_BLANK_TARGET = """
            <html><body>
            <ul class="list-group">
              <li><a href="https://jwc.jxnu.edu.cn/Jxzl_x.pdf" target="_blank" class="button bg-red">标题</a></li>
            </ul>
            </body></html>
        """.trimIndent()
    }
}
