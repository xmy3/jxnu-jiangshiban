package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.network.JwcError
import cn.jxnu.nvzhuanban.data.network.JwcException
import org.jsoup.nodes.Document

/**
 * 解析层统一空页守卫：判断"这页到底是不是业务页"。
 *
 * 背景：[cn.jxnu.nvzhuanban.data.network.JwcResponseGuard] 只能在 OkHttp `Response` 层拦住
 * 「显式跳 CAS / LoginAccount / default.aspx?preurl=」和「访问受限 stub」。会话半失效时 jwc 还会
 * **零跳 200 直出**「有框架无数据的壳页」——那种页面 Response 层看不出问题，各解析器过去会静默
 * 返回 `emptyList()` / 空模型，缓存被污染、用户看到无提示的空屏。本对象把这一层判据统一下沉到
 * `Document` 层，供各解析器在"锚点缺失"时调用。
 *
 * 判据（与 JwcResponseGuard 同思路，但输入是 Jsoup 解析后的结构）：
 *  - [Shape.Login]：存在 `input[name=execution]`，或 form 的 action 指向 cas/login / LoginAccount
 *    且带密码框 —— CAS / Portal 登录页的硬特征；
 *  - [Shape.AccessDenied]：整页文本 ≤ [STUB_TEXT_LIMIT] 且同时含「访问受限」「请登录」（jwc 的
 *    60 字节纯文本 stub），或文本含「没有此页面访问权限」；
 *  - [Shape.Maintenance]：文本含「系统维护」「维护中」「访问过于频繁」；
 *  - 文本为空 → [Shape.Unknown]；其余 → [Shape.Business]。
 *
 * **只做判定，不做业务兜底**：解析器必须先自己确认"真实空数据页也会有的锚点"存在，锚点缺失时才
 * 调本对象。否则学生真的没有成绩 / 没有考试时会把合法空页判成错误。
 */
object PageGuard {

    enum class Shape { Business, Login, AccessDenied, Maintenance, Unknown }

    /** 与 JwcResponseGuard.MAX_STUB_BYTES 同口径：正常业务页光 __VIEWSTATE 就远超此长度。 */
    private const val STUB_TEXT_LIMIT = 512

    /** 壳页的统一用户文案：可刷新自愈，持续出现才需要重登。 */
    private const val USER_MESSAGE = "页面加载异常，请下拉刷新重试；若持续出现请重新登录"

    fun detect(doc: Document): Shape {
        val text = pageText(doc)
        if (looksLikeLoginPage(doc)) return Shape.Login
        if (isAccessDenied(text)) return Shape.AccessDenied
        if (isMaintenance(text)) return Shape.Maintenance
        return if (text.isBlank()) Shape.Unknown else Shape.Business
    }

    /**
     * 锚点缺失时的统一出口。
     *
     * @param pageName 页面名（如「成绩」「考试安排」），用于 Decode 的 detail 文案
     * @param anchorFound 该页"真实空数据时也存在"的锚点是否命中
     * @throws JwcException 登录页 → [JwcError.SessionExpired]；其余 shape → [JwcError.Decode]
     */
    fun requireBusinessPage(doc: Document, pageName: String, anchorFound: Boolean) {
        if (anchorFound) return
        val shape = detect(doc)
        if (shape == Shape.Login) throw JwcException(JwcError.SessionExpired)
        throw JwcException(
            JwcError.Decode("${pageName}页面结构无法识别（$shape），可能是会话半失效返回的壳页面"),
            USER_MESSAGE,
        )
    }

    /**
     * 整页可见文本，`&nbsp;`（不间断空格）与全角空格先归一为普通空格，再多空白塌缩 —— 与各解析器
     * `cleanText` 的口径保持一致，避免关键词被奇怪的空白切断。
     */
    private fun pageText(doc: Document): String =
        doc.text().replace('\u00A0', ' ').replace('　', ' ').replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 结构化判登录页。**不用子串扫描**：通知正文里恰好贴了 CAS 文档片段时，`input[name=execution]`
     * 这种字面量会误伤（JwcResponseGuard 有过这段历史），必须看真正的表单结构。
     */
    private fun looksLikeLoginPage(doc: Document): Boolean {
        if (doc.selectFirst("input[name=execution]") != null) return true
        val loginForm = doc.selectFirst("form[action*=/cas/login], form[action*=LoginAccount]")
            ?: return false
        // 密码框优先在 form 内找；jwc 的 LoginAccount 页偶尔把控件渲染在 form 外的包壳 div 里，
        // 所以整页再兜一次（此时 form 已经确认是登录表单，误判风险可忽略）。
        return loginForm.selectFirst("input[name=passwordEncrypt], input[name=password]") != null ||
            doc.selectFirst("input[name=passwordEncrypt]") != null
    }

    private fun isAccessDenied(text: String): Boolean {
        if ("没有此页面访问权限" in text) return true
        return text.length <= STUB_TEXT_LIMIT && "访问受限" in text && "请登录" in text
    }

    private fun isMaintenance(text: String): Boolean =
        MAINTENANCE_MARKERS.any { it in text }

    /** 维护/限流的提示词；命中即视为系统侧问题，不是解析失败。 */
    private val MAINTENANCE_MARKERS = listOf("系统维护", "维护中", "访问过于频繁")
}

/**
 * 解析结果 + 解析告警。
 *
 * 告警是"部分数据被丢弃"的可见信号（列数不符、时间无法解析、缺少必需字段等），由 Repository 转交
 * [cn.jxnu.nvzhuanban.data.network.ParseWarnings]。**不改变** [value] 的语义 —— `parse(x)` 与
 * `parsePage(x).value` 始终一致。
 */
data class PageParse<out T>(
    val value: T,
    val warnings: List<String> = emptyList(),
)

internal fun <T> PageParse<T>.requireComplete(): T {
    if (warnings.isNotEmpty()) {
        throw JwcException(JwcError.Decode(warnings.joinToString("；")))
    }
    return value
}
