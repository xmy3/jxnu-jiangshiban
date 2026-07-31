package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.ui.theme.AppShape

/**
 * 骨架屏基件：整棵骨架树套一层同步的呼吸透明度（0.45 ↔ 1.0），比逐块 shimmer 便宜
 * （单个 infiniteTransition + graphicsLayer，不逐帧重组内容），观感也更安静。
 *
 * 骨架纯装饰，[clearAndSetSemantics] 清掉子树语义、只保留一句「加载中」——
 * 骨架是 Loading 分支的全部内容，不留这句 TalkBack 用户在加载态会听到一片空白。
 */
@Composable
fun SkeletonPulse(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val loadingDesc = stringResource(R.string.common_loading)
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .clearAndSetSemantics { contentDescription = loadingDesc },
    ) {
        content()
    }
}

/** 骨架里的一个灰块。 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 一行文字占位：高度按 label/body 的观感取 12dp，宽度由调用方给比例。 */
@Composable
private fun SkeletonLine(widthFraction: Float, height: Dp = 12.dp) {
    SkeletonBox(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height),
    )
}

/** 列表条目骨架：模拟「标题两行 + 元信息」的卡片，对应通知/成绩/考试的行卡。 */
@Composable
private fun SkeletonListCard(showThumb: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.listItem)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showThumb) {
                SkeletonBox(
                    modifier = Modifier.size(width = 72.dp, height = 54.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                SkeletonLine(widthFraction = 0.35f, height = 10.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonLine(widthFraction = 0.9f)
                Spacer(Modifier.height(6.dp))
                SkeletonLine(widthFraction = 0.6f)
            }
        }
    }
}

/** 顶部大数据卡骨架（成绩页的加权平均卡 / 我的页的用户卡这种 hero 位）。 */
@Composable
private fun SkeletonHeroCard(height: Dp = 120.dp) {
    SkeletonBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = AppShape.heroCard,
    )
}

/**
 * 通用「列表页」骨架：可选 hero 大卡 + 若干行卡。成绩 / 考试 / 通知 / 培养方案的
 * Loading 分支都用它，只是参数不同。
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 7,
    showHeroCard: Boolean = false,
    showThumbEvery: Int = 0,
) {
    SkeletonPulse(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showHeroCard) {
                SkeletonHeroCard()
                Spacer(Modifier.height(6.dp))
            }
            repeat(itemCount) { index ->
                SkeletonListCard(showThumb = showThumbEvery > 0 && index % showThumbEvery == 0)
            }
        }
    }
}

/** 我的页骨架：用户渐变卡 + 成绩入口卡 + 工具宫格 + 设置列表的轮廓。 */
@Composable
fun ProfileSkeleton(modifier: Modifier = Modifier) {
    SkeletonPulse(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 用户卡：头像 + 两行文字
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShape.heroCard)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(20.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonLine(widthFraction = 0.4f, height = 16.dp)
                        Spacer(Modifier.height(10.dp))
                        SkeletonLine(widthFraction = 0.6f)
                    }
                }
            }
            SkeletonHeroCard(height = 72.dp)
            SkeletonHeroCard(height = 140.dp)
            SkeletonHeroCard(height = 200.dp)
        }
    }
}

/** 课表格子骨架：左侧节次条 + 网格若干随机高度的课程块轮廓。 */
@Composable
fun ScheduleSkeleton(modifier: Modifier = Modifier) {
    SkeletonPulse(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部周次条
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(20.dp),
                shape = CircleShape,
            )
            Spacer(Modifier.height(4.dp))
            // 7 列课程块：固定的伪随机布局，避免每次组合闪变
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val heights = listOf(
                    listOf(90, 60, 90), listOf(60, 90, 60), listOf(90, 90),
                    listOf(60, 60, 90), listOf(90, 60), listOf(60, 90, 60), listOf(90, 60),
                )
                heights.forEach { column ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        column.forEach { h ->
                            SkeletonBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(h.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文章详情骨架：标题两行 + 时间 + 若干段落行 + 一张图片占位，对应通知详情页。 */
@Composable
fun ArticleSkeleton(modifier: Modifier = Modifier) {
    SkeletonPulse(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonLine(widthFraction = 0.95f, height = 18.dp)
            SkeletonLine(widthFraction = 0.6f, height = 18.dp)
            Spacer(Modifier.height(2.dp))
            SkeletonLine(widthFraction = 0.35f, height = 10.dp)
            Spacer(Modifier.height(10.dp))
            repeat(4) {
                SkeletonLine(widthFraction = 1f)
                SkeletonLine(widthFraction = 1f)
                SkeletonLine(widthFraction = 0.7f)
                Spacer(Modifier.height(6.dp))
            }
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

/** 人员详情骨架：头部（照片 + 姓名行）+ 两张信息卡，对应学生/教师详情页。 */
@Composable
fun PersonDetailSkeleton(modifier: Modifier = Modifier) {
    SkeletonPulse(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头部对齐真实 PersonDetailHeader：同样是一张 heroCard 圆角的整宽卡、
            // 内 padding 20dp、照片 72dp 在卡内——否则骨架→真内容会有约 20dp 的位移跳变
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShape.heroCard)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(
                        modifier = Modifier.size(72.dp),
                        shape = RoundedCornerShape(20.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonLine(widthFraction = 0.4f, height = 16.dp)
                        Spacer(Modifier.height(10.dp))
                        SkeletonLine(widthFraction = 0.55f)
                    }
                }
            }
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = AppShape.card,
            )
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = AppShape.card,
            )
        }
    }
}
