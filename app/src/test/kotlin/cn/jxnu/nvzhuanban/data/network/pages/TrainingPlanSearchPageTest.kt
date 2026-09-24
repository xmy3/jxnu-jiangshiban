package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanSearchPageTest {
    @Test fun `parses actual training plan control names and td table headers`() {
        val html = """
            <form name="_ctl0" id="_ctl0" method="post">
              <input type="hidden" name="__VIEWSTATE" value="state" />
              <table><tr>
                <td>年级：</td><td><select name="_ctl1:Nianji" id="_ctl1_Nianji">
                  <option selected="selected" value="2026/9/1 0:00:00">2026年</option>
                  <option value="2025/9/1 0:00:00">2025年</option>
                </select></td>
                <td>专业：</td><td><div><select name="_ctl1:zhuanye" id="_ctl1_zhuanye">
                  <option value="130301  ">表演</option>
                  <option selected="selected" value="080605  ">计算机科学与技术（师范）</option>
                </select></div></td>
                <td><input type="submit" name="_ctl1:GoSearch" value="查询" id="_ctl1_GoSearch" /></td>
              </tr></table>
              <table><tr><td>课程性质</td><td>课程号</td><td>课程名称标识</td><td>学分</td></tr>
                <tr><td>公共必修</td><td>056001</td><td>大学体育Ⅰ</td><td>1</td></tr></table>
            </form>
        """.trimIndent()
        val parsed = TrainingPlanSearchPage.parse(html)
        assertEquals(listOf("年级", "专业"), parsed.filters.map { it.label })
        assertEquals("2026/9/1 0:00:00", parsed.filters[0].selectedValue)
        assertEquals("080605  ", parsed.filters[1].selectedValue)
        assertEquals("_ctl1:GoSearch", parsed.submitName)
        assertEquals("大学体育Ⅰ", parsed.tables.single().rows.single()[2])
    }

    @Test fun `discovers year and major when WebForms names are opaque`() {
        val html = """
            <form id="_ctl0">
              <select name="site_menu"><option value="home">首页</option></select>
              <h2>培养方案查询</h2>
              <table><tr>
                <td>年级：</td><td><select name="_ctl1:ddlA" id="_ctl1_ddlA" onchange="__doPostBack('_ctl1${'$'}ddlA','')">
                  <option value="2026" selected>2026年</option><option value="2025">2025年</option>
                </select></td>
                <td>专业：</td><td><select name="_ctl1:ddlB" id="_ctl1_ddlB"><option value="1">表演</option></select></td>
                <td><input type="submit" name="_ctl1:btnQ" value="查询" /></td>
              </tr></table>
            </form>
        """.trimIndent()
        val parsed = TrainingPlanSearchPage.parse(html)
        assertEquals(listOf("年级", "专业"), parsed.filters.map { it.label })
        assertEquals(listOf("_ctl1:ddlA", "_ctl1:ddlB"), parsed.filters.map { it.name })
        assertEquals("_ctl1:btnQ", parsed.submitName)
    }

    @Test fun `discovers filters and results from WebForms page`() {
        val html = """
            <form>
              <input type="hidden" name="__VIEWSTATE" value="state" />
              <label for="year">年级</label><select id="year" name="_ctl1:ddlYear" onchange="__doPostBack('_ctl1${'$'}ddlYear','')">
                <option value="2025">2025级</option><option selected value="2024">2024级</option>
              </select>
              <label for="college">学院</label><select id="college" name="_ctl1:ddlCollege"><option value="51000   ">数学学院</option></select>
              <label for="major">专业</label><select id="major" name="_ctl1:ddlMajor"><option value="01">数学与应用数学</option></select>
              <input type="submit" name="_ctl1:btnSearch" value="查询" />
              <table><tr><th>课程名称</th><th>学分</th></tr><tr><td>高等数学</td><td>4</td></tr></table>
            </form>
        """.trimIndent()
        val parsed = TrainingPlanSearchPage.parse(html)
        assertEquals(3, parsed.filters.size)
        assertEquals("2024", parsed.filters[0].selectedValue)
        assertTrue(parsed.filters[0].postsBack)
        assertEquals("51000   ", parsed.filters[1].options[0].value)
        assertEquals("_ctl1:btnSearch", parsed.submitName)
        assertEquals("高等数学", parsed.tables.single().rows.single()[0])
        assertEquals("state", parsed.hiddenFields["__VIEWSTATE"])
    }
}
