package cn.jxnu.nvzhuanban.data.work

import cn.jxnu.nvzhuanban.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知里带的路由必须和导航图里的路由一致 —— 两边都是手写的字符串常量，改了一边忘了另一边
 * 的后果是「点通知没反应」，而且只在真机上能发现。这里用最便宜的方式把它钉住。
 */
class AlertNotifierRouteTest {

    @Test
    fun `notification routes stay in sync with navigation routes`() {
        assertEquals(Routes.GRADES, AlertNotifier.ROUTE_GRADES)
        assertEquals(Routes.EXAMS, AlertNotifier.ROUTE_EXAMS)
    }
}
