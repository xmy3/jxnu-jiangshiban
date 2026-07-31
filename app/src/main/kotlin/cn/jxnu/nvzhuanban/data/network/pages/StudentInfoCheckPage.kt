package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.StudentBasicInfo
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 本人学籍/身份信息校对表解析器：`/MyControl/Student_InforCheck.aspx`。
 *
 * 页面两张表，字段以 span id 承载（真实页面 id 无 `_ctl` 前缀，仍按后缀匹配容错未来变更）：
 *   lblXH 学号 / lblXM 姓名 / lblBJ 班级 / lblKSH 考生号 / lblXB 性别 /
 *   lblMZ 民族 / lblCSRQ 出生日期(yyyyMMdd)
 * 页面还有 lblSFZH 身份证号，属强 PII，刻意不解析（不落内存不展示）。
 * 相片走 `imgXP`（All_PhotoShow.aspx），与 UserProfile.avatarUrl 同端点，这里不解析。
 */
object StudentInfoCheckPage {

    fun parse(html: String): StudentBasicInfo {
        val doc = Jsoup.parse(html)
        return StudentBasicInfo(
            studentId = spanText(doc, "lblXH"),
            name = spanText(doc, "lblXM"),
            className = spanText(doc, "lblBJ"),
            examId = spanText(doc, "lblKSH"),
            gender = spanText(doc, "lblXB"),
            ethnicity = spanText(doc, "lblMZ"),
            birthDate = formatBirthDate(spanText(doc, "lblCSRQ")),
        )
    }

    /** "20070323" -> "2007-03-23"；非 8 位纯数字则原样返回。 */
    private fun formatBirthDate(raw: String): String =
        if (raw.length == 8 && raw.all { it.isDigit() }) {
            "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
        } else raw

    // 后缀匹配天然区分 lblXM 与 lblXM2（"lblXM2" 不以 "lblXM" 结尾），无需额外处理。
    private fun spanText(doc: Document, idSuffix: String): String =
        doc.selectFirst("span[id$=$idSuffix]")?.text()?.trim().orEmpty()
}
