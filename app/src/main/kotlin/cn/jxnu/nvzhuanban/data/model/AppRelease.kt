package cn.jxnu.nvzhuanban.data.model

/**
 * GitHub `/releases/latest` 响应的精简投影。
 *
 * 字段全部保留 raw 字符串：版本号比较走
 * [cn.jxnu.nvzhuanban.data.network.SemVer]，发布时间格式化由 UI 层自行决定。
 *
 * [versionName] 是 [tagName] 去掉前导 `v` 后的版本号字符串，方便直接展示。
 */
data class AppRelease(
    /** GitHub tag，例如 `v1.1.0`。 */
    val tagName: String,
    /** 去掉前导 `v` 后的版本号字符串，例如 `1.1.0`。 */
    val versionName: String,
    /** Release 页 URL，跳浏览器走它。 */
    val htmlUrl: String,
    /** Release notes（Markdown 原文，不渲染）。 */
    val body: String,
    /** 发布时间 ISO-8601 字符串，例如 `2026-05-01T12:00:00Z`。 */
    val publishedAt: String,
)
