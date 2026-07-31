package cn.jxnu.nvzhuanban.ui.screens.calendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 校历 PDF 的 app 内查看页。
 *
 * 校历 2018 年起都是 PDF，此前外跳浏览器打开体验割裂（跳出 app、部分机型还弹「下载文件」）。
 * 这里用系统 [PdfRenderer] 原生渲染：下载字节 → 落盘 cache（PdfRenderer 需要可 seek 的文件
 * 描述符，且顺手当离线缓存——校历一学期才更新一次）→ 逐页渲染成位图 → 可缩放浏览。
 * 不引第三方 PDF 库，也不走 WebView（jwc 的 TLS 配置与 WebView 不兼容，见项目 memory）。
 *
 * 缩放交互：双指捏合 1×–4×、双击在 1×/2.5× 间切换；缩放采用**布局级**放大
 * （页面 Image 的宽 = 视口宽 × scale，非 graphicsLayer 变换），这样水平/垂直平移直接由
 * 双向 scroll 容器承担，带惯性、多页也不会互相叠压。
 *
 * 顶栏保留「在浏览器中打开」作为逃生门（渲染异常的病态 PDF / 用户想调系统分享链路）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDocumentScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackNavigationIcon(onBack) },
                actions = {
                    IconButton(onClick = { openCalendarUrlExternally(context, url, title) }) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInBrowser,
                            contentDescription = stringResource(R.string.calendar_doc_open_in_browser),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        // 渲染分辨率：屏宽 2 倍（1080–2160px 区间），1× 阅读清晰、放大 2~3× 后小字仍可辨。
        // 多页文档降回 1 倍屏宽——校历现实中是单页，这只是防病态 PDF 把内存吃爆的护栏。
        val screenWidthPx = remember { context.resources.displayMetrics.widthPixels }
        val state by produceState<UiState<RenderedPdf>>(UiState.Loading, url, retry) {
            value = UiState.Loading
            value = try {
                UiState.Success(renderPdfPages(context, url, screenWidthPx))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                UiState.Error(t.toUserMessage(context.getString(R.string.calendar_doc_load_failed)))
            }
        }
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onRetry = { retry++ },
        ) { doc ->
            PdfPagesViewer(doc)
        }
    }
}

/** 渲染结果。[truncated] = 原始页数超过 [MAX_RENDERED_PAGES]，尾部页被丢弃。 */
private data class RenderedPdf(
    val pages: List<ImageBitmap>,
    val truncated: Boolean,
)

@Composable
private fun PdfPagesViewer(doc: RenderedPdf, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 页面位图恒白底（PDF 惯例），衬一层容器色让页边界在深浅色主题下都可见
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val baseWidth = maxWidth
        var scale by remember { mutableFloatStateOf(1f) }
        // canPan = false：双指捏合只负责改 scale，平移完全交给双向 scroll 容器
        // （带惯性；单指滚动也不会被 transformable 抢事件）
        val transformState = rememberTransformableState { zoom, _, _ ->
            scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .transformable(state = transformState, canPan = { false })
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1.5f) 1f else DOUBLE_TAP_SCALE
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                doc.pages.forEachIndexed { index, page ->
                    Image(
                        bitmap = page,
                        contentDescription = stringResource(R.string.calendar_doc_page_cd, index + 1),
                        modifier = Modifier
                            .width(baseWidth * scale)
                            .aspectRatio(page.width.toFloat() / page.height.toFloat()),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                    )
                }
                if (doc.truncated) {
                    Text(
                        text = stringResource(R.string.calendar_doc_pages_truncated, MAX_RENDERED_PAGES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 下载（或命中本地缓存）并渲染 PDF 全部页面。
 *
 * 校历是公开文件，走裸 [JwcClient.getBytes]（与 [cn.jxnu.nvzhuanban.data.repository.CalendarRepository]
 * 同一通道，无需登录态）。缓存键是 URL 哈希；`.tmp` 写完再 rename，防止杀进程留下半截文件
 * 被 PdfRenderer 当合法输入；万一缓存仍损坏（打开即抛），删文件后透传异常，「重试」会重新下载。
 */
private suspend fun renderPdfPages(
    context: Context,
    url: String,
    screenWidthPx: Int,
): RenderedPdf = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "calendar_docs").apply { mkdirs() }
    val file = File(dir, url.hashCode().toUInt().toString(16) + ".pdf")
    if (!(file.isFile && file.length() > 0L)) {
        val bytes = JwcClient.getBytes(url, context.getString(R.string.calendar_doc_empty_response))
        // jwc 对异常路径可能回 200 + HTML 提示页；魔数校验挡住非 PDF 字节，别落缓存
        if (!bytes.isPdf()) {
            throw JwcException(
                JwcError.Decode("not a pdf"),
                context.getString(R.string.calendar_doc_not_pdf),
            )
        }
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("无法缓存校历文件")
        }
    }
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount
                val count = total.coerceAtMost(MAX_RENDERED_PAGES)
                // 单页 ARGB_8888 在 2160px 宽下约 25MB；多页文档降回屏宽防 OOM
                val targetWidth =
                    if (count <= 2) (screenWidthPx * 2).coerceIn(1080, 2160)
                    else screenWidthPx.coerceAtLeast(720)
                val pages = (0 until count).map { index ->
                    renderer.openPage(index).use { page ->
                        val w = targetWidth
                        val h = (w.toLong() * page.height / page.width.coerceAtLeast(1))
                            .toInt().coerceIn(1, MAX_PAGE_HEIGHT_PX)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF 页背景是透明的，按惯例衬白（也保证深色模式下黑字可读）
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
                RenderedPdf(pages = pages, truncated = total > count)
            }
        }
    } catch (t: Throwable) {
        file.delete()
        throw t
    }
}

private fun ByteArray.isPdf(): Boolean =
    size >= 4 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
        this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_RENDERED_PAGES = 12
private const val MAX_PAGE_HEIGHT_PX = 8192
