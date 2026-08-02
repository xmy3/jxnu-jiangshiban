package cn.jxnu.nvzhuanban.ui.screens.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 课表配色的对比度回归：亮 / 暗**两套色板的每一组** container ↔ onContainer 都必须
 * 达到 WCAG AA ≥ 4.5:1（课程卡 9sp 小字用 onContainer 写在 container 上；
 * "上课中/下节"角标把两色反转用——onContainer 当底、container 当字——比值相同，
 * 一条断言同时覆盖两个方向）。
 *
 * 调整色值时若此测试红了，说明两色离得太近——拉开明度，不要删断言。
 */
class SchedulePaletteContrastTest {

    private val palettes = mapOf(
        "light" to LightCourseColors,
        "dark" to DarkCourseColors,
        // 晚自习专用中性组（hash 池外），对比度要求与正课一致
        "eveningStudy" to listOf(LightEveningStudySwatch, DarkEveningStudySwatch),
    )

    @Test
    fun `every swatch keeps its own text at 4_5 to 1 or better`() {
        palettes.forEach { (name, swatches) ->
            swatches.forEachIndexed { idx, swatch ->
                val container = swatch.container.value shr 32
                val onContainer = swatch.onContainer.value shr 32
                val ratio = contrast(container, onContainer)
                assertTrue(
                    "$name[$idx] container #${container.toString(16)} ↔ " +
                        "onContainer #${onContainer.toString(16)} 对比度 " +
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

    /** WCAG 2.x 相对亮度 → 两色对比度（自动取亮者为分子）。[argb] 是 0xAARRGGBB。 */
    private fun contrast(a: ULong, b: ULong): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(argb: ULong): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFFu).toDouble() / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
