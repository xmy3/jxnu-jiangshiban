package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.StudentInfo
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 学生基本信息页解析器：`/MyControl/All_Display.aspx?UserControl=All_StudentInfor.ascx&UserType=Student&UserNum=<base64>`
 *
 * 页面只有 4 个有用 span（id 形如 `_ctl6_lbl*`）：
 *   - `_ctl6_lblBJ`（班级名称）/ `_ctl6_lblXH`（学号） / `_ctl6_lblXM`（姓名）/ `_ctl6_lblXB`（性别）
 *
 * 院系 / 头像由检索列表 + [cn.jxnu.nvzhuanban.data.network.JxnuUrls.studentPhotoUrl] 提供，此处不解析。
 */
object StudentDetailPage {

    fun parse(html: String): StudentInfo {
        val doc = Jsoup.parse(html)
        return StudentInfo(
            name = spanText(doc, "_lblXM"),
            studentId = spanText(doc, "_lblXH"),
            className = spanText(doc, "_lblBJ"),
            gender = spanText(doc, "_lblXB"),
        )
    }

    private fun spanText(doc: Document, idSuffix: String): String =
        doc.selectFirst("span[id$=$idSuffix]")?.text()?.trim().orEmpty()
}
