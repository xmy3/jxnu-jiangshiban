package cn.jxnu.nvzhuanban.data.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFingerprintInstrumentedTest {
    @Test
    fun visitorIdIsStableAndOpaque() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = AuthStorage.init(context)

        val first = DeviceFingerprint.visitorId(context, storage)
        val second = DeviceFingerprint.visitorId(context, storage)

        assertEquals(first, second)
        assertEquals(32, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
