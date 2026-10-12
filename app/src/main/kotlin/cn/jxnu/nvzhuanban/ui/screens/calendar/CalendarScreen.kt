package cn.jxnu.nvzhuanban.ui.screens.calendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.model.CalendarFileType
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.FullScreenImageViewer
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit = {},
    onOpenDocument: (CalendarEntry) -> Unit = {},
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = rememberCalendarDate()
    val perpetualEntry = (state as? UiState.Success)?.data?.firstOrNull { it.isPerpetual }
    // 非空时全屏展示该 JPG 条目（早年校历是图片文件，app 内直接看，不外跳）
    var imageEntry by remember { mutableStateOf<CalendarEntry?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(rememberTransientErrorSnackbar(viewModel.refreshFailed)) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = { BackNavigationIcon(onBack) },
                actions = {
                    TextButton(
                        onClick = {
                            perpetualEntry?.let { entry ->
                                openCalendarUrlExternally(context, entry.url, entry.title)
                            }
                        },
                        enabled = perpetualEntry != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.calendar_perpetual))
                    }
                    // 两个按钮的触控区域之间留出间隔，避免打开万年历时误触刷新。
                    Spacer(Modifier.width(20.dp))
                    RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding),
        ) {
            StateScaffold(
                state = state,
                onRetry = viewModel::load,
                loading = { m -> cn.jxnu.nvzhuanban.ui.components.ListSkeleton(modifier = m) },
            ) { entries ->
                val semesterEntries = entries.filterNot { it.isPerpetual }
                val highlight = remember(entries, today) { highlightedCalendar(entries, today) }
                if (semesterEntries.isEmpty()) {
                    EmptyState(stringResource(R.string.calendar_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(semesterEntries, key = { it.url }) { entry ->
                            CalendarRow(
                                entry = entry,
                                highlight = highlight?.takeIf { it.entry === entry },
                                onClick = {
                                    // 尽量留在 app 内：PDF 走原生渲染查看页，JPG 走全屏图片查看器；
                                    // DOC/XLS/HTM 等无法原生渲染的早年格式仍外跳系统应用兜底。
                                    when (entry.fileType) {
                                        CalendarFileType.PDF -> onOpenDocument(entry)
                                        CalendarFileType.JPG -> imageEntry = entry
                                        else -> openCalendarUrlExternally(context, entry.url, entry.title)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    imageEntry?.let { entry ->
        FullScreenImageViewer(
            url = entry.url,
            contentDescription = entry.title,
            onDismiss = { imageEntry = null },
        )
    }
}

/**
 * 外部打开校历文件（浏览器 / 系统对应应用）。
 *
 * 校历 URL 是 .pdf / .doc / .xls 等带后缀文件。Android 11+ 在 MIME 解析路径上受
 * package visibility 限制；先尝试 BROWSABLE 把它当 web link 交给浏览器（Chrome 内置
 * PDF viewer），失败再 createChooser 兜底（chooser 总能 startActivity，至少弹出系统
 * 选择器或「无应用可处理」）。
 *
 * 现在只作为兜底：PDF/JPG 已在 app 内查看，这里服务 DOC/XLS/HTM 条目和
 * [CalendarDocumentScreen] 顶栏的「在浏览器中打开」逃生门。
 */
internal fun openCalendarUrlExternally(context: Context, url: String, title: String) {
    val uri = Uri.parse(url)
    val browsable = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(browsable)
    } catch (_: ActivityNotFoundException) {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_VIEW, uri),
            title,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.calendar_open_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

@Composable
private fun CalendarRow(entry: CalendarEntry, highlight: CalendarHighlight?, onClick: () -> Unit) {
    val isHighlighted = highlight != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (isHighlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (isHighlighted) 120.dp else 0.dp)
                .padding(
                    horizontal = if (isHighlighted) 20.dp else 16.dp,
                    vertical = if (isHighlighted) 22.dp else 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (isHighlighted) 56.dp else 40.dp)
                    .clip(RoundedCornerShape(if (isHighlighted) 16.dp else 12.dp))
                    .background(
                        if (isHighlighted) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.fileType.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(if (isHighlighted) 28.dp else 22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (highlight != null) {
                    Text(
                        text = stringResource(
                            if (highlight.upcoming) R.string.calendar_next_semester
                            else R.string.calendar_current_semester,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = if (isHighlighted) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isCorrection) {
                        Spacer(Modifier.width(8.dp))
                        CorrectionBadge()
                    }
                }
                // 文件类型 chip 单独一行做次级标识，便于扫视区分 PDF/JPG/DOC
                if (entry.fileType != CalendarFileType.OTHER) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.fileType.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CorrectionBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.calendar_badge_correction),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

private fun CalendarFileType.icon(): ImageVector = when (this) {
    CalendarFileType.PDF -> Icons.Outlined.PictureAsPdf
    CalendarFileType.DOC -> Icons.Outlined.Description
    CalendarFileType.XLS -> Icons.Outlined.TableChart
    CalendarFileType.JPG -> Icons.Outlined.Image
    CalendarFileType.HTM -> Icons.Outlined.Language
    CalendarFileType.OTHER -> Icons.Outlined.CalendarMonth
}

// 标签用于副标题展示。OTHER 不显示，避免出现含糊的「文件」二字。
private fun CalendarFileType.label(): String = when (this) {
    CalendarFileType.PDF -> "PDF"
    CalendarFileType.DOC -> "DOC"
    CalendarFileType.XLS -> "XLS"
    CalendarFileType.JPG -> "图片"
    CalendarFileType.HTM -> "网页"
    CalendarFileType.OTHER -> ""
}
