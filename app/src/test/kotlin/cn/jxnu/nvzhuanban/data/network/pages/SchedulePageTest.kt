package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.CourseType
import cn.jxnu.nvzhuanban.data.model.SemesterPhase
import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课表页解析回归测试。
 *
 * fixture 采用基于 [SchedulePage] 选择器手写的最小化 HTML（不依赖 samples/ 真实数据），
 * 覆盖：学期下拉 + 服务器 selected、视觉网格连排合并、dgStudentLesson 学分/教师补全、
 * ASP.NET 三件套隐藏字段、CourseType 推断。
 */
class SchedulePageTest {

    @Test
    fun `parses semester options and server selected value`() {
        val parsed = SchedulePage.parse(FIXTURE)

        // 隐藏 ASP.NET 字段必须原样回带，POST 切学期才能成功
        assertEquals("VS_TOKEN", parsed.viewState)
        assertEquals("GEN_TOKEN", parsed.viewStateGenerator)
        assertEquals("EV_TOKEN", parsed.eventValidation)

        // serverSelectedValue 来自 option[selected]
        assertEquals("2026\\3\\1 0:00:00", parsed.serverSelectedValue)

        // 学期下拉收齐 3 项，且 startDate 既支持 `\` 也支持 `/`
        assertEquals(3, parsed.semesters.size)
        val mar = parsed.semesters.first { it.label == "25-26第2学期" }
        assertEquals(LocalDate.of(2026, 3, 1), mar.startDate)
        val sep = parsed.semesters.first { it.label == "25-26第1学期" }
        assertEquals(LocalDate.of(2025, 9, 1), sep.startDate)

        // 顶栏 semester 取的是 serverSelected 对应的 label
        assertEquals("25-26第2学期", parsed.semester)
        assertEquals(LocalDate.of(2026, 3, 1), parsed.semesterStart)
    }

    @Test
    fun `merges consecutive grid cells and enriches teacher from dgStudentLesson`() {
        val parsed = SchedulePage.parse(FIXTURE)

        // 网格里"高等数学"第 1 节、第 2 节是两个相邻 td；mergeConsecutiveSlots 应合并成 1-2
        val math = parsed.courses.first { it.name == "高等数学" }
        assertEquals(1, math.weekday)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("文科楼A301", math.location)
        // 老师来自 dgStudentLesson 行
        assertEquals("李老师", math.teacher)
        assertEquals(CourseType.LECTURE, math.type)

        // 体育课位置只占一节，type 推断为 PE
        val pe = parsed.courses.first { it.name == "体育" }
        assertEquals(CourseType.PE, pe.type)
        assertEquals(3, pe.startSection)
        assertEquals(3, pe.endSection)
    }

    @Test
    fun `grid cells without bgcolor or labeled bgcolor are skipped`() {
        // 网格表头 bgcolor=#00ccff（星期标签）、节次标签 #cccccc 都不该当成课
        val parsed = SchedulePage.parse(FIXTURE)
        val noisy = parsed.courses.firstOrNull { it.name == "星期一" || it.name == "1" }
        assertNull("不应把表头当成课程", noisy)
    }

    @Test
    fun `falls back when serverSelectedValue is missing`() {
        val parsed = SchedulePage.parse(FIXTURE_NO_SELECTED)
        assertNull(parsed.serverSelectedValue)
        // 没有 selected 时退而求其次：按 isCurrent / 第一个，semester 标签依然非空
        assertNotNull(parsed.semester)
        assertTrue(parsed.semesters.isNotEmpty())
    }

