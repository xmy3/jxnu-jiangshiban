package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 完全本地的加载器替身，默认设备回归不访问教务网或消耗登录会话。 */
@RunWith(AndroidJUnit4::class)
class RemoteJwcImageTest {
    @get:Rule
    val compose = createComposeRule()

    private val retryKey = mutableIntStateOf(0)
    private val url = mutableStateOf<String?>("https://example.invalid/image.jpg")
    private lateinit var owner: ImageLifecycleOwner

    @Test
    fun defaultFailedPlaceholderCanBeRetriedWithoutCallerRetryKey() {
        val loader = FakeImageLoader(JwcException(JwcError.Server(404)))
        showImage(loader)
        compose.onNodeWithTag("image").assertHasClickAction()
        compose.runOnIdle { loader.failure = null }
        compose.onNodeWithTag("image").performClick()
        compose.onNodeWithContentDescription("loaded image").assertExists()
        assertEquals(2, loader.attempts.get())
    }

    @Test
    fun exhaustedNetworkFailureRecoversWhenReturningToForeground() {
        val loader = FakeImageLoader(IOException("offline"))
        showImage(loader)
        compose.mainClock.advanceTimeBy(4_100)
        compose.onNodeWithTag("image").assertHasClickAction()
        assertEquals(3, loader.attempts.get())

        compose.runOnIdle { loader.failure = null }
        returnToForeground()
        compose.onNodeWithContentDescription("loaded image").assertExists()
        assertEquals(4, loader.attempts.get())
    }

    @Test
    fun missingImageDoesNotRetryAutomaticallyOnForeground() {
        val loader = FakeImageLoader(JwcException(JwcError.Server(404)))
        showImage(loader)
        compose.onNodeWithTag("image").assertHasClickAction()
        returnToForeground()
        compose.waitForIdle()
        assertEquals(1, loader.attempts.get())
    }

    @Test
    fun successfulImageIsNotReloadedOnForegroundOrRetryKeyChange() {
        val loader = FakeImageLoader()
        showImage(loader)
        compose.onNodeWithContentDescription("loaded image").assertExists()
        returnToForeground()
        compose.runOnIdle { retryKey.intValue++ }
        compose.onNodeWithContentDescription("loaded image").assertExists()
        assertEquals(1, loader.attempts.get())
    }

    @Test
    fun decodedCacheHitAvoidsNetworkEvenAfterForeground() {
        val loader = FakeImageLoader().apply { cachedImage = bitmap }
        showImage(loader)
        compose.onNodeWithContentDescription("loaded image").assertExists()
        returnToForeground()
        compose.waitForIdle()
        assertEquals(0, loader.attempts.get())
    }

    @Test
    fun changingImageUrlClearsPreviousSuccess() {
        val loader = FakeImageLoader()
        showImage(loader)
        compose.onNodeWithContentDescription("loaded image").assertExists()
        compose.runOnIdle {
            loader.failure = JwcException(JwcError.Server(404))
            url.value = "https://example.invalid/missing.jpg"
        }
        compose.onNodeWithTag("image").assertHasClickAction()
        compose.onNodeWithContentDescription("loaded image").assertDoesNotExist()
        assertEquals(2, loader.attempts.get())
    }

    @Test
    fun clearingUrlDuringBackoffStopsRetriesAndForegroundDoesNotRestartThem() {
        val loader = FakeImageLoader(IOException("offline"))
        showImage(loader)
        compose.runOnIdle {
            assertEquals(1, loader.attempts.get())
            url.value = null
        }
        compose.mainClock.advanceTimeBy(5_000)
        returnToForeground()
        compose.onNodeWithTag("image").assertHasNoClickAction()
        compose.onNodeWithContentDescription("loaded image").assertDoesNotExist()
        assertEquals(1, loader.attempts.get())
    }

    @Test
    fun customErrorPreservesParentAvatarSwitchAndClosingItStopsRequests() {
        val loader = FakeImageLoader(JwcException(JwcError.Server(404)))
        val showAvatar = mutableStateOf(true)
        compose.setContent {
            val placeholder: @Composable () -> Unit = { Text("placeholder") }
            Box(
                Modifier.testTag("avatarSwitch").toggleable(
                    value = showAvatar.value,
                    onValueChange = { showAvatar.value = it },
                ),
            ) {
                RemoteJwcImageContent(
                    url = url.value.takeIf { showAvatar.value },
                    contentDescription = "loaded image",
                    modifier = Modifier.size(80.dp),
                    error = placeholder,
                    fallback = placeholder,
                    loader = loader,
                )
            }
        }
        compose.onNodeWithTag("avatarSwitch").performClick()
        compose.runOnIdle { assertFalse(showAvatar.value) }
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(1, loader.attempts.get())
    }

    private fun showImage(loader: RemoteImageLoader) {
        compose.runOnUiThread {
            owner = ImageLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RemoteJwcImageContent(
                    url = url.value,
                    contentDescription = "loaded image",
                    modifier = Modifier.size(80.dp).testTag("image"),
                    retryKey = retryKey.intValue,
                    fallback = { Text("placeholder") },
                    loader = loader,
                )
            }
        }
    }

    private fun returnToForeground() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    private class ImageLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private class FakeImageLoader(var failure: Exception? = null) : RemoteImageLoader {
        val attempts = AtomicInteger()
        val bitmap = ImageBitmap(2, 2)
        var cachedImage: ImageBitmap? = null
        override fun cached(url: String): ImageBitmap? = cachedImage
        override suspend fun load(url: String): ImageBitmap? {
            attempts.incrementAndGet()
            failure?.let { throw it }
            return bitmap
        }
    }
}
