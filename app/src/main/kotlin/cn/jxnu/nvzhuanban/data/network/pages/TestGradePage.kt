package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.TestGrade
import cn.jxnu.nvzhuanban.data.model.TestGradeGroup
import cn.jxnu.nvzhuanban.data.model.TestGradeReport
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 考试出分页解析器：`/MyControl/All_Display.aspx?UserControl=xfz_Test_cj.ascx`
 *
 * 页面结构（参考 `samples/test_grades.html`）：
 *
 *   <div class="text-large">25-26第2学期期末成绩查询</div>
 *   <blockquote class="border-red">
 *     <p>学号：<u>...</u> 姓名：<u>...</u> 考试学期：<u>YYYY-MM-DD</u></p>
 *     <hr>
 *     <p class="text-sub">注：因部分老师未提交成绩……</p>
 *   </blockquote>
 *   <fieldset><legend>主专业课程成绩</legend>
 *     <table id="_ctl6_gvZZY">
 *       <tr> 序号 | 课程号 | 课程名称标识 | 算法名称 | 平时 | 期中 | 实践 | 卷面 | 总评 | 备注 | 考试情况 </tr>
 *       <tr> ... 数据行（11 列） ... </tr>
 *     </table>
 *   </fieldset>
 *   <fieldset><legend>双专业课程成绩</legend>
 *     <table id="_ctl6_gvSZY">
 *       <tr><td colspan="10">没有记录</td></tr>   // 空表占位
 *     </table>
 *   </fieldset>
 *
 * 空单元格统一是 `&nbsp;`（Jsoup `text()` 之后是不间断空格 ` `），解析时归一为 null。
 */
object TestGradePage {

    fun parse(html: String): TestGradeReport {
        val doc = Jsoup.parse(html)
        val pageTitle = doc.selectFirst("div.text-large")?.text()?.trim().orEmpty()
        val blockquote = doc.selectFirst("blockquote.border-red")
        val studentId = blockquote?.let { pick(it.text(), "学号") }
        val studentName = blockquote?.let { pick(it.text(), "姓名") }
        val semesterDate = blockquote?.let { pick(it.text(), "考试学期") }
        val disclaimer = blockquote?.selectFirst("p.text-sub")?.text()
            ?.removePrefix("注：")?.removePrefix("注:")?.trim()

        val groups = doc.select("fieldset").mapNotNull { parseGroup(it) }

        return TestGradeReport(
            pageTitle = pageTitle,
            studentId = studentId,
            studentName = studentName,
            semesterDate = semesterDate,
            disclaimer = disclaimer,
            groups = groups,
        )
    }

    private fun parseGroup(fieldset: Element): TestGradeGroup? {
        val title = fieldset.selectFirst("legend")?.text()?.trim().orEmpty()
        val table = fieldset.selectFirst("table") ?: return null
        val grades = parseGrades(table)
        if (grades.isEmpty()) return null
        return TestGradeGroup(title = title, grades = grades)
    }

    private fun parseGrades(table: Element): List<TestGrade> {
        val rows = table.select("> tbody > tr, > tr")
        val results = mutableListOf<TestGrade>()
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            // 表头行只有 th 没有 td；占位行 `<td colspan="10">没有记录</td>` 只有 1 列且非数字。
            if (cells.size < 11) continue
            val seq = cleanText(cells[0]).toIntOrNull() ?: continue
            val code = cleanText(cells[1])
            val name = cleanText(cells[2])
            if (code.isBlank() || name.isBlank()) continue
            results += TestGrade(
                id = "$seq-$code",
                sequenceNo = seq,
                courseCode = code,
                courseName = name,
                algorithmName = nullIfBlank(cells[3]),
                regularScore = nullIfBlank(cells[4]),
                midtermScore = nullIfBlank(cells[5]),
                practiceScore = nullIfBlank(cells[6]),
                finalExamScore = nullIfBlank(cells[7]),
                totalScore = nullIfBlank(cells[8]),
                remark = nullIfBlank(cells[9]),
                examStatus = nullIfBlank(cells[10]),
            )
        }
        return results
    }

    /** 用 `&nbsp;` 占位的空单元格在 `text()` 之后是 ` `，先归一为普通空格再判空。 */
    private fun cleanText(el: Element): String =
        el.text().replace(' ', ' ').trim()

    private fun nullIfBlank(el: Element): String? =
        cleanText(el).takeIf { it.isNotBlank() }

    private fun pick(text: String, key: String): String? {
        val normalized = text.replace(' ', ' ')
        // 学号 / 姓名 / 考试学期 用全角或半角冒号都见过，正则两个都吃
        return Regex("$key\\s*[：:]\\s*([^\\s　]+)").find(normalized)
            ?.groupValues?.getOrNull(1)
    }
}