    /**
     * 回归：晚间段在网格里有两种"晚上"格子——段标签 `<td>晚上</td>`（与 上午/下午 同列）
     * 与节次标签 `<td>晚<br>上</td>`（与 1 2 / 6 7 同列）。早期实现只看文本，会把段标签
     * 当成节次标签，导致 weekdayCells 整体右移一列：周一晚课跑到周二，并且段标签格本身
     * 被解析成 name="晚" / location="上" 的幽灵课。
     */
    @Test
    fun `night row distinguishes segment label from section label`() {
        val parsed = SchedulePage.parse(NIGHT_FIXTURE)

        val mondayNight = parsed.courses.firstOrNull { it.name == "药物常识（慕课）" }
        assertNotNull("周一晚 10-11 节的课不应被段标签格挤走列", mondayNight)
        assertEquals(1, mondayNight!!.weekday)
        assertEquals(10, mondayNight.startSection)
        assertEquals(11, mondayNight.endSection)
        assertEquals("W7302", mondayNight.location)

        val thursdayNight = parsed.courses.firstOrNull { it.name == "硬笔书法" }
        assertNotNull("周四晚 10-11 节的课也要跟着对齐回正确列", thursdayNight)
        assertEquals(4, thursdayNight!!.weekday)
        assertEquals(10, thursdayNight.startSection)

        // 段标签格 "晚上" 和节次标签格 "晚<br>上" 都不应被当成幽灵课程
        assertNull("段标签 '晚上' 不应被识别成课程", parsed.courses.firstOrNull { it.name == "晚" })
        assertNull("节次标签 '晚\\n上' 不应被识别成课程", parsed.courses.firstOrNull { it.name == "晚上" })
    }

    @Test
    fun `extracts control prefix from ddlSterm name attribute`() {
        // 学生页：_ctl1，向后兼容默认值
        assertEquals("_ctl1", SchedulePage.parse(FIXTURE).controlPrefix)
        // 教师页（教务通过 All_Display.aspx 渲染 Xfz_Kcb.ascx 时）用 _ctl6
        assertEquals("_ctl6", SchedulePage.parse(TEACHER_PREFIX_FIXTURE).controlPrefix)
        // ddlSterm 有 id 无 name 属性时退回到 _ctl1（锚点仍在，只是 name 缺失）
        assertEquals(
            "_ctl1",
            SchedulePage.parse("""<html><select id="_ctl1_ddlSterm"><option value="2026/3/1 0:00:00">x</option></select></html>""").controlPrefix,
        )
    }

    @Test
    fun `parse fails fast on page without ddlSterm anchor`() {
        // 锚点 fail-fast（2026-07 实测教训）：jwc 对无效会话曾以 200 纯文本穿透 Guard，
        // 无锚点时 parse 把垃圾输入静默解析成「合法空课表」写进缓存——UI 呈现 Success 空网格、
        // 无错误无重试无重登入口。ddlSterm 学期下拉是课表页固定控件（真实空课表也有），
        // 缺它必须抛 Decode 走错误态。
        val junkInputs = listOf(
            "您提交的内容中含有非法字符,已经被拒绝4.Error参数错误",
            "【访问受限：请登录后访问！】【参数错误】",
            "<html></html>",
        )
        junkInputs.forEach { junk ->
            val thrown = assertThrows(JwcException::class.java) { SchedulePage.parse(junk) }
            assertTrue("应是 Decode 错误: ${thrown.error}", thrown.error is JwcError.Decode)
        }
    }

