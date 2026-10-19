package cn.jxnu.nvzhuanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import cn.jxnu.nvzhuanban.data.model.Announcement
import cn.jxnu.nvzhuanban.data.model.AnnouncementType
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadMarker
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementScreen
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementViewModel
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * **仅 debug 构建**的排版预览页：灌一批假数据直接渲染通知页，用来目测分组吸顶、
 * 未读点、卡片缩略图与 footer 的状态。release 源集不含这个文件，正式包不受影响。
 *
 * 启动方式：`adb shell am start -n cn.jxnu.nvzhuanban/.DebugAnnouncementPreviewActivity`
 */
class DebugAnnouncementPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val today = LocalDate.now()
        val items = previewItems(today)
        setContent {
            NvzhuanbanTheme(dynamicColor = false) {
                val viewModel = remember {
                    AnnouncementViewModel(
                        firstPageFlow = { flowOf(items) },
                        fetchPage = { emptyList() },
                        // 锚点落在第 4 条（昨天的通知）上：前 3 条应为未读
                        lastReadMarker = {
                            AnnouncementReadMarker("NOTIFICATION:1004", today.minusDays(1))
                        },
                    )
                }
                AnnouncementScreen(onItemClick = {}, viewModel = viewModel)
            }
        }
    }
}

private fun previewItems(today: LocalDate): List<Announcement> {
    var id = 1001
    fun item(
        title: String,
        daysAgo: Long,
        type: AnnouncementType = AnnouncementType.NOTIFICATION,
        thumb: String? = null,
    ) = Announcement(
        id = (id++).toString(),
        title = title,
        date = today.minusDays(daysAgo),
        type = type,
        thumbnailUrl = thumb,
    )
    return listOf(
        item("关于开展2026年秋季学期期中教学检查工作的通知", 0),
        item("2026年校级教学改革研究课题立项名单公示", 0, AnnouncementType.BULLETIN),
        item(
            "我校学子在2026年全国大学生数学建模竞赛中斩获佳绩",
            0,
            AnnouncementType.PICTURE_NEWS,
            "https://jwc.jxnu.edu.cn/Portal/images/preview-not-found.jpg",
        ),
        item("关于做好2027届本科毕业生图像信息采集工作的通知", 1),
        item("2026-2027学年第一学期公共选修课选课安排", 1, AnnouncementType.BULLETIN),
        item("【教学风采】文学院举办第七届师范生教学技能大赛", 2, AnnouncementType.SHOWCASE),
        item("关于2026年下半年大学英语四六级考试报名工作的通知", 3),
        item("关于调整国庆节后作息时间的通知", 4, AnnouncementType.BULLETIN),
        item("【教学风采】数学与统计学院开展青年教师公开课活动", 9, AnnouncementType.SHOWCASE),
        item("2026年秋季学期重修、补修报名工作安排", 12),
        item("关于做好2026年校级一流本科课程建设工作的通知", 20, AnnouncementType.BULLETIN),
        item("2026年上半年全国大学英语四六级考试成绩查询通知", 34),
        item("【教学风采】化学化工学院举办实验室安全知识竞赛", 41, AnnouncementType.SHOWCASE),
        item("关于推荐2026年江西省教学成果奖申报项目的公示", 58, AnnouncementType.BULLETIN),
        item("2025-2026学年第二学期期末考试安排", 75),
        item("【教学风采】体育学院承办校第十九届运动会", 96, AnnouncementType.SHOWCASE),
    )
}
