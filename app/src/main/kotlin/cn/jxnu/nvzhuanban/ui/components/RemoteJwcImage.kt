package cn.jxnu.nvzhuanban.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.network.JwcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进程级解码结果缓存，按位图字节数计权（ARGB_8888 ≈ w*h*4），容量约 20MB。
 * LazyColumn 回收后滚回、全屏查看器复用正文图、配置变更重建，命中同一 url 时零网络零解码。
 */
private val decodedImageCache = object : LruCache<String, ImageBitmap>(20 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** 退出登录时清空，避免上一用户的头像 / 检索照片以解码位图形式跨账号残留。 */
internal fun clearDecodedImageCache() = decodedImageCache.evictAll()

/**
 * 解码尺寸上限（单边）。通知里的手机长截图原始尺寸可达 1080x10000+，全尺寸解码单张 40MB+：
 * 一张就击穿 20MB LruCache 使缓存整体失效、滚动反复重解码掉帧，且超过多数 GPU 的
 * 4096px 纹理上限时 Compose 直接渲染空白。two-pass（inJustDecodeBounds → inSampleSize）
 * 把最长边压到 ≤2048px，视觉上手机屏内无损。
 */
private const val MAX_DECODE_DIM = 2048

private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    // 循环到两边都 ≤ MAX_DECODE_DIM 为止（条件写 `>` 而不是 half-size 惯用的
    // `/(sample*2) >=`——后者只保证 <2*MAX，12MP 手机照片会整张漏过不降采样）
    while (bounds.outWidth / sample > MAX_DECODE_DIM || bounds.outHeight / sample > MAX_DECODE_DIM) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/** 加载中 / 成功 / 失败三态，Failed 与 Loading 区分开才能给调用方提供重试入口。 */
private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Success(val bitmap: ImageBitmap) : ImageLoadState
    data class Failed(val retryOnResume: Boolean) : ImageLoadState
}

/** 将缓存与实际 IO 隔离，界面回归测试无需登录或访问教务网。 */
internal interface RemoteImageLoader {
    fun cached(url: String): ImageBitmap?
    suspend fun load(url: String): ImageBitmap?
}

private object JwcRemoteImageLoader : RemoteImageLoader {
    override fun cached(url: String): ImageBitmap? = decodedImageCache.get(url)

    override suspend fun load(url: String): ImageBitmap? {
        // fetch + decode 一起留在 IO 线程：通知里的长图截屏在主线程解码会卡帧。
        // getBytesAuth 的装饰性请求失败策略不变：自动恢复 session，但绝不踢出登录。
        val bitmap = withContext(Dispatchers.IO) {
            decodeSampled(JwcClient.getBytesAuth(url, "图片返回空响应"))?.asImageBitmap()
        }
        if (bitmap != null) decodedImageCache.put(url, bitmap)
        return bitmap
    }
}

/**
 * 用应用全局 [cn.jxnu.nvzhuanban.data.network.JxnuHttpClient] 拉一张教务网图片解码成 [ImageBitmap]。
 *
 * 为什么不用 Coil：
 * 1. 教务图片接口在 /MyControl/ 路径下，需要 OkHttp 持久化 session cookie 才能拿到；
 *    用 Coil 默认 ImageLoader 会自己开一份 OkHttp，没登录态。
 * 2. jwc 的 HTTPS 对 Android WebView TLS 栈不友好（见 memory: project-webview-tls-quirk），
 *    OkHttp 同样的 URL 没事 —— 复用同一份 client 避免再写 TrustManager 配置。
 * 3. 全应用就一个头像位 + 通知正文偶尔的内嵌图要远程图片，不值得加依赖。
 *
 * **加载后的布局策略由调用方控制**：
 * - 头像 / 缩略图：传固定尺寸 [modifier]（如 `Modifier.size(80.dp)`）+ 默认 [ContentScale.Crop]，
 *   图片裁切居中铺满。
 * - 通知正文长图：传 `Modifier.fillMaxWidth()` + [ContentScale.FillWidth]，加载成功后 Image
 *   按 intrinsic ratio 自己撑开；loading / 失败占位的尺寸走 [placeholderModifier]
 *   （如 `Modifier.fillMaxWidth().aspectRatio(…)` 精确预占位）。预占位只作用于占位分支：
 *   HTML 宽高属性与真实位图不符时，代价只是位图落地那一刻的一次高度调整，
 *   而不是把加载完的图裁掉一截。
 *
 * 加载中显示 [fallback]；失败显示 [error]（未提供时回退可点按重试的 [fallback]）。
 * 瞬时网络故障有限退避，仍失败时返回前台会重试；成功图继续复用缓存。
 * 自定义 [error] 可把计数状态传给 [retryKey]，递增即重触发下载。
 * [url] 变化或被置为 null 会清空当前图。
 */
