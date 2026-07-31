package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.TeacherInfo
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 教工基本信息页解析器：`/MyControl/All_Display.aspx?UserControl=All_TeacherInfor.ascx&UserType=Teacher&UserNum=<base64>`
 *
 * 页面结构很简单，关键信息全在 5 个 span 里（id 形如 `_ctl6_lbl*`）：
 *   - `_ctl6_lblName` / `_ctl6_lblSex` / `_ctl6_lblEmail` / `_ctl6_lblZC`（职称） / `_ctl6_lblJJ`（教学简介）
 *
 * 学校并不在这页给出单位/教号，那两项由检索列表自带。
 */
object TeacherDetailPage {

    fun parse(html: String): TeacherInfo {
        val doc = Jsoup.parse(html)
        return TeacherInfo(
            name = spanText(doc, "_lblName"),
            gender = spanText(doc, "_lblSex"),
            email = spanText(doc, "_lblEmail"),
            title = spanText(doc, "_lblZC"),
            intro = spanText(doc, "_lblJJ"),
        )
    }

    private fun spanText(doc: Document, idSuffix: String): String =
        doc.selectFirst("span[id$=$idSuffix]")?.text()?.trim().orEmpty()
}