    @Test
    fun `isCurrent and phase follow the injected date`() {
        // 学期中（2026-05-01）：本学期 = 25-26第2学期（selected，start 2026/3/1 周日 → 第 1 周
        // 从 3/2 周一起算），第 9 周进行中
        val may = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 5, 1))
        assertEquals("25-26第2学期", may.semesters.first { it.isCurrent }.label)
        assertEquals(SemesterPhase.InProgress(9), may.phaseAt(LocalDate.of(2026, 5, 1)))

        // 暑假（2026-07-17）：isCurrent 仍指向刚结束的春季学期，但相位已是 Ended ——
        // 上层据此显示假期横幅，而不是旧算法的「第 20 周」
        val summer = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 7, 17))
        assertEquals("25-26第2学期", summer.semesters.first { it.isCurrent }.label)
        assertEquals(SemesterPhase.Ended, summer.phaseAt(LocalDate.of(2026, 7, 17)))

        // 秋季开学后（2026-09-02）：isCurrent 移到 26-27第1学期
        val autumn = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 9, 2))
        assertEquals("26-27第1学期", autumn.semesters.first { it.isCurrent }.label)

        // 边界：真实开学日 = 名义 9/1（周二）对齐的 8/31 周一。8/30 仍是暑假（isCurrent 停在
        // 春季），8/31 当天即切入秋季学期——不再有「开学第一天还提示假期倒计时」的一天窗口
        val beforeFirstDay = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 8, 30))
        assertEquals("25-26第2学期", beforeFirstDay.semesters.first { it.isCurrent }.label)
        val firstDay = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 8, 31))
        assertEquals("26-27第1学期", firstDay.semesters.first { it.isCurrent }.label)

        // 反向边界：名义 3/1 是周日，真实上课从 3/2 周一开始。3/1 当天 isCurrent 仍是上学期
        //（假期最后一天，倒计时 1 天），3/2 才切到春季
        val nominalSpringDay = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 3, 1))
        assertEquals("25-26第1学期", nominalSpringDay.semesters.first { it.isCurrent }.label)
        val springFirstDay = SchedulePage.parse(FIXTURE, today = LocalDate.of(2026, 3, 2))
        assertEquals("25-26第2学期", springFirstDay.semesters.first { it.isCurrent }.label)
    }

    @Test
    fun `parses teacher schedule sample with ctl6 prefix and empty grid`() {
        val html = sampleHtml("teacher_schedule.html")

        val parsed = SchedulePage.parse(html)

        // 教师页用 _ctl6_*，prefix 自动识别
        assertEquals("_ctl6", parsed.controlPrefix)
        assertEquals("2026/9/1 0:00:00", parsed.serverSelectedValue)
        // 该样本是一位 校领导 老师，grid 全空
        assertTrue("校领导样本不应解析出课程: ${parsed.courses}", parsed.courses.isEmpty())
        // 学期下拉 12 项
        assertEquals(12, parsed.semesters.size)
        // 三件套都拿到
        assertTrue(parsed.viewState.isNotBlank())
        assertEquals("547AE704", parsed.viewStateGenerator)
        assertTrue(parsed.eventValidation.isNotBlank())
    }

    @Test
    fun `parses student schedule sample (same UserControl as teacher)`() {
        val html = sampleHtml("student_schedule.html")

        val parsed = SchedulePage.parse(html)

        // 学生通过 All_Display.aspx 渲染的课表与教师同结构，prefix 仍是 _ctl6
        assertEquals("_ctl6", parsed.controlPrefix)
        assertEquals("2026/9/1 0:00:00", parsed.serverSelectedValue)
        assertEquals(12, parsed.semesters.size)
        assertTrue(parsed.viewState.isNotBlank())
        assertEquals("547AE704", parsed.viewStateGenerator)
        assertTrue(parsed.eventValidation.isNotBlank())
        // 样本是一位 07 级老学生，26-27 学期 grid 全空
        assertTrue("旧学生样本不应解析出课程: ${parsed.courses}", parsed.courses.isEmpty())
    }

    /**
     * 回归：教师页面没有 dgStudentLesson，原来 fallback 用 `extractTeacherFromClassText`
     * 从班级文本里抽 2-4 个连续汉字当教师名，结果把「24级计算机科学与技术（师范）1班」
     * 截成「级计算机」展示到 UI 上（见 issue: 教师课表 meta 行的"级计算机"）。
     * 修复后：班级文本原样保留到 [Course.className]；UI 在教师视角下用 className 即可。
     */
    @Test
    fun `teacher view preserves full class name in className field`() {
        val html = """
            <html><body>
              <input type="hidden" name="__VIEWSTATE" value="V" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="G" />
              <input type="hidden" name="__EVENTVALIDATION" value="E" />
              <select name="_ctl6:ddlSterm" id="_ctl6_ddlSterm">
                <option value="2026/3/1 0:00:00" selected="selected">25-26第2学期</option>
              </select>
              <div id="_ctl6_NewKcb">
                <table>
                  <tr>
                    <td bgcolor="#cccccc">节</td>
                    <td bgcolor="#00ccff">星期一</td>
                  </tr>
                  <tr>
                    <td bgcolor="#cccccc">6 7</td>
                    <td bgcolor="#FFFFCC"><div align="center">Python程序设计（师范）<br>( W2209 )<br>24级计算机科学与技术（师范）1班</div></td>
                  </tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = SchedulePage.parse(html)

        assertEquals(1, parsed.courses.size)
        val c = parsed.courses.first()
        assertEquals("Python程序设计（师范）", c.name)
        assertEquals("W2209", c.location)
        assertEquals("24级计算机科学与技术（师范）1班", c.className)
        // teacher 字段不保证内容（regex fallback 仍可能给出碎片），UI 教师页改用 className，
        // 这里只锁住 className 不为空，避免后续重构时 className 默认值漂回 ""
        assertTrue("className 不应为空", c.className.isNotBlank())
    }

    @Test
    fun `inferType marks lab by name keyword or X-building and online by mooc keywords`() {
        val parsed = SchedulePage.parse(TYPE_FIXTURE)
        val byName = parsed.courses.associateBy { it.name }
        // 名称含「实验」→ LAB
        assertEquals(CourseType.LAB, byName.getValue("数据结构（实验）").type)
        // 名称无关键词但教室在 X 楼（瑶湖实验楼）→ LAB
        assertEquals(CourseType.LAB, byName.getValue("程序设计实训").type)
        // 「慕课」→ ONLINE
        assertEquals(CourseType.ONLINE, byName.getValue("药物常识（慕课）").type)
        // 英文 MOOC 大小写不敏感 → ONLINE
        assertEquals(CourseType.ONLINE, byName.getValue("创新创业mooc").type)
    }

    private companion object {
        val TEACHER_PREFIX_FIXTURE = """
            <html><body>
              <input type="hidden" name="__VIEWSTATE" value="V" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="G" />
              <input type="hidden" name="__EVENTVALIDATION" value="E" />
              <select name="_ctl6:ddlSterm" id="_ctl6_ddlSterm">
                <option value="2026/9/1 0:00:00" selected="selected">26-27第1学期</option>
              </select>
              <div id="_ctl6_NewKcb"><table></table></div>
            </body></html>
        """.trimIndent()
        // ASP.NET 网格用 bgcolor 区分：#cccccc 节次/段标签，#00ccff 星期标签，#FFFFFF/有色 = 课
        val FIXTURE = """
            <html><body>
            <form>
              <input type="hidden" name="__VIEWSTATE" value="VS_TOKEN" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN_TOKEN" />
              <input type="hidden" name="__EVENTVALIDATION" value="EV_TOKEN" />
              <select id="_ctl1_ddlSterm">
                <option value="2025\9\1 0:00:00">25-26第1学期</option>
                <option value="2026\3\1 0:00:00" selected="selected">25-26第2学期</option>
                <option value="2026/9/1 0:00:00">26-27第1学期</option>
              </select>
              <div id="_ctl1_NewKcb">
                <table>
                  <tr>
                    <td bgcolor="#cccccc">节</td>
                    <td bgcolor="#00ccff">星期一</td>
                    <td bgcolor="#00ccff">星期二</td>
                  </tr>
                  <tr>
                    <td bgcolor="#cccccc">1</td>
                    <td bgcolor="#FFFFCC"><div>高等数学<br>( 文科楼A301 )<br>计科24-1班</div></td>
                    <td></td>
                  </tr>
                  <tr>
                    <td bgcolor="#cccccc">2</td>
                    <td bgcolor="#FFFFCC"><div>高等数学<br>( 文科楼A301 )<br>计科24-1班</div></td>
                    <td></td>
                  </tr>
                  <tr>
                    <td bgcolor="#cccccc">3</td>
                    <td bgcolor="#CCFFCC"><div>体育<br>( 田径场 )<br>计科24-1班</div></td>
                    <td></td>
                  </tr>
                </table>
              </div>
              <table id="_ctl1_dgStudentLesson">
                <tr><td>课程号</td><td>课程名称</td><td>学时</td><td>班级</td><td>任课老师</td></tr>
                <tr><td>MA101</td><td>高等数学</td><td>64</td><td>计科24-1班</td><td>李老师</td></tr>
                <tr><td>PE101</td><td>体育</td><td>32</td><td>计科24-1班</td><td>王老师</td></tr>
              </table>
            </form>
            </body></html>
        """.trimIndent()

        val FIXTURE_NO_SELECTED = """
            <html><body>
              <input type="hidden" name="__VIEWSTATE" value="X" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="Y" />
              <input type="hidden" name="__EVENTVALIDATION" value="Z" />
              <select id="_ctl1_ddlSterm">
                <option value="2025\9\1 0:00:00">25-26第1学期</option>
              </select>
              <div id="_ctl1_NewKcb"><table></table></div>
            </body></html>
        """.trimIndent()

        // 覆盖 inferType 的 LAB / ONLINE 分支：名称关键词、X 楼位置兜底、MOOC 大小写
        val TYPE_FIXTURE = """
            <html><body>
              <input type="hidden" name="__VIEWSTATE" value="V" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="G" />
              <input type="hidden" name="__EVENTVALIDATION" value="E" />
              <select id="_ctl1_ddlSterm">
                <option value="2026\3\1 0:00:00" selected="selected">25-26第2学期</option>
              </select>
              <div id="_ctl1_NewKcb">
                <table>
                  <tr>
                    <td bgcolor="#cccccc">节</td>
                    <td bgcolor="#00ccff">星期一</td>
                    <td bgcolor="#00ccff">星期二</td>
                    <td bgcolor="#00ccff">星期三</td>
                    <td bgcolor="#00ccff">星期四</td>
                  </tr>
                  <tr>
                    <td bgcolor="#cccccc">1</td>
                    <td bgcolor="#FFFFCC"><div>数据结构（实验）<br>( W2101 )<br>计科24-1班</div></td>
                    <td bgcolor="#FFFFCC"><div>程序设计实训<br>( X4313e )<br>计科24-1班</div></td>
                    <td bgcolor="#FFFFCC"><div>药物常识（慕课）<br>( 线上 )<br>计科24-1班</div></td>
                    <td bgcolor="#FFFFCC"><div>创新创业mooc<br>( W2102 )<br>计科24-1班</div></td>
                  </tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()

        // 还原 samples/schedule.html 里晚间段的真实结构：
        // tds[0]=段标签 "晚上"（无 <br>）、tds[1]=节次标签 "晚<br>上"、tds[2..8]=周一到周日 7 个格子
        val NIGHT_FIXTURE = """
            <html><body>
              <input type="hidden" name="__VIEWSTATE" value="V" />
              <input type="hidden" name="__VIEWSTATEGENERATOR" value="G" />
              <input type="hidden" name="__EVENTVALIDATION" value="E" />
              <select id="_ctl1_ddlSterm">
                <option value="2026\3\1 0:00:00" selected="selected">25-26第2学期</option>
              </select>
              <div id="_ctl1_NewKcb">
                <table>
                  <tr>
                    <td bgcolor="#00ccff"><div align="center">晚上</div></td>
                    <td bgcolor="#cccccc"><div align="center">晚<br>上</div></td>
                    <td bgcolor="#66FFCC"><div align="center">药物常识（慕课）<br>( W7302 )<br>赵六#1班</div></td>
                    <td bgcolor=""><div>&nbsp;</div></td>
                    <td bgcolor=""><div>&nbsp;</div></td>
                    <td bgcolor="#66FFCC"><div align="center">硬笔书法<br>( W7101 )<br>钱七#2班</div></td>
                    <td bgcolor=""><div>&nbsp;</div></td>
                    <td bgcolor=""><div>&nbsp;</div></td>
                    <td bgcolor=""><div>&nbsp;</div></td>
                  </tr>
                </table>
              </div>
              <table id="_ctl1_dgStudentLesson">
                <tr><td>课程号</td><td>课程名称</td><td>学时</td><td>班级</td><td>任课老师</td></tr>
                <tr><td>006013</td><td>药物常识（慕课）</td><td>2</td><td>赵六#1班</td><td>赵六</td></tr>
                <tr><td>005011</td><td>硬笔书法</td><td>2</td><td>钱七#2班</td><td>钱七</td></tr>
              </table>
            </body></html>
        """.trimIndent()
    }
}