@Composable
fun RemoteJwcImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    retryKey: Any? = null,
    // loading / 失败占位分支专用；null 时沿用 modifier（固定尺寸调用方两态同框）
    placeholderModifier: Modifier? = null,
    error: (@Composable () -> Unit)? = null,
    fallback: @Composable () -> Unit,
) = RemoteJwcImageContent(
    url = url,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
    retryKey = retryKey,
    placeholderModifier = placeholderModifier,
    error = error,
    fallback = fallback,
    loader = JwcRemoteImageLoader,
)

@Composable
internal fun RemoteJwcImageContent(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    retryKey: Any? = null,
    placeholderModifier: Modifier? = null,
    error: (@Composable () -> Unit)? = null,
    fallback: @Composable () -> Unit,
    loader: RemoteImageLoader,
) {
    var state by remember(url, loader) {
        val cached = url?.let(loader::cached)
        mutableStateOf<ImageLoadState>(
            if (cached != null) ImageLoadState.Success(cached) else ImageLoadState.Loading
        )
    }
    var localRetry by remember(url) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, url, loader) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                (state as? ImageLoadState.Failed)?.retryOnResume == true
            ) {
                localRetry++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(url, retryKey, localRetry, loader) {
        if (url.isNullOrBlank() || state is ImageLoadState.Success) return@LaunchedEffect
        state = ImageLoadState.Loading
        val result = loadImageWithRetry { loader.load(url) }
        val bmp = result.getOrNull()
        state = if (bmp != null) {
            ImageLoadState.Success(bmp)
        } else {
            ImageLoadState.Failed(result.exceptionOrNull()?.isRetryableImageFailure() == true)
        }
    }
    when (val s = state) {
        is ImageLoadState.Success ->
            // 直接把 modifier 作用在 Image 上：固定尺寸调用方拿到固定框，
            // fillMaxWidth 调用方拿到「宽度铺满 + 高度按 intrinsic ratio」自适应行为。
            Image(
                bitmap = s.bitmap,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
            )
        // loading / 失败：用 Box wrap 占位内容居中。placeholderModifier（缺省 modifier）决定占位框
        // 大小（调用方应提供 min 高度或宽高比，否则 fillMaxWidth 时 Box 会塌缩为 0 高）。
        is ImageLoadState.Failed -> {
            val failureDescription = stringResource(R.string.image_load_failed_retry)
            val retryModifier = if (error == null) {
                Modifier
                    .semantics { stateDescription = failureDescription }
                    .clickable(onClickLabel = stringResource(R.string.common_retry)) { localRetry++ }
            } else Modifier
            Box(
                modifier = (placeholderModifier ?: modifier).then(retryModifier),
                contentAlignment = Alignment.Center,
            ) {
                (error ?: fallback)()
            }
        }
        ImageLoadState.Loading ->
            Box(modifier = placeholderModifier ?: modifier, contentAlignment = Alignment.Center) {
                fallback()
            }
    }
}
