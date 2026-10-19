package cn.jxnu.nvzhuanban.ui.screens.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格列宽适配的纯算术部分（[squeezeColumnsTo] / [scaleColumnsTo]）。
 *
 * 来自真机实测的回归：一张 3 列表「阶段 / 时间 / 工作内容」自然宽只比视口宽 ~12%，
 * 走「压进屏内」分支后按比例把**所有**列一起压，结果短标签列被压到装不下
 * 「学院自查」，渲染成「学院自 / 查」。修法是让宽额度只从宽文本列里扣。
 */
class AnnouncementTableColumnsTest {

    /** 3 倍密度下的像素值：88dp / 168dp / 232dp = 264 / 504 / 696 px。 */
    private val exempt = 96 * 3

    @Test
    fun squeeze_keeps_narrow_label_column_untouched() {
        val widths = intArrayOf(264, 504, 696)
        val target = 1005 // 335dp @3x，只比自然宽 1464 窄 ~31%（这里直接调用，跳过 10% 门槛判断）
        squeezeColumnsTo(widths, target, exempt)
        assertEquals("短标签列不该被压", 264, widths[0])
        assertEquals("总宽必须落在视口上", target, widths.sum())
    }

    @Test
    fun squeeze_only_takes_from_wide_columns() {
        val widths = intArrayOf(264, 504, 696)
        squeezeColumnsTo(widths, 1300, exempt)
        assertEquals(264, widths[0])
        assertEquals(1300, widths.sum())
        // 两列都变窄，且更宽的那列让得更多（按可压量比例）
        assertTrue(widths[1] < 504)
        assertTrue(widths[2] < 696)
        assertTrue(504 - widths[1] < 696 - widths[2])
    }

    @Test
    fun squeeze_falls_back_to_proportional_when_every_column_is_narrow() {
        val widths = intArrayOf(240, 240, 240)
        squeezeColumnsTo(widths, 600, exempt)
        assertEquals(600, widths.sum())
        // 等比压，三列仍然一样宽
        assertEquals(widths[0], widths[1])
        assertEquals(widths[1], widths[2])
    }

    @Test
    fun squeeze_never_goes_below_exempt_width() {
        val widths = intArrayOf(264, 300, 900)
        squeezeColumnsTo(widths, 1000, exempt)
        assertEquals(1000, widths.sum())
        assertTrue(widths[1] >= exempt)
    }

    @Test
    fun scale_fills_target_exactly_and_keeps_proportions() {
        val widths = intArrayOf(200, 400, 200)
        scaleColumnsTo(widths, 1200)
        assertEquals(1200, widths.sum())
        assertTrue(widths[1] > widths[0])
        assertEquals(widths[0], widths[2])
    }
}
