package cn.jxnu.nvzhuanban.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 培养方案「已修学分」口径回归测试。
 *
 * 背景：毕业审核页的「当前所修学分总数」只统计培养方案内课程，不含校外慕课/赴外交流；
 * 成绩页的「累计学分」才含（形如 `59 + 4 = 63`）。两页各自显示自己的数字时，
 * 用户会看到「培养方案 59/172」对不上「我的 已修学分 63」。
 * 现在统一取 [TrainingPlan.earnedCredits]（两者较大值）。
 */
class TrainingPlanCreditsTest {

    @Test
    fun `补上毕业审核没算的校外慕课学分`() {
        val plan = TrainingPlan(minimumCredits = 172f, currentCredits = 59f, cumulativeCredits = 63f)
        assertEquals(63f, plan.earnedCredits!!, 0.001f)
        assertEquals(4f, plan.externalCredits!!, 0.001f)
    }

    @Test
    fun `教务把慕课并进毕业审核后不重复累加`() {
        // 毕业审核页与成绩页口径对齐时，earnedCredits 必须还是 63，不能变成 59+63
        val plan = TrainingPlan(minimumCredits = 172f, currentCredits = 63f, cumulativeCredits = 63f)
        assertEquals(63f, plan.earnedCredits!!, 0.001f)
        assertNull(plan.externalCredits)
    }

    @Test
    fun `成绩页累计落后于毕业审核时以毕业审核为准`() {
        // 成绩页缓存旧了（或累计值更小）不能把已修学分往下拉
        val plan = TrainingPlan(minimumCredits = 172f, currentCredits = 59f, cumulativeCredits = 55f)
        assertEquals(59f, plan.earnedCredits!!, 0.001f)
        assertNull(plan.externalCredits)
    }

    @Test
    fun `取不到成绩页时退回毕业审核学分`() {
        val plan = TrainingPlan(minimumCredits = 172f, currentCredits = 59f)
        assertEquals(59f, plan.earnedCredits!!, 0.001f)
        assertNull(plan.externalCredits)
    }

    @Test
    fun `空成绩单的零累计学分不能顶掉毕业审核学分`() {
        val plan = TrainingPlan(minimumCredits = 172f, currentCredits = 59f, cumulativeCredits = 0f)
        assertEquals(59f, plan.earnedCredits!!, 0.001f)
        assertNull(plan.externalCredits)
    }

    @Test
    fun `只有成绩页累计时也能给出已修学分`() {
        val plan = TrainingPlan(cumulativeCredits = 63f)
        assertEquals(63f, plan.earnedCredits!!, 0.001f)
        assertNull("没有培养方案内学分就谈不上差额", plan.externalCredits)
    }

    @Test
    fun `两边都没有学分时返回 null`() {
        val plan = TrainingPlan(minimumCredits = 172f)
        assertNull(plan.earnedCredits)
        assertNull(plan.externalCredits)
    }
}
