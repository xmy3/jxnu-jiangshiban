package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「已修学分 / 毕业进度」统一口径的回归测试。
 *
 * 这两个数在「我的」「成绩」「培养方案」三处显示，必须同一套算法——此前「我的」钳 0..100、
 * 成绩页钳 0..999，修超学分时两页不一致。
 */
class GraduationProgressTest {

    @Test
    fun `毕业进度按官方累计学分算`() {
        assertEquals(36, graduationProgressPercent(63f, 172f))
    }

    @Test
    fun `修超学分不钳到 100`() {
        assertEquals(104, graduationProgressPercent(180f, 172f))
    }

    @Test
    fun `百分比截断而不是四舍五入`() {
        // 171.5/172 = 99.7%，显示成 100% 会让还差半门课的人以为已经毕业
        assertEquals(99, graduationProgressPercent(171.5f, 172f))
    }

    @Test
    fun `缺分母或分子时返回 null 让 UI 显示破折号`() {
        assertNull(graduationProgressPercent(63f, null))
        assertNull(graduationProgressPercent(63f, 0f))
        assertNull(graduationProgressPercent(null, 172f))
        assertNull("还没出分（0 学分）不该显示 0%", graduationProgressPercent(0f, 172f))
    }

    @Test
    fun `已修学分取培养方案与成绩页累计的较大值`() {
        assertEquals(63f, earnedCreditsOf(59f, 63f)!!, 0.001f)
        assertEquals("成绩页累计更小时以培养方案为准", 59f, earnedCreditsOf(59f, 55f)!!, 0.001f)
        assertEquals(59f, earnedCreditsOf(59f, null)!!, 0.001f)
        assertEquals("空成绩单的 0 不能顶掉培养方案学分", 59f, earnedCreditsOf(59f, 0f)!!, 0.001f)
        assertNull(earnedCreditsOf(null, 0f))
    }
}
