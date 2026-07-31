package cn.jxnu.nvzhuanban.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全局形状 token。此前各页面 16/20/24dp 圆角混用、语义不清，现在统一为三级：
 *
 *  - [heroCard]  24dp — 每页最多一张的「主角卡」（我的页用户卡、成绩页加权平均卡）
 *  - [card]      20dp — 常规分组容器卡（工具宫格、设置列表、成绩入口）
 *  - [listItem]  16dp — 列表行卡（成绩行、通知行、考试行）
 *  - [tag]        6dp — 行内小标签（学分 tag、通知类型 tag）
 *
 * 新界面一律从这里取形状，不要再写字面量圆角。
 */
object AppShape {
    val heroCard = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(20.dp)
    val listItem = RoundedCornerShape(16.dp)
    val tag = RoundedCornerShape(6.dp)
}
