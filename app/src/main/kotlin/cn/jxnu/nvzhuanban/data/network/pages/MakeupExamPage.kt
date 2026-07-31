package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.MakeupExam
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 补缓考考试安排页解析器：`/MyControl/All_Display.aspx?UserControl=xfz_Test_BHK.ascx`
 *
 * 数据表 `<table id="_ctl6_dgContent">` 列顺序固定 13 列：
 *
 *   教学区名称 | 学院 | 班级 | 姓名 | 学号 | 课程号 | 课程名称标识 |
 *   课程类型 | 课程管理单位 | 教室号 | 考试时间 | 考试方式 | 备注
 *
 * 教务网把真正的考试时间/地点塞进「考试方式」自然语言段，"教室号"和"考试时间"两列普遍是
 * `&nbsp;`；解析时统一归一为 null。
 */
object MakeupExamPage {

    fun parse(html: String): List<MakeupExam> {
        val doc = Jsoup.parse(html)
        val rows = doc.select("table[id$=_dgContent] > tbody > tr, table[id$=_dgContent] > tr")
        val results = mutableListOf<MakeupExam>()
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size != 13) continue
            if (cells[0].text().trim() == "教学区名称") continue  // 表头

            val campus = cleanText(cells[0])
            val college = cleanText(cells[1])
            val className = cleanText(cells[2])
            val studentName = cleanText(cells[3])
            val studentId = cleanText(cells[4])
            val courseCode = cleanText(cells[5])
            val courseName = cleanText(cells[6])
            if (courseCode.isBlank() || courseName.isBlank()) continue
            val courseType = cleanText(cells[7])
            val managingDept = cleanText(cells[8])
            val location = nullIfBlank(cells[9])
            val examTime = nullIfBlank(cells[10])
            val examMethod = nullIfBlank(cells[11])
            val remark = nullIfBlank(cells[12])

            results += MakeupExam(
                // 同一学生在同一门课上"补考"和"缓考"理论上可能同时存在，把 remark 也拼进 id 防撞
                id = "$courseCode-${remark.orEmpty()}-${examTime.orEmpty()}",
                campus = campus,
                college = college,
                className = className,
                studentName = studentName,
                studentId = studentId,
                courseCode = courseCode,
                courseName = courseName,
                courseType = courseType,
                managingDept = managingDept,
                location = location,
                examTime = examTime,
                examMethod = examMethod,
                remark = remark,
            )
        }
        return results
    }

    /** `&nbsp;` 在 `text()` 后是不间断空格 ` `；先归一为普通空格再判空。 */
    private fun cleanText(el: Element): String =
        el.text().replace(' ', ' ').trim()

    private fun nullIfBlank(el: Element): String? =
        cleanText(el).takeIf { it.isNotBlank() }
}
