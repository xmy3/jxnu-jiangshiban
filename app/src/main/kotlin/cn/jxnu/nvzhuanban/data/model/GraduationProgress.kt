package cn.jxnu.nvzhuanban.data.model

/**
 * 全 app 统一的「已修学分」与「毕业进度」口径。
 *
 * 这两个数在四个页面出现（我的 / 成绩 / 培养方案 / 课表学分映射），此前每处自己算一份，
 * 已经分叉过一次：毕业进度百分比「我的」钳 0..100、成绩页钳 0..999，已修超过最低学分时
 * 两页数字不同。所以聚合逻辑只留在这里，UI 不要再写 `(credits / minimum * 100)`。
 */

/**
 * 已修学分：**培养方案内学分**与**成绩页累计学分**取较大值。
 *
 * 毕业审核页的「当前所修学分总数」只统计培养方案内课程，不含校外慕课/赴外交流；
 * 成绩页的「累计学分」才含（教务显示成 `59 + 4 = 63`）。取 max 而不是相加：
 * 教务哪天把交流学分并进毕业审核，前者自己就会变大，相加会重复计数。
 */
fun earnedCreditsOf(planCredits: Float?, cumulativeCredits: Float?): Float? =
    listOfNotNull(planCredits, cumulativeCredits?.takeIf { it > 0f }).maxOrNull()

/**
 * 毕业进度百分比（0..999）；已修学分或最低学分缺失/非正时返回 null，UI 显示「—」。
 *
 * 取整用**截断**而不是四舍五入：171.5/172 若显示成 100% 会让还差半门课的人以为已经毕业。
 * 上限不钳 100：学分修超（辅修/提前修）时 104% 比 100% 更诚实，两页也才不会又对不上。
 */
fun graduationProgressPercent(earnedCredits: Float?, minimumCredits: Float?): Int? {
    val earned = earnedCredits?.takeIf { it > 0f } ?: return null
    val minimum = minimumCredits?.takeIf { it > 0f } ?: return null
    return ((earned / minimum) * 100).toInt().coerceIn(0, 999)
}
