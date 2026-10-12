package cn.jxnu.nvzhuanban.ui.screens.grades

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.storage.ThemeMode
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose rendering with a redacted transcript; no account or network login is needed. */
@RunWith(AndroidJUnit4::class)
class GradesExchangeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exchangeGradesAndCreditsAreVisibleInTheCorrectSummaryAndCourseCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val html = instrumentation.context.assets.open("samples/grades_exchange.html")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = GradePage.parse(html)
        val courseNames = parsed.semesters.flatMap { it.grades }.map { it.courseName }.toSet()

        compose.setContent {
            NvzhuanbanTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    GradesList(semesters = parsed.semesters, graduationMinimumCredits = null)
                }
            }
        }
        // Let the actual 700 ms header number animation reach its final values.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertStatValue(label = "已修学分", value = "63")
        assertStatValue(label = "课程门数", value = "28")
        saveScreenshot("grades-exchange-summary")

        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("25-26第2学期"))
        val semester = compose.onNodeWithText("25-26第2学期", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val credits = compose.onNodeWithText("34 学分", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("34 学分应与第二学期标题在同一行", abs(semester.center.y - credits.center.y) < 2f)
        assertTrue("学期学分应位于对应标题右侧", credits.left > semester.right)
        saveScreenshot("grades-exchange-semester")

        assertCourseCard("互联网金融", "91.3", courseNames)
        assertCourseCard("婚恋职场人格", "100", courseNames)
        saveScreenshot("grades-exchange-courses")
    }

    private fun assertStatValue(label: String, value: String) {
        val labelBounds = compose.onNodeWithText(label, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val valueBounds = compose.onNodeWithText(value, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The two values are checked against their own column labels, not a global number match.
        assertTrue("$value 应与 $label 居中对齐", abs(labelBounds.center.x - valueBounds.center.x) < 2f)
        assertTrue("$value 应显示在 $label 上方", valueBounds.bottom <= labelBounds.top)
    }

    private fun assertCourseCard(name: String, score: String, courseNames: Set<String>) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        val course = compose.onNodeWithText(name, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val expected = listOf(name, score, "2 学分", "赴外交流学习 · 江西师范大学慕课")
        // Find the smallest enclosing semantic card. A list/root containing several courses
        // cannot satisfy this check, even when another visible course has the same credit/score.
        val card = generateSequence(course.parent) { it.parent }.firstOrNull { ancestor ->
            val texts = descendantTexts(ancestor)
            texts.containsAll(expected) && texts.count { it in courseNames } == 1
        }
        assertNotNull("$name 的同一张课程卡应包含成绩、学分和学校来源", card)
        val inCard = hasAnyAncestor(SemanticsMatcher("course card for $name") { it.id == card!!.id })
        expected.drop(1).forEach { text ->
            compose.onNode(hasText(text) and inCard, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private fun descendantTexts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap(::descendantTexts)

    private fun saveScreenshot(name: String) {
        // Prefer the runner's output directory so AGP collects screenshots before uninstalling
        // the test apps. Direct adb runs can retrieve the external-files fallback instead.
        // Capture failure is optional and must not mask the UI assertions.
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            try {
                val outputDirectory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val directory = outputDirectory?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(instrumentation.targetContext.getExternalFilesDir(null), "test-results")
                check(directory.isDirectory || directory.mkdirs())
                val output = File(directory, "$name.png")
                output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                Log.i("GradesExchangeTest", "Screenshot: ${output.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        }.onFailure { Log.w("GradesExchangeTest", "Optional screenshot failed", it) }
    }
}
