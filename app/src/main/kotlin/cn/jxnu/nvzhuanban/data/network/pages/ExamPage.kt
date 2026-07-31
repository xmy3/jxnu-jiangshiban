package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.Exam
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 考试安排页解析器：`/User/default.aspx?code=129&uctl=MyControl\xfz_test_schedule.ascx`
 *
 * 数据表 `<table id="_ctl1_dgContent">` 列顺序固定为：
 *
 *   课程号 | 课程名称标识 | 学号 | 考试时间 | 教室号 | 座位号 | 备注
 *
 * 考试时间格式：`yyyy-MM-dd HH:mm:ss`。教务系统不提供考试时长，统一按 [Exam] 默认 120 分钟。
 */
object ExamPage {

    // 显式 Locale.ROOT 避免阿拉伯/孟加拉等数字本地化导致解析失败 —— 服务端永远发 ASCII。
    private val DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun parse(html: String): List<Exam> {
        val doc = Jsoup.parse(html)
        val rows = doc.select("table[id$=_dgContent] > tbody > tr, table[id$=_dgContent] > tr")
        val results = mutableListOf<Exam>()
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size != 7) continue
            if (cells[0].text().trim() == "课程号") continue  // 表头

            val code = cleanText(cells[0])
            val name = cleanText(cells[1])
            // cells[2] 是学号，每行都一样，忽略
            val rawTime = cleanText(cells[3])
            val time = parseTime(rawTime) ?: continue
            val location = cleanText(cells[4])
            val seat = cleanText(cells[5]).takeIf { it.isNotBlank() }
            val remark = cleanText(cells[6]).takeIf { it.isNotBlank() }

            results += Exam(
                // 用清洗后的时间字符串拼 id，避免 &nbsp; 等空白字符让"同一行"在不同次刷新里 id 不一致
                id = "$code-$rawTime",
                courseName = name,
                courseCode = code,
                startTime = time,
                location = location,
                seat = seat,
                remark = remark,
            )
        }
        return results
    }

    private fun parseTime(s: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s, DT_FMT) }.getOrNull()

    private fun cleanText(el: org.jsoup.nodes.Element): String =
        el.text().replace(' ', ' ').trim()
}
