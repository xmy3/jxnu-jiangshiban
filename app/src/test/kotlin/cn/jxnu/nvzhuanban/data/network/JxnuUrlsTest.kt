package cn.jxnu.nvzhuanban.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JxnuUrlsTest {

    @Test
    fun `userNum query parameter preserves base64 reserved characters`() {
        val raw = "abc+def/=="
        val urls = listOf(
            JxnuUrls.teacherDetailUrl(raw),
            JxnuUrls.teacherScheduleUrl(raw),
            JxnuUrls.teacherPhotoUrl(raw),
            JxnuUrls.studentDetailUrl(raw),
            JxnuUrls.studentScheduleUrl(raw),
            JxnuUrls.studentPhotoUrl(raw),
        )

        urls.forEach { url ->
            val parsed = url.toHttpUrl()
            assertEquals(raw, parsed.queryParameter("UserNum"))
            assertTrue(
                "UserNum should be percent-encoded in $url",
                parsed.encodedQuery!!.contains("UserNum=abc%2Bdef%2F%3D%3D"),
            )
        }
    }
}
