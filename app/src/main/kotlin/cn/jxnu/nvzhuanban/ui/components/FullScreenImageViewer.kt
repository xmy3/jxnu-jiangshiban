package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.jxnu.nvzhuanban.R

/**
 * 全屏图片查看器。
 *
 * 交互：
 *  - 双指捏合缩放（[MIN_SCALE]×–[MAX_SCALE]×）；缩放 > 1× 时单指拖拽平移
 *  - 双击：< 1.5× → 跳到 2.5×；≥ 1.5× → 回到 1×
 *  - 单击：> 1× 时回到 1×；= 1× 时关闭
 *  - 返回键 / 右上 × → 关闭
 *  - 加载失败显示「加载失败，点按重试」，点按重新触发下载
 *
 * 黑底全屏 Dialog。底层图片走 [RemoteJwcImage]（带 session cookie，与正文同一加载路径），
 * `ContentScale.Fit` 保证整图可见，再叠 [graphicsLayer] 做缩放/平移变换。
 *
 * 注意：缩放焦点固定在视觉中心（不跟随手指 centroid）。对长图阅读已经够用；
 * 真要做"双指放大就以双指为焦点"的体验，需要在 onGesture 里用
 * `newOffset = (offset - centroid) * actualZoom + centroid + pan` 那套公式，
 * v1 暂未做（未来若有人吐槽再迭代）。
 */
@Composable
fun FullScreenImageViewer(
    url: String,
    contentDescription: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            // 黑底铺满整个 dialog 可点击区域，没有「点击外部」概念；
            // 用 onTap 在 1× 时主动 dismiss，避免在缩放/拖动状态下被误关
            dismissOnClickOutside = false,
        ),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // 递增触发 RemoteJwcImage 重新下载（加载失败后的「点按重试」）
        var retry by remember(url) { mutableIntStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            RemoteJwcImage(
                url = url,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        // panZoomLock = true：第一帧锁定动作类型为缩放/平移，避免抖动
                        detectTransformGestures(panZoomLock = true) { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            scale = newScale
                            // 缩放回到 1× 时把 offset 清零，避免下次进入 viewer 残留偏移视觉
                            offset = if (newScale > 1.01f) {
                                // 中心缩放下可平移边界 = size*(scale-1)/2；用新 scale 计算，
                                // 捏合缩小时旧 offset 一并被 re-clamp 回收，图片不会被拖出屏幕
                                val maxX = size.width * (newScale - 1f) / 2f
                                val maxY = size.height * (newScale - 1f) / 2f
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    onDismiss()
                                }
                            },
                            onDoubleTap = {
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                }
                            },
                        )
                    },
                contentScale = ContentScale.Fit,
                retryKey = retry,
                fallback = {
                    CircularProgressIndicator(color = Color.White)
                },
                error = {
                    Text(
                        text = "加载失败，点按重试",
                        color = Color.White,
                        modifier = Modifier
                            .clickable { retry++ }
                            .padding(16.dp),
                    )
                },
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = Color.White,
                )
            }
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
