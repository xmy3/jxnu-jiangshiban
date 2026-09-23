package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanSearchPageTest {
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
