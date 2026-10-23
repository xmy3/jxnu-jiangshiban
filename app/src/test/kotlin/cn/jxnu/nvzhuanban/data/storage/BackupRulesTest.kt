package cn.jxnu.nvzhuanban.data.storage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    @Test
    fun `personal alert data is excluded from every backup transport`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val legacy = builder.parse(File(main, "res/xml/backup_rules.xml"))
        val modern = builder.parse(File(main, "res/xml/data_extraction_rules.xml"))
        val sections = listOf(
            legacy.documentElement,
            modern.getElementsByTagName("cloud-backup").item(0) as Element,
            modern.getElementsByTagName("device-transfer").item(0) as Element,
        )
        for (section in sections) {
            val excludes = section.getElementsByTagName("exclude")
            for (filename in listOf("alert_prefs.xml", "exam_reminders.xml")) {
                assertTrue("${section.tagName} must exclude $filename", (0 until excludes.length).any {
                    val entry = excludes.item(it) as Element
                    entry.getAttribute("domain") == "sharedpref" && entry.getAttribute("path") == filename
                })
            }
        }
    }
}
