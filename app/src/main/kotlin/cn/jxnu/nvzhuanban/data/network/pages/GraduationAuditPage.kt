package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.TrainingPlan
import cn.jxnu.nvzhuanban.data.model.TrainingPlanCourse
import cn.jxnu.nvzhuanban.data.model.TrainingPlanCreditSummary
import cn.jxnu.nvzhuanban.data.model.TrainingPlanSection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object GraduationAuditPage {

    data class Parsed(
        val minimumCredits: Float? = null,
        val trainingPlan: TrainingPlan = TrainingPlan(minimumCredits = minimumCredits),
    )

    private val labelRegex = Regex(
        """(?:毕业最低学分|最低毕业学分|毕业要求学分|毕业所需学分|毕业应修学分|应修总学分|应修学分|要求学分|最低学分)\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)"""
    )

    fun parse(html: String): Parsed {
        val doc = Jsoup.parse(html)
        val plan = parseTrainingPlan(doc)
        return Parsed(
            minimumCredits = plan.minimumCredits,
            trainingPlan = plan,
        )
    }

    private fun parseTrainingPlan(doc: Document): TrainingPlan {
        val infoText = doc.selectFirst("[id$=_lblInfor]")?.text().orEmpty().normalizeText()
        val minimumCredits = parseMinimumCredits(doc)
        return TrainingPlan(
            minimumCredits = minimumCredits,
            currentCredits = Regex("""当前所修学分总数\s*[:：]\s*([0-9]+(?:\.[0-9]+)?)""")
                .find(infoText)?.groupValues?.getOrNull(1)?.toFloatOrNull(),
            overallStandardScore = Regex("""加权平均标准分\s*[:：]\s*([-+]?[0-9]+(?:\.[0-9]+)?)""")
                .find(infoText)?.groupValues?.getOrNull(1)?.toFloatOrNull(),
            degreeCourseTotal = Regex("""专业学位课程总数为\s*[:：]\s*([0-9]+)\s*门""")
                .find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            retakenDegreeCourseCount = Regex("""重修学位课程门次为\s*[:：]\s*([0-9]+)\s*门""")
                .find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            creditSummaries = parseCreditSummaries(doc),
            sections = parseSections(doc),
        )
    }

    private fun parseCreditSummaries(doc: Document): List<TrainingPlanCreditSummary> =
        doc.select("[id$=_lblInfor] div.button").mapNotNull { div ->
            val text = div.text().normalizeText()
            val match = Regex("""(.+?)\s*[:：]\s*([0-9]+(?:\.[0-9]+)?)""").find(text) ?: return@mapNotNull null
            TrainingPlanCreditSummary(
                name = match.groupValues[1].trim(),
                earnedCredits = match.groupValues[2].toFloatOrNull() ?: return@mapNotNull null,
                required = div.classNames().contains("bg-red"),
            )
        }

    private fun parseSections(doc: Document): List<TrainingPlanSection> =
        doc.select("fieldset").mapNotNull { fieldset ->
            val title = fieldset.selectFirst("> legend")?.text()?.normalizeText()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val courses = parseCourses(fieldset)
            if (courses.isEmpty()) return@mapNotNull null
            TrainingPlanSection(
                title = title,
                requirement = fieldset.ownText().normalizeText().takeIf { it.isNotBlank() },
                courses = courses,
            )
        }

    private fun parseCourses(fieldset: Element): List<TrainingPlanCourse> {
        val header = fieldset.selectFirst("table tr") ?: return emptyList()
        val headers = header.children().filter { it.tagName() == "td" || it.tagName() == "th" }
            .map { it.text().normalizeText() }
        val rows = fieldset.select("table tr").drop(1)
        return rows.mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) return@mapNotNull null
            val values = cells.map { it.text().normalizeText() }
            if (headers.any { it.contains("课程性质") }) parseOptionalCourse(values) else parseRegularCourse(values)
        }
    }

    private fun parseRegularCourse(values: List<String>): TrainingPlanCourse? {
        if (values.size < 9) return null
        val code = values[0].trim()
        val name = values[1].trim()
        if (code.isBlank() || name.isBlank()) return null
        return TrainingPlanCourse(
            courseCode = code,
            courseName = name,
            openingOrder = values[2].takeIf { it.isNotBlank() },
            isDegreeCourse = values[3].contains("是"),
            credit = values[4].toFloatOrNull(),
            examScore = values[5].takeIf { it.isNotBlank() },
            makeupScore = values[6].takeIf { it.isNotBlank() },
            examTime = values[7].takeIf { it.isNotBlank() },
            remark = values[8].takeIf { it.isNotBlank() },
        )
    }

    private fun parseOptionalCourse(values: List<String>): TrainingPlanCourse? {
        if (values.size < 8) return null
        val code = values[1].trim()
        val name = values[2].trim()
        if (code.isBlank() || name.isBlank()) return null
        return TrainingPlanCourse(
            category = values[0].takeIf { it.isNotBlank() },
            courseCode = code,
            courseName = name,
            credit = values[3].toFloatOrNull(),
            examScore = values[4].takeIf { it.isNotBlank() },
            makeupScore = values[5].takeIf { it.isNotBlank() },
            examTime = values[6].takeIf { it.isNotBlank() },
            remark = values[7].takeIf { it.isNotBlank() },
        )
    }

    private fun parseMinimumCredits(doc: Document): Float? {
        doc.selectFirst("[id$=_lblInfor]")?.text()
            ?.normalizeText()
            ?.let { labelRegex.find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull() }
            ?.let { return it }
        findLabelValueInTables(doc)?.let { return it }
        val text = doc.body()?.text().orEmpty().normalizeText()
        return labelRegex.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    private fun findLabelValueInTables(doc: Document): Float? {
        val labelKeys = listOf(
            "毕业最低学分",
            "最低毕业学分",
            "毕业要求学分",
            "毕业所需学分",
            "毕业应修学分",
            "应修总学分",
            "应修学分",
            "要求学分",
            "最低学分",
        )
        for (row in doc.select("tr")) {
            val cells = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
                .map { it.text().replace('\u00A0', ' ').replace('　', ' ').trim() }
            if (cells.isEmpty()) continue
            for ((index, cell) in cells.withIndex()) {
                if (labelKeys.none { cell.contains(it) }) continue
                cells.drop(index + 1).firstNotNullOfOrNull { it.extractCreditNumber() }?.let { return it }
                cell.extractCreditNumberAfterLabel()?.let { return it }
            }
        }
        return null
    }

    private fun String.extractCreditNumberAfterLabel(): Float? {
        val afterSeparator = substringAfter('：', missingDelimiterValue = "")
            .ifBlank { substringAfter(':', missingDelimiterValue = "") }
        return afterSeparator.extractCreditNumber()
    }

    private fun String.extractCreditNumber(): Float? =
        Regex("""([0-9]+(?:\.[0-9]+)?)""").find(this)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    private fun String.normalizeText(): String = replace('\u00A0', ' ')
        .replace('　', ' ')
        .replace(Regex("\\s+"), " ")
}
