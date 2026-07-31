package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 教务通知/通告列表解析回归测试。
 *
 * 关键覆盖：
 *   - href 上 `id=数字` 抽取
 *   - title 末尾 `【YYYY-MM-DD】` 抽取并从标题里去除
 *   - 缺日期 / 无 id / class 不全的条目应被跳过
 *   - AnnouncementType 透传不被解析器修改
 */
class AnnouncementPageTest {

    @Test
    fun `extracts id title and date from anchor`() {
        val list = AnnouncementPage.parse(FIXTURE, AnnouncementType.NOTIFICATION)
        assertEquals(2, list.size)

        val first = list.first { it.id == "13616" }
        // title 应当不含末尾的 【...】 日期段
        assertTrue("title 不应留下日期尾", !first.title.endsWith("】"))
        assertEquals("【教务字〔2026〕66号】 关于申报校级课程思政研究专项课题的通知", first.title)
        assertEquals(LocalDate.of(2026, 5, 13), first.date)
        assertEquals(AnnouncementType.NOTIFICATION, first.type)

        val second = list.first { it.id == "13620" }
        assertEquals("关于五一假期校园安全的通告", second.title)
    }

    @Test
    fun `passes through announcement type unchanged`() {
        val list = AnnouncementPage.parse(FIXTURE, AnnouncementType.BULLETIN)
        assertTrue(list.all { it.type == AnnouncementType.BULLETIN })
    }

    @Test
    fun `parses showcase rows in real jwc shape`() {
        // 教务风采（type=Jxfc）实测行标记：href 用单引号、target 无引号，与通知同模板
        val list = AnnouncementPage.parse(FIXTURE_SHOWCASE, AnnouncementType.SHOWCASE)
        assertEquals(2, list.size)
        assertEquals(AnnouncementType.SHOWCASE, list[0].type)
        assertEquals("13691", list[0].id)
        assertEquals("某学院教师斩获全国教学展示二等奖", list[0].title)
        assertEquals(LocalDate.of(2026, 7, 6), list[0].date)
    }

    @Test
    fun `skips entries without date or id`() {
        val list = AnnouncementPage.parse(FIXTURE_BAD, AnnouncementType.NOTIFICATION)
        // 4 条里只有 1 条同时满足 id + 日期 + 标题非空
        assertEquals(1, list.size)
        assertEquals("13700", list[0].id)
    }

    private companion object {
        val FIXTURE = """
            <html><body>
            <a href="ArticlesView.aspx?id=13616" target="_blank">
              <div class="line border-bottom  margin-bottom text-big padding">
                【教务字〔2026〕66号】 关于申报校级课程思政研究专项课题的通知 【2026-05-13】
              </div>
            </a>
            <a href="ArticlesView.aspx?id=13620" target="_blank">
              <div class="line border-bottom  margin-bottom text-big padding">
                关于五一假期校园安全的通告 【2026-04-29】
              </div>
            </a>
            </body></html>
        """.trimIndent()

        val FIXTURE_SHOWCASE = """
            <html><body>
            <a href='ArticlesView.aspx?id=13691' target=_blank>
              <div class="line border-bottom  margin-bottom text-big padding">
                 某学院教师斩获全国教学展示二等奖 【2026-07-06】
              </div>
            </a>
            <a href='ArticlesView.aspx?id=13685' target=_blank>
              <div class="line border-bottom  margin-bottom text-big padding">
                 某学院举办本科生研究思维培养专题讲座 【2026-07-02】
              </div>
            </a>
            </body></html>
        """.trimIndent()

        val FIXTURE_BAD = """
            <html><body>
            <a href="ArticlesView.aspx?id=13700" target="_blank">
              <div class="line border-bottom text-big">合法条目 【2026-03-01】</div>
            </a>
            <a href="ArticlesView.aspx?id=13701" target="_blank">
              <div class="line border-bottom text-big">缺日期标题</div>
            </a>
            <a href="ArticlesView.aspx" target="_blank">
              <div class="line border-bottom text-big">无 id 【2026-02-01】</div>
            </a>
            <a href="ArticlesView.aspx?id=13703" target="_blank">
              <div class="other-class">class 不全 【2026-01-01】</div>
            </a>
            </body></html>
        """.trimIndent()
    }
}
