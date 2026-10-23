package cn.jxnu.nvzhuanban.data.network

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in smoke test against public news images; never uses a personal photo. */
@RunWith(AndroidJUnit4::class)
class JwcImageLiveDiagnosticTest {
    @Before
    fun requireExplicitLiveImageCheck() {
        assumeTrue(
            "Pass -e liveJwcImages true to check the live university image service",
            InstrumentationRegistry.getArguments().getString("liveJwcImages") == "true",
        )
    }

    @Test
    fun publicNewsImageDecodesThroughProductionClient() = runBlocking {
        val bytes = JwcClient.getBytesAuth(
            "https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202607061117402.png",
            "Empty public image",
        )
        assertNotNull("Image bytes: ${bytes.size}", BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    @Test
    fun publicJpegDecodesThroughProductionClient() = runBlocking {
        val bytes = JwcClient.getBytesAuth(
            "https://jwc.jxnu.edu.cn/UploadSystem/NewUploadFiles/202607260914132.jpg",
            "Empty public image",
        )
        assertNotNull("Image bytes: ${bytes.size}", BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
