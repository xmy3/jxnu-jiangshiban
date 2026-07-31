package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 图文新闻列表解析回归测试。
 *
 * 关键覆盖：
 *   - href 上 `ID=数字` / `id=数字` 大小写都接
 *   - title 开头 `【YYYY-MM-DD】` 抽取并从标题里去除
 *   - img.src 抽到 Announcement.thumbnailUrl（绝对 + 相对路径都覆盖）
 *   - 缺日期 / 无 id / 无图片的条目处理
 *   - type 统一为 PICTURE_NEWS
 */
class PictureNewsPageTest {

    @Test
    fun `extracts id title date and thumbnail with absolute urls`() {
        val list = PictureNewsPage.parse(FIXTURE_ABS)
        assertEquals(2, list.size)

        val first = list.first { it.id == "13479" }
        assertEquals("新闻与传播学院召开2022级教育技术学专业实习总结大会暨研习动员大会", first.title)
        assertEquals(LocalDate.of(2026, 3, 9), first.date)
        assertEquals(AnnouncementType.PICTURE_NEWS, first.type)
        assertEquals(
            "https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202512311732282.png",
            first.thumbnailUrl,
        )

        val second = list.first { it.id == "13494" }
        assertEquals("新闻与传播学院召开基层教学组织负责人年度述职会议", second.title)
        assertNotNull(second.thumbnailUrl)
    }

    @Test
    fun `lower-case id query param works`() {
        // ASP.NET 路由大小写不敏感；解析器也允许 `id=` 出现在 picture news 列表里
        val list = PictureNewsPage.parse(FIXTURE_LOWERCASE_ID)
        assertEquals(1, list.size)
        assertEquals("13900", list[0].id)
    }

    @Test
    fun `resolves relative thumbnail url against baseUrl`() {
        // 浏览器另存样本会把 img src 改写为 ./picture_news_files/xxx.png 这种相对路径。
        // 给 baseUrl 后应当能解析为绝对路径（即便指向的是不存在的本地路径，逻辑上是 host-relative）。
        val list = PictureNewsPage.parse(
            FIXTURE_RELATIVE,
            baseUrl = "https://jwc.jxnu.edu.cn/Portal/ArticlesPictureNews.aspx?page=1",
        )
        assertEquals(1, list.size)
        val thumb = list[0].thumbnailUrl
        assertNotNull(thumb)
        assertTrue("应解析为绝对 URL", thumb!!.startsWith("https://"))
    }

    @Test
    fun `skips entries without id or date`() {
        val list = PictureNewsPage.parse(FIXTURE_BAD)
        // 4 条里只有 1 条同时满足 id + 日期 + 标题非空
        assertEquals(1, list.size)
        assertEquals("13700", list[0].id)
    }

    @Test
    fun `keeps null thumbnail when img tag missing`() {
        val list = PictureNewsPage.parse(FIXTURE_NO_IMAGE)
        assertEquals(1, list.size)
        assertNull(list[0].thumbnailUrl)
    }

    @Test
    fun `all entries carry PICTURE_NEWS type`() {
        val list = PictureNewsPage.parse(FIXTURE_ABS)
        assertTrue(list.all { it.type == AnnouncementType.PICTURE_NEWS })
    }

    private companion object {
        // 线上 HTML 的真实形状：href + img src 都是绝对 URL
        val FIXTURE_ABS = """
            <html><body>
            <div class="border border-red radius padding-small float-left margin-small text-center" style="height: 220px;">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13479" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202512311732282.png" border="0" width="240px" height="180px">
                <div class="line text-small" style="height: 30px; overflow: hidden; width: 240px;">
                    【2026-03-09】
                    新闻与传播学院召开2022级教育技术学专业实习总结大会暨研习动员大会
                </div>
                </a>
            </div>
            <div class="border border-red radius padding-small float-left margin-small text-center" style="height: 220px;">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13494" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202601191447282.jpg" border="0" width="240px" height="180px">
                <div class="line text-small" style="height: 30px; overflow: hidden; width: 240px;">
                    【2026-03-09】
                    新闻与传播学院召开基层教学组织负责人年度述职会议
                </div>
                </a>
            </div>
            </body></html>
        """.trimIndent()

        val FIXTURE_LOWERCASE_ID = """
            <html><body>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=13900" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/foo.png">
                <div class="line text-small">【2026-05-01】小写 id 也能解析</div>
                </a>
            </div>
            </body></html>
        """.trimIndent()

        val FIXTURE_RELATIVE = """
            <html><body>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13479" target="_blank">
                <img src="./picture_news_files/202512311732282.png">
                <div class="line text-small">【2026-03-09】 浏览器另存样本</div>
                </a>
            </div>
            </body></html>
        """.trimIndent()

        val FIXTURE_BAD = """
            <html><body>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13700" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/ok.png">
                <div class="line text-small">【2026-03-01】合法条目</div>
                </a>
            </div>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13701" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/x.png">
                <div class="line text-small">缺日期标题</div>
                </a>
            </div>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/y.png">
                <div class="line text-small">【2026-02-01】无 id</div>
                </a>
            </div>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13703" target="_blank">
                <img src="https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/z.png">
                <div class="line text-small">【bad-date】坏日期格式</div>
                </a>
            </div>
            </body></html>
        """.trimIndent()

        val FIXTURE_NO_IMAGE = """
            <html><body>
            <div class="border border-red radius padding-small float-left margin-small text-center">
                <a href="https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?ID=13800" target="_blank">
                <div class="line text-small">【2026-04-01】没有 img 标签的极端样本</div>
                </a>
            </div>
            </body></html>
        """.trimIndent()
    }
}
