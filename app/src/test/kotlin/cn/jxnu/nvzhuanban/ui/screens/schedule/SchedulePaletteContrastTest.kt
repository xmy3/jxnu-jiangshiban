package cn.jxnu.nvzhuanban.ui.screens.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 课表配色的对比度回归：亮 / 暗**两套色板的每个颜色**都必须让其上的白色文字
 * （课程卡 9sp 小字）达到 WCAG AA ≥ 4.5:1。"上课中/下节"角标把同一色值反过来
 * 用作白底上的文字色，比值相同，一条断言同时覆盖两个方向。
 *
 * 调整色值时若此测试红了，说明颜色太浅——压深它，不要删断言
 * （浅色 300 系 + 白字曾低至 1.7:1，是真实踩过的坑）。
 */
class SchedulePaletteContrastTest {

    private val palettes = mapOf(
        "light" to LightCourseColors,
        "dark" to DarkCourseColors,
    )

    @Test
    fun `all course colors keep white text at 4_5 to 1 or better`() {
        palettes.forEach { (name, colors) ->
            colors.forEachIndexed { idx, color ->
                val ratio = contrastAgainstWhite(color.value shr 32)
                assertTrue(
                    "$name[$idx] #${(color.value shr 32).toString(16)} 对白字对比度 " +
                        "${"%.2f".format(ratio)} < 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `light and dark palettes have the same swatch count so a course keeps its hue across theme switch`() {
        assertEquals(12, LightCourseColors.size)
        assertEquals(LightCourseColors.size, DarkCourseColors.size)
    }

    /** WCAG 2.x 相对亮度 → 与纯白的对比度。[argb] 是 0xAARRGGBB。 */
    private fun contrastAgainstWhite(argb: ULong): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFFu).toDouble() / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val lum = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        return 1.05 / (lum + 0.05)
    }
}
