package cn.jxnu.nvzhuanban.ui.screens.announcement

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.InlineStyle
import cn.jxnu.nvzhuanban.data.model.ParagraphAlign
import cn.jxnu.nvzhuanban.data.storage.ArticleFontSize
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.FullScreenImageViewer
import cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import java.net.URI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailScreen(
    articleId: String,
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    // 默认按 id 自取真实正文；debug 预览页传入一份「假数据 VM」即可离线目测排版。
    viewModel: AnnouncementDetailViewModel = viewModel(
        key = "announcement_detail_$articleId",
        factory = AnnouncementDetailViewModel.factory(articleId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 阅读字号档位：外观偏好（ThemePrefs 持久化、登出不清），整篇正文实时缩放。
    val fontSize by ThemePrefs.articleFontSize.collectAsStateWithLifecycle()
    var showFontSizeSheet by remember { mutableStateOf(false) }
    // 「在浏览器中打开」始终可点：哪怕详情解析失败，也允许用户用浏览器查看原文。
    val externalUrl = remember(articleId) {
        if (articleId.matches(Regex("""\d+"""))) {
            "https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=$articleId"
        } else null
    }
    // 正文里的内嵌图被点击后进入全屏查看器；null = 不显示。
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    // 正文滚动状态提到这里：顶部的阅读进度条与正文共用同一份。
    val listState = rememberLazyListState()
    val readingProgress by remember(listState) {
        derivedStateOf { readingProgressOf(listState) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.announcement_detail_title)) },
                    navigationIcon = { BackNavigationIcon(onBack) },
                    actions = {
                        IconButton(onClick = { showFontSizeSheet = true }) {
                            Icon(
                                Icons.Outlined.FormatSize,
                                contentDescription = stringResource(R.string.announcement_font_size_action),
                            )
                        }
                        if (externalUrl != null) {
                            IconButton(onClick = { openExternalHttpUrl(context, externalUrl) }) {
                                Icon(
                                    Icons.Outlined.OpenInBrowser,
                                    contentDescription = stringResource(R.string.announcement_open_external),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                // 进度条高度恒定占位，只在开始滚动后才显形——避免出现/消失时正文整体跳 2dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                ) {
                    if (readingProgress > 0.005f) {
                        LinearProgressIndicator(
                            progress = { readingProgress },
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onRetry = viewModel::load,
            loading = { m -> cn.jxnu.nvzhuanban.ui.components.ArticleSkeleton(modifier = m) },
        ) { detail ->
            if (detail.requiresLogin) {
                // jwc 无会话时返回的「需要登录」占位页：不渲染正文，给出 app 内登录引导。
                LoginRequiredView(onLogin = onNavigateToLogin)
            } else {
                ArticleBody(
                    detail = detail,
                    fontScale = fontSize.scale,
                    listState = listState,
                    onImageClick = { url -> imageViewerUrl = url },
                )
            }
        }
    }

    // 渲染在 Scaffold 外面：Dialog 会自己起一个 window，覆盖整个 activity
    imageViewerUrl?.let { url ->
        FullScreenImageViewer(
            url = url,
            contentDescription = null,
            onDismiss = { imageViewerUrl = null },
        )
    }

    if (showFontSizeSheet) {
        FontSizeSheet(
            current = fontSize,
            onSelect = ThemePrefs::setArticleFontSize,
            onDismiss = { showFontSizeSheet = false },
        )
    }
}

/**
 * 「需要登录」占位状态：替代正文，居中展示一枚图标 + 文案 + 「去登录」按钮。
 * 按钮回调 [onLogin] 由 AppNav 接到 app 内登录页（不再是历史上那条跳浏览器官方教务处的外链）。
 */
@Composable
private fun LoginRequiredView(onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.announcement_login_required_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.announcement_login_required_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onLogin,
            modifier = Modifier
                .widthIn(min = 160.dp)
                .heightIn(min = 50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.announcement_login_required_action),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ArticleBody(
    detail: ArticleDetail,
    fontScale: Float,
    listState: LazyListState,
    onImageClick: (String) -> Unit,
) {
    // 阅读字号：把用户档位乘进 LocalDensity.fontScale（叠加在系统字体缩放之上）。
    // 标题 / 段落 / 表格 / 附件名等所有 sp 文本整篇成比例缩放，dp 尺寸（图片、间距）不动。
    val density = LocalDensity.current
    val readerDensity = remember(density, fontScale) {
        Density(density.density, density.fontScale * fontScale)
    }
    val scope = rememberCoroutineScope()
    // 长通知滚过几屏后给一个回到顶部的入口（与列表页同一套交互）
    val showBackToTop by remember(listState) {
        derivedStateOf { listState.canScrollBackward && listState.firstVisibleItemIndex > 3 }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // SelectionContainer 让正文（标题 / 时间 / 段落 / 表格文字）支持长按选中 + 复制。
        // Compose 1.7 起，选择手势与 InlineRun.Link 的点击、图片/附件的 clickable 可共存。
        // 已知限制：LazyColumn 会回收滚出屏幕的 item，跨越回收边界的选择会断；可见区域内选中复制正常。
        CompositionLocalProvider(LocalDensity provides readerDensity) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (detail.title.isNotBlank()) {
                            Text(
                                text = detail.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (detail.postedAt.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = detail.postedAt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    if (detail.blocks.isEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.announcement_detail_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // contentType 让 LazyColumn 只在同类 block（段落/图片/表格…）之间复用节点。
                    // 避免滚出屏的 Table 被拿去承载 Paragraph 这类近似整棵重建的错。
                    items(detail.blocks, contentType = { it::class }) { block ->
                        // 段落之间 12dp、图表前后 18dp：Word 正文块之间需要有节奏。
                        // 一刀切 8dp 会让图片和上一段黏在一起。
                        Spacer(modifier = Modifier.height(blockTopSpacing(block)))
                        BlockView(block, onImageClick = onImageClick)
                    }

                    item { Spacer(modifier = Modifier.height(48.dp)) }
                }
            }
        }

        AnimatedVisibility(
            visible = showBackToTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.announcement_back_to_top),
                )
            }
        }
    }
}

/** 正文块之间的留白：段落紧凑、图表宽松，长文才有段落节奏。 */
private fun blockTopSpacing(block: ArticleBlock): Dp = when (block) {
    is ArticleBlock.Paragraph -> 12.dp
    is ArticleBlock.Image -> 18.dp
    is ArticleBlock.Table -> 18.dp
    is ArticleBlock.Attachment -> 14.dp
    ArticleBlock.Divider -> 12.dp
}

/**
 * 阅读进度（0..1）：按「首个可见块 / 总块数」估算。
 *
 * 只驱动顶栏一条 2dp 的指示器，不需要精确的像素进度；块粒度对通知正文足够。
 * （一条通知通常几十个段落/图片块），也避免为估算高度引入自定义测量。
 */
private fun readingProgressOf(state: LazyListState): Float {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0f
    // 内容不足一屏（两个方向都滚不动）不显示进度条：短通知顶一条满格进度条纯属噪音
    if (!state.canScrollBackward && !state.canScrollForward) return 0f
    if (!state.canScrollForward) return 1f
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: return 0f
    return (first.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
}

@Composable
private fun BlockView(
    block: ArticleBlock,
    onImageClick: (String) -> Unit,
) {
    when (block) {
        is ArticleBlock.Paragraph -> ParagraphView(block.runs, block.align)
        is ArticleBlock.Image -> ImageBlockView(block, onImageClick = onImageClick)
        is ArticleBlock.Table -> TableBlockView(block.rows)
        is ArticleBlock.Attachment -> AttachmentView(block)
        ArticleBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ParagraphView(runs: List<InlineRun>, align: ParagraphAlign) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    // 正文段落直接坐在 Scaffold 的 background 上（详情页未覆盖 containerColor）。
    val background = MaterialTheme.colorScheme.background
    val annotated = remember(runs, linkColor, baseColor, background) {
        buildParagraph(runs, linkColor, baseColor, background) { url ->
            openExternalHttpUrl(context, url)
        }
    }
    // 中文正文阅读优化：行高提到 1.7 倍字号（M3 bodyLarge 默认 1.5），
    // 断行策略用 Paragraph 级（高质量换行，长词/URL 不再突兀地撑破行）。
    val base = MaterialTheme.typography.bodyLarge
    val readingStyle = remember(base) {
        base.copy(lineHeight = base.fontSize * 1.7f, lineBreak = LineBreak.Paragraph)
    }
    Text(
        text = annotated,
        style = readingStyle,
        color = baseColor,
        textAlign = align.toTextAlign(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** START 返回 null（沿用样式默认的起始对齐），避免覆盖 RTL 语境下的自然方向。 */
private fun ParagraphAlign.toTextAlign(): TextAlign? = when (this) {
    ParagraphAlign.START -> null
    ParagraphAlign.CENTER -> TextAlign.Center
    ParagraphAlign.END -> TextAlign.End
}

@Composable
private fun ImageBlockView(
    image: ArticleBlock.Image,
    onImageClick: (String) -> Unit,
) {
    // Surface 只约束宽度铺满，高度交给内部 RemoteJwcImage 按图片真实宽高比自适应。
    // <img> 带像素 width/height（Word 粘贴产物通常带）时用 aspectRatio 做 loading 占位精确预留高度。
    // 属性可信时加载完成高度零跳变，阅读位置不会被撑开的图片推移；无宽高信息时退到
    // heightIn(min = 160.dp)。预占位只作用于占位分支：加载成功后 ContentScale.FillWidth 撑满。
    // Image 靠 intrinsic ratio 自己撑开高度，属性与真实位图不符（手动改 HTML / CMS 二次缩放）
    // 也不会把图裁掉一截（jwc 通知里偶尔出现的长图截屏可以完整展示）。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.announcement_view_image)) { onImageClick(image.src) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        val ratio = image.aspectRatio
        RemoteJwcImage(
            url = image.src,
            // jwc 的 img 几乎从不带 alt；给个中文兜底，TalkBack 不至于聚焦到无名称的可点目标
            contentDescription = image.alt ?: stringResource(R.string.announcement_image_fallback_desc),
            modifier = Modifier.fillMaxWidth(),
            placeholderModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ratio != null) Modifier.aspectRatio(ratio)
                    else Modifier.heightIn(min = 160.dp),
                ),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            fallback = {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            },
        )
    }
}

/** 统一列宽的下限 / 上限：窄列（如序号）不至于被挤没；超长文本列在格内换行，而不是把整行拖成无尽横滚。 */
private val TABLE_CELL_MIN_WIDTH = 48.dp
private val TABLE_CELL_MAX_WIDTH = 280.dp

/**
 * 「窄列」阈值（约 6 个汉字）：不超过它的列在压缩时不参与让宽。
 *
 * 这类列装的是序号 / 日期 / 阶段 / 姓名这类**短标签**，本来就只有一行；按比例一起压会把
 * 压到「学院自 / 查」这种断词（真机实测）。让宽的额度只从宽度超过该值的文本列里按比例扣。
 * 宽文本列多折一行不伤读感，短标签断行则很显眼。
 */
private val TABLE_SQUEEZE_EXEMPT_WIDTH = 96.dp

/** 表格自然宽超出视口不足 10% 时，压缩列宽挤进屏内（格内多折一两行），免得为几十 dp 拖一条横滚。 */
private const val TABLE_FIT_TOLERANCE = 1.1f

@Composable
private fun TableBlockView(rows: List<List<List<InlineRun>>>) {
    if (rows.isEmpty()) return
    val columnCount = rows.maxOf { it.size }
    if (columnCount == 0) return
    val scroll = rememberScrollState()
    val surfaceColor = MaterialTheme.colorScheme.surface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // BoxWithConstraints 只为拿视口宽：TableGrid 的列宽适配（拉伸铺满 / 压进屏内 / 横滚）
        // 需要真实可用宽度，而 horizontalScroll 内侧的测量约束是无限宽，拿不到。
        BoxWithConstraints {
            val viewportWidth = maxWidth
            TableGrid(
                rows = rows,
                columnCount = columnCount,
                viewportWidth = viewportWidth,
                modifier = Modifier.horizontalScroll(scroll),
            )
            // 表格宽于视口时，可继续滚的方向盖一条渐变遮沿提示「屏外还有列」。
            // 纯 background、无手势 modifier，不参与命中测试，不挡横滚手势与文本选择。
            Box(Modifier.matchParentSize()) {
                if (scroll.canScrollBackward) {
                    TableEdgeFade(
                        surfaceColor = surfaceColor,
                        leading = true,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                if (scroll.canScrollForward) {
                    TableEdgeFade(
                        surfaceColor = surfaceColor,
                        leading = false,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableEdgeFade(surfaceColor: Color, leading: Boolean, modifier: Modifier = Modifier) {
    val colors =
        if (leading) listOf(surfaceColor, Color.Transparent)
        else listOf(Color.Transparent, surfaceColor)
    Box(
        modifier
            .fillMaxHeight()
            .width(20.dp)
            .background(Brush.horizontalGradient(colors)),
    )
}

/**
 * 统一列宽的表格网格。
 *
 * 旧实现是「每行独立 Row、单元格各按自身内容 intrinsic 定宽」——同一列在不同行里宽度不一，
 * 整张表列不对齐、行尾参差。这里改为两遍布局。
 * 1. 列宽 = 整列最宽单元格的单行 intrinsic 宽，夹进 [TABLE_CELL_MIN_WIDTH]..[TABLE_CELL_MAX_WIDTH]。
 *    再按视口适配——比视口窄按比例拉伸铺满；略宽（≤10%）往回收时**窄列不让宽**
 *    （见 [TABLE_SQUEEZE_EXEMPT_WIDTH]），只压缩宽文本列；真正的宽表保持原宽横滚。
 * 2. 行高 = 该行各单元格在最终列宽下的换行高度取最大，单元格按 Constraints.fixed 撑满自己的格子，
 *    格线与表头底色因此天然贯满整行（不再需要 IntrinsicSize.Min + fillMaxHeight 的分隔线技巧）。
 */
@Composable
private fun TableGrid(
    rows: List<List<List<InlineRun>>>,
    columnCount: Int,
    viewportWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            rows.forEachIndexed { rowIndex, row ->
                repeat(columnCount) { col ->
                    TableCellView(
                        runs = row.getOrNull(col).orEmpty(),
                        isHeader = rowIndex == 0,
                        drawEndBorder = col < columnCount - 1,
                        drawBottomBorder = rowIndex < rows.lastIndex,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val minColPx = TABLE_CELL_MIN_WIDTH.roundToPx()
        val maxColPx = TABLE_CELL_MAX_WIDTH.roundToPx()
        val rowCount = measurables.size / columnCount

        val colWidths = IntArray(columnCount)
        measurables.forEachIndexed { index, cell ->
            val col = index % columnCount
            colWidths[col] = maxOf(colWidths[col], cell.maxIntrinsicWidth(Constraints.Infinity))
        }
        for (col in 0 until columnCount) {
            colWidths[col] = colWidths[col].coerceIn(minColPx, maxColPx)
        }

        val naturalWidth = colWidths.sum()
        val viewportPx = viewportWidth.roundToPx()
        if (viewportPx > 0 && naturalWidth != viewportPx && naturalWidth <= viewportPx * TABLE_FIT_TOLERANCE) {
            if (naturalWidth < viewportPx) {
                scaleColumnsTo(colWidths, viewportPx)
            } else {
                squeezeColumnsTo(colWidths, viewportPx, TABLE_SQUEEZE_EXEMPT_WIDTH.roundToPx())
            }
        }

        val rowHeights = IntArray(rowCount)
        measurables.forEachIndexed { index, cell ->
            val row = index / columnCount
            rowHeights[row] = maxOf(rowHeights[row], cell.minIntrinsicHeight(colWidths[index % columnCount]))
        }

        val placeables = measurables.mapIndexed { index, cell ->
            cell.measure(Constraints.fixed(colWidths[index % columnCount], rowHeights[index / columnCount]))
        }
        layout(colWidths.sum(), rowHeights.sum()) {
            var y = 0
            var index = 0
            for (row in 0 until rowCount) {
                var x = 0
                for (col in 0 until columnCount) {
                    placeables[index++].place(x, y)
                    x += colWidths[col]
                }
                y += rowHeights[row]
            }
        }
    }
}

/** 等比缩放到 [targetPx]（放大用）：末列吃掉舍入误差，保证总宽恰好等于视口。 */
internal fun scaleColumnsTo(widths: IntArray, targetPx: Int) {
    val total = widths.sum()
    if (total <= 0) return
    val scale = targetPx.toFloat() / total
    var used = 0
    for (i in widths.indices) {
        // 末列吃掉舍入误差，保证总宽恰好等于视口
        val width = if (i == widths.lastIndex) {
            (targetPx - used).coerceAtLeast(0)
        } else {
            (widths[i] * scale).roundToInt()
        }
        widths[i] = width
        used += width
    }
}

/**
 * 压进视口（缩小用）：让宽额度只从「宽度超过 [exemptPx] 的列」里按可压量比例扣，
 * 窄列保持原宽——否则短标签会被压成「学院自 / 查」这种断词（真机实测）。
 * 可压余量不够（整张表全是窄列）时退回整体等比压。
 */
internal fun squeezeColumnsTo(widths: IntArray, targetPx: Int, exemptPx: Int) {
    val deficit = widths.sum() - targetPx
    if (deficit <= 0) return
    val shrinkable = IntArray(widths.size) { (widths[it] - exemptPx).coerceAtLeast(0) }
    val totalShrinkable = shrinkable.sum()
    if (totalShrinkable < deficit) {
        scaleColumnsTo(widths, targetPx)
        return
    }
    var used = 0
    for (i in widths.indices) {
        val take = (deficit.toLong() * shrinkable[i] / totalShrinkable).toInt()
        widths[i] -= take
        used += take
    }
    // 整除余数从后往前补齐；每一列都只扣到 [exemptPx] 为止，窄列始终不动
    var rest = deficit - used
    var i = widths.lastIndex
    while (rest > 0 && i >= 0) {
        val room = (widths[i] - exemptPx).coerceAtLeast(0)
        val give = minOf(rest, room)
        widths[i] -= give
        rest -= give
        i--
    }
}

@Composable
private fun TableCellView(
    runs: List<InlineRun>,
    isHeader: Boolean,
    drawEndBorder: Boolean,
    drawBottomBorder: Boolean,
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    // 表格行底色是 surface / 表头 surfaceContainerHigh（见 TableBlockView），两者亮度几乎一致，
    // 对内联色的对比度过滤取 surface 即可。
    val background = MaterialTheme.colorScheme.surface
    val annotated = remember(runs, linkColor, baseColor, background, isHeader) {
        val styled = if (isHeader) {
            // 表头：所有 Text 强制粗体（保留原 color 等其他属性）
            runs.map { run ->
                when (run) {
                    is InlineRun.Text -> run.copy(style = run.style.copy(bold = true))
                    is InlineRun.Link -> run.copy(style = run.style.copy(bold = true))
                    InlineRun.LineBreak -> run
                }
            }
        } else runs
        buildParagraph(styled, linkColor, baseColor, background) { url ->
            openExternalHttpUrl(context, url)
        }
    }
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .then(
                if (isHeader) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier,
            )
            // 单元格被 TableGrid 用 Constraints.fixed 撑满格子，右缘 / 下缘各画一条格线；
            // 列宽统一后相邻格子的线首尾相接，拼成贯通对齐的网格。
            .drawBehind {
                val stroke = 1.dp.toPx()
                if (drawEndBorder) {
                    drawLine(
                        color = borderColor,
                        start = Offset(size.width - stroke / 2, 0f),
                        end = Offset(size.width - stroke / 2, size.height),
                        strokeWidth = stroke,
                    )
                }
                if (drawBottomBorder) {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height - stroke / 2),
                        end = Offset(size.width, size.height - stroke / 2),
                        strokeWidth = stroke,
                    )
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = baseColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AttachmentView(attachment: ArticleBlock.Attachment) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openExternalHttpUrl(context, attachment.url) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 正文字号选择弹层。四档（小/标准/大/特大），选择即写。
 * [ThemePrefs.setArticleFontSize]（持久化、登出不清）；弹层不自动关，
 * 正文在其身后实时缩放，用户可以边看效果边调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSizeSheet(
    current: ArticleFontSize,
    onSelect: (ArticleFontSize) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.announcement_font_size_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ArticleFontSize.entries.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = size == current,
                        onClick = { onSelect(size) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ArticleFontSize.entries.size,
                        ),
                    ) {
                        Text(stringResource(size.labelRes))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            FontSizePreview(current)
        }
    }
}

/**
 * 字号档位的实时预览：弹层不自动关、正文在身后同步缩放，这里再给一段「就地可见」的样例。
 * 不必反复关弹层确认效果。
 */
@Composable
private fun FontSizePreview(size: ArticleFontSize) {
    val base = MaterialTheme.typography.bodyLarge
    Text(
        text = stringResource(R.string.announcement_font_size_preview),
        style = base.copy(
            fontSize = base.fontSize * size.scale,
            // 与正文同样的 1.7 倍行高，预览的行距才和实际阅读体验一致。
            lineHeight = base.fontSize * size.scale * 1.7f,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
    )
}

@get:StringRes
private val ArticleFontSize.labelRes: Int
    get() = when (this) {
        ArticleFontSize.SMALL -> R.string.announcement_font_size_small
        ArticleFontSize.DEFAULT -> R.string.announcement_font_size_default
        ArticleFontSize.LARGE -> R.string.announcement_font_size_large
        ArticleFontSize.XLARGE -> R.string.announcement_font_size_xlarge
    }

/**
 * 把 [InlineRun] 列表搓成可点击的 [AnnotatedString]。
 *
 * Link 使用 [LinkAnnotation.Url] + 自定义 onClick：我们不让系统直接 launch URL。
 * 而是通过 [openExternalHttpUrl] 做协议白名单过滤（只放行 http / https）。
 * 字体的颜色 / 粗细 / 斜体 / 下划线一起 push 成 SpanStyle。
 */
private fun buildParagraph(
    runs: List<InlineRun>,
    linkColor: Color,
    baseColor: Color,
    background: Color,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        when (run) {
            is InlineRun.Text -> withStyle(run.style.toSpanStyle(baseColor, background)) { append(run.text) }
            is InlineRun.LineBreak -> append('\n')
            is InlineRun.Link -> {
                val linkStyles = TextLinkStyles(
                    style = run.style
                        .copy(color = run.style.color ?: linkColor.toArgb())
                        .toSpanStyle(linkColor, background)
                        .copy(textDecoration = TextDecoration.Underline),
                )
                withLink(
                    LinkAnnotation.Url(
                        url = run.url,
                        styles = linkStyles,
                        linkInteractionListener = { link ->
                            if (link is LinkAnnotation.Url) onLinkClick(link.url)
                        },
                    ),
                ) {
                    append(run.text)
                }
            }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

/**
 * jwc 通知正文是 Word 复制粘贴出身，里面的 `style="color:#000"` / `<font color>` 全部按**白底**配色。
 * 暗色主题下直接套用就会「深色字 + 深色底 = 看不见」（见用户反馈：暗黑模式看不到字）。
 *
 * 因此 author 指定的颜色**只在它与当前主题背景 [background] 仍有足够对比度时才保留**，否则回退
 * 主题 [fallbackColor]（onSurface）——后者天然与背景对比，任何主题下都读得清。效果：
 * - 暗色下纯黑 / 深灰正文 → 回退成浅色 onSurface。
 * - 红色等强调色对比度通常达标 → 原样保留；纯蓝这类在黑底上本就难读的 → 一并回退。
 * - 亮色模式下常见的深色字对比度极高 → 全部保留，零回归（还顺手修了「白底浅色字」反向边缘情况）。
 */
private fun InlineStyle.toSpanStyle(fallbackColor: Color, background: Color): SpanStyle {
    val authored = color?.let { Color(it) }
    val resolvedColor =
        if (authored != null && contrastRatio(authored, background) >= MIN_TEXT_CONTRAST) authored
        else fallbackColor
    return SpanStyle(
        color = resolvedColor,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

/** 低于 3.0 WCAG 对比度（AA 大字号阈值）的内联色判定为「在当前背景上不可读」，回退到主题色。 */
private const val MIN_TEXT_CONTRAST = 3.0f

/** 两色的 WCAG 对比度（1..21）。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.wcagLuminance()
    val lb = b.wcagLuminance()
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** WCAG 相对亮度。Compose [Color] 的 red/green/blue 是 sRGB 伽马编码分量（0..1），正是公式所需输入。 */
private fun Color.wcagLuminance(): Float {
    fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

internal fun openExternalHttpUrl(context: Context, url: String) {
    if (!isExternalHttpUrlAllowed(url)) return
    val uri = runCatching { url.toUri() }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    runCatching { context.startActivity(intent) }
}

internal fun isExternalHttpUrlAllowed(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
}
