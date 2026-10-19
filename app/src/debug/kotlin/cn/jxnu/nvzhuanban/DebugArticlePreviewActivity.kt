package cn.jxnu.nvzhuanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.InlineStyle
import cn.jxnu.nvzhuanban.data.model.ParagraphAlign
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementDetailViewModel
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme

/**
 * **仅 debug 构建**的详情页预览：灌一篇结构完整的假通知，把阅读排版的所有分支都摆出来——
 * 居中标题、项目符号段落、正文内链、统一列宽表格（含需要横滚的宽表）、缺省尺寸的图片、
 * 附件卡片、右对齐落款、正文分隔线，以及随之出现的顶部进度条和回到顶部按钮。
 *
 * 详情页需要登录态才拿得到真数据，改排版时不用真机登录也能目测；release 源集不含本文件。
 *
 * 启动方式：`adb shell am start -n cn.jxnu.nvzhuanban/.DebugArticlePreviewActivity`
 */
class DebugArticlePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NvzhuanbanTheme(dynamicColor = false) {
                val viewModel = remember {
                    AnnouncementDetailViewModel(articleId = "1") { previewDetail() }
                }
                AnnouncementDetailScreen(
                    articleId = "1",
                    onBack = { finish() },
                    onNavigateToLogin = {},
                    viewModel = viewModel,
                )
            }
        }
    }
}

private fun previewDetail(): ArticleDetail = ArticleDetail(
    title = "关于做好2026年秋季学期期中教学检查工作的通知",
    postedAt = "2026-10-19 16:28:25",
    blocks = buildList {
        add(p("关于做好2026年秋季学期期中教学检查工作的通知", ParagraphAlign.CENTER, bold = true))
        add(p("各学院、各教学单位："))
        add(
            p(
                "为进一步规范教学秩序、提高课堂教学质量，学校决定于第 9—10 教学周开展本学期期中教学" +
                    "检查工作。现将有关事项通知如下，请各单位按照要求认真组织落实，并于检查结束后三个" +
                    "工作日内提交书面总结材料。",
            ),
        )
        add(
            p(
                "一、检查内容。课堂教学秩序、教师到岗与学生出勤情况、教学进度与教学大纲的一致性、" +
                    "实验（实训）环节的开出率、教材与讲义的使用情况，以及上学期教学检查中发现问题的" +
                    "整改落实情况。",
            ),
        )
        // 内链：验证 LinkAnnotation 走受控外部浏览器，而不是崩在 WebView
        add(
            ArticleBlock.Paragraph(
                runs = listOf(
                    InlineRun.Text("二、材料报送。检查用表可在教务处网站（"),
                    InlineRun.Link("https://jwc.jxnu.edu.cn", "https://jwc.jxnu.edu.cn"),
                    InlineRun.Text("）下载，填写后交本单位教学秘书汇总。"),
                ),
            ),
        )
        add(
            ArticleBlock.Paragraph(
                runs = listOf(
                    InlineRun.Text("三、时间安排。", InlineStyle(bold = true)),
                    InlineRun.Text("第 9 周为学院自查，第 10 周为学校抽查，具体分组见附件。"),
                ),
            ),
        )
        add(ArticleBlock.Table(rows = scheduleTable))
        add(p("四、分组安排。学校抽查分三组进行，日程如下表："))
        // 宽表：验证 TableGrid 的「真宽表横滚 + 两侧渐变遮沿」
        add(ArticleBlock.Table(rows = wideTable))
        add(
            ArticleBlock.Image(
                src = "https://jwc.jxnu.edu.cn/Portal/images/preview-not-found.jpg",
                alt = "期中教学检查现场",
                widthPx = 800,
                heightPx = 450,
            ),
        )
        // 缺 width/height 的图片：验证退回 heightIn(min = 160dp) 的兜底占位
        add(ArticleBlock.Image(src = "https://jwc.jxnu.edu.cn/Portal/images/preview-not-found.jpg", alt = null))
        add(
            ArticleBlock.Attachment(
                name = "附件1：2026年秋季学期期中教学检查分组安排表.pdf",
                url = "https://jwc.jxnu.edu.cn/Jxzl_preview.pdf",
            ),
        )
        add(ArticleBlock.Divider)
        // 落款：验证 END 对齐还原 Word 的右对齐签名块
        add(
            ArticleBlock.Paragraph(
                runs = listOf(
                    InlineRun.Text("教 务 处", InlineStyle(bold = true)),
                    InlineRun.LineBreak,
                    InlineRun.Text("2026年10月19日"),
                ),
                align = ParagraphAlign.END,
            ),
        )
        // 再铺几段，保证内容超过一屏：进度条与回到顶部按钮才有机会出现
        repeat(4) { i ->
            add(
                p(
                    "五、其他事项（${i + 1}）。检查过程中如发现教学设施故障，请及时报后勤管理处维修；" +
                        "涉及教学安排调整的，须提前三个工作日向教务处报备。各单位要指定专人负责本次检查" +
                        "的联系与协调工作，确保检查工作取得实效。",
                ),
            )
        }
    },
)

private fun p(
    text: String,
    align: ParagraphAlign = ParagraphAlign.START,
    bold: Boolean = false,
) = ArticleBlock.Paragraph(
    runs = listOf(InlineRun.Text(text, InlineStyle(bold = bold))),
    align = align,
)

private val scheduleTable: List<List<List<InlineRun>>> = listOf(
    listOf("阶段", "时间", "工作内容").map { h -> listOf(InlineRun.Text(h, InlineStyle(bold = true))) },
    listOf(
        listOf(InlineRun.Text("学院自查")),
        listOf(InlineRun.Text("第 9 周")),
        listOf(InlineRun.Text("按检查表逐项自查并形成记录")),
    ),
    listOf(
        listOf(InlineRun.Text("学校抽查")),
        listOf(InlineRun.Text("第 10 周")),
        listOf(InlineRun.Text("按分组听课、查阅教学资料")),
    ),
    listOf(
        listOf(InlineRun.Text("总结报送")),
        listOf(InlineRun.Text("第 11 周周三前")),
        listOf(InlineRun.Text("提交书面总结与整改台账")),
    ),
)

private val wideTable: List<List<List<InlineRun>>> = listOf(
    listOf("检查组", "组长", "成员", "检查学院", "检查时间", "集合地点"),
    listOf("第一组", "张三", "李四、王五", "文学院、历史文化与旅游学院", "10月28日上午", "先骕楼一楼大厅"),
    listOf("第二组", "李四", "赵六、钱七", "数学与统计学院、物理与通信电子学院", "10月28日下午", "先骕楼二楼会议室"),
    listOf("第三组", "王五", "孙八、周九", "化学化工学院、生命科学学院", "10月29日上午", "方荫楼三楼报告厅"),
).mapIndexed { index, row ->
    row.map { cell -> listOf(InlineRun.Text(cell, InlineStyle(bold = index == 0))) }
}
