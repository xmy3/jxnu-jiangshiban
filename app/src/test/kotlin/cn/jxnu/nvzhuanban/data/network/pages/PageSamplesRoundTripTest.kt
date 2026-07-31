package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实教务网 HTML 回归测试。
 *
 * fixture 不是手写最小 HTML，而是 `samples/` 目录下从真实账号抓的实页（已脱敏到学号 `2024050001`）。
 * 目的：手写 fixture 无法覆盖 ASP.NET 模板特有的怪招（嵌套 table、`&nbsp;` 列填充、
 * font 标签包裹、`_ctl0`/`_ctl1`/`_ctl6` 这套占位前缀编号、HTML4 `bgcolor` 等）。
 * 当教务网升级模板而我们没察觉时，这个测试是第一道哨兵。
 *
 * 资源文件通过 `app/src/test/resources/samples/` 下的 html 加载，确保任何 IDE/Gradle 双击运行都能找到。
 */
class PageSamplesRoundTripTest {

    @Test
    fun `parses schedule sample without throwing and yields semesters + courses + ASP_NET tokens`() {
        val html = loadResource("/samples/schedule.html")
        val parsed = SchedulePage.parse(html)

        // ASP.NET 三件套必须齐
        assertTrue("__VIEWSTATE 非空", parsed.viewState.isNotEmpty())
        assertTrue("__VIEWSTATEGENERATOR 非空", parsed.viewStateGenerator.isNotEmpty())
        assertTrue("__EVENTVALIDATION 非空", parsed.eventValidation.isNotEmpty())

        // 样本里 selected 的是 "25-26第2学期"
        assertEquals("2026/3/1 0:00:00", parsed.serverSelectedValue)
        assertEquals("25-26第2学期", parsed.semester)

        // 真实样本是该学生的全部历史学期，不固定 3 个；只要求非空且包含 selected 项
        assertTrue("学期下拉非空", parsed.semesters.isNotEmpty())
        assertTrue(
            "学期下拉应包含 selected 项",
            parsed.semesters.any { it.label == "25-26第2学期" },
        )
        assertNotNull("当前学期 startDate 应能解析", parsed.semesterStart)

        // 视觉网格里至少应解析出几门课（合并后）
        assertTrue("课程数 > 0", parsed.courses.isNotEmpty())

        // 表头不应被当成课程
        val noisy = parsed.courses.firstOrNull { it.name == "星期一" || it.name == "节" }
        assertEquals(null, noisy)
    }

    @Test
    fun `parses grades sample and extracts student meta + non-empty semesters`() {
        val html = loadResource("/samples/grades.html")
        val parsed = GradePage.parse(html)

        assertEquals("2024050001", parsed.meta.studentId)
        assertEquals("张三", parsed.meta.name)
        assertEquals("人工智能学院", parsed.meta.college)
        assertEquals("计算机科学与技术（师范）", parsed.meta.major)
        assertTrue("累计学分应为正数", (parsed.meta.totalCredit ?: 0f) > 0f)
        assertTrue("至少一个学期", parsed.semesters.isNotEmpty())
        assertTrue("第一个学期至少一门课", parsed.semesters.first().grades.isNotEmpty())
    }

    @Test
    fun `parses exams sample without throwing`() {
        val html = loadResource("/samples/exams.html")
        val exams = ExamPage.parse(html)

        // 样本是真实考试安排，长度只要解析过即可（>= 0 永远成立，但要确保没炸）
        // 真实样本含若干行：若解析为空说明 _dgContent 选择器或时间格式有问题
        assertTrue("考试至少一条（如本学期无考试请更换 sample）", exams.isNotEmpty())
        for (exam in exams) {
            assertTrue("课程名非空", exam.courseName.isNotBlank())
            assertTrue("课程号非空", exam.courseCode.isNotBlank())
        }
    }

    @Test
    fun `parses test_grades sample and finds at least one group`() {
        val html = loadResource("/samples/test_grades.html")
        val report = TestGradePage.parse(html)

        assertTrue("报告标题非空", report.pageTitle.isNotBlank())
        // 至少一组（主专业 / 双专业 之一）
        assertTrue("至少一个考试组", report.groups.isNotEmpty())
    }

    private fun loadResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: error("找不到测试资源: $path —— 确认 app/src/test/resources$path 已存在")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
