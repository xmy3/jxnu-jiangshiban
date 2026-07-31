package cn.jxnu.nvzhuanban.ui.screens.announcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementDetailUrlTest {

    @Test
    fun allowsOnlyHttpAndHttpsExternalUrls() {
        assertTrue(isExternalHttpUrlAllowed("https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=1"))
        assertTrue(isExternalHttpUrlAllowed("http://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=1"))

        assertFalse(isExternalHttpUrlAllowed("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isExternalHttpUrlAllowed("file:///sdcard/Download/a.html"))
        assertFalse(isExternalHttpUrlAllowed("content://cn.jxnu.nvzhuanban/private"))
        assertFalse(isExternalHttpUrlAllowed("javascript:alert(1)"))
        assertFalse(isExternalHttpUrlAllowed("not a url"))
    }
}
