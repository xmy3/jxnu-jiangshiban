package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GraduationAuditPageTest {

    @Test
    fun `parses minimum credits from saved xueji sample`() {
        val html = sampleHtml("xueji.html")

        val parsed = GraduationAuditPage.parse(html)

        assertEquals(172f, parsed.minimumCredits!!, 0.001f)
    }

    @Test
    fun `parses training plan from saved xueji sample`() {
        val html = sampleHtml("xueji.html")

        val plan = GraduationAuditPage.parse(html).trainingPlan

        assertEquals(172f, plan.minimumCredits!!, 0.001f)
        assertEquals(29f, plan.currentCredits!!, 0.001f)
        assertEquals(0.46591036f, plan.overallStandardScore!!, 0.001f)
        assertEquals(6, plan.degreeCourseTotal)
        assertEquals(0, plan.retakenDegreeCourseCount)
        assertEquals(8, plan.creditSummaries.size)
        assertEquals("公共必修", plan.creditSummaries.first().name)
        assertEquals(13f, plan.creditSummaries.first().earnedCredits, 0.001f)
        assertEquals(8, plan.sections.size)
        assertEquals("公共必修", plan.sections.first().title)
        assertEquals(21, plan.sections.first().courses.size)
        val firstCourse = plan.sections.first().courses.first()
        assertEquals("056001", firstCourse.courseCode)
        assertEquals(1f, firstCourse.credit!!, 0.001f)
        assertEquals("41", firstCourse.examScore)
        assertEquals("60", firstCourse.makeupScore)
        assertEquals("2025/9/1 0:00:00", firstCourse.examTime)
        val degreeCourses = plan.sections.flatMap { it.courses }.filter { it.isDegreeCourse }
        assertEquals(6, degreeCourses.size)
        val optionalSection = plan.sections.last()
        assertEquals("主修：计算机科学与技术（师范类）", optionalSection.title)
        assertEquals("在下列课程中至少应选修12学分", optionalSection.requirement)
        assertEquals(9, optionalSection.courses.size)
        assertEquals("专业限选", optionalSection.courses.first().category)
    }

    @Test
    fun `parses minimum credits from adjacent table cell`() {
        val html = """
            <html>
              <body>
                <table>
                  <tr>
                    <td>$GRADUATION_MINIMUM_CREDITS</td>
                    <td>160</td>
                  </tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val parsed = GraduationAuditPage.parse(html)

        assertEquals(160f, parsed.minimumCredits!!, 0.001f)
    }

    @Test
    fun `parses minimum credits from inline label text`() {
        val html = """
            <html>
              <body>
                <span>$MINIMUM_GRADUATION_CREDITS: 160.5</span>
              </body>
            </html>
        """.trimIndent()

        val parsed = GraduationAuditPage.parse(html)

        assertEquals(160.5f, parsed.minimumCredits!!, 0.001f)
    }

    @Test
    fun `does not parse unrelated numbers`() {
        val html = """
            <html>
              <body>
                <table>
                  <tr><td>$COURSE_CATEGORY</td><td>1</td></tr>
                  <tr><td>$PASSED_CREDITS</td><td>87</td></tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val parsed = GraduationAuditPage.parse(html)

        assertNull(parsed.minimumCredits)
    }

    private companion object {
        const val GRADUATION_MINIMUM_CREDITS = "毕业最低学分"
        const val MINIMUM_GRADUATION_CREDITS = "最低毕业学分"
        const val COURSE_CATEGORY = "第1类"
        const val PASSED_CREDITS = "已修学分"
    }
}
