package cn.jxnu.nvzhuanban.data.network

/**
 * 严格三段 semver（major.minor.patch），允许前导 `v` 前缀。
 *
 * 解析器拒绝：
 *  - 纯日期 tag（如 `2024.05.25`：第一段 ≥ 1900 视为日期）
 *  - 非三段（`1.0` / `1.0.0.1` / 空串）
 *  - 含字母段（`1.x.0`）
 *  - 带 `-rc1` / `-beta` / `+build` 后缀的预发版本与元数据
 *
 * 调用方拿到 null 一律静默不提示：GitHub `/releases/latest` 默认就已过滤
 * prerelease，正常分支不会撞上 `-rc1` tag；命中只能说明 tag 格式异常。
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        val byMajor = major.compareTo(other.major)
        if (byMajor != 0) return byMajor
        val byMinor = minor.compareTo(other.minor)
        if (byMinor != 0) return byMinor
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * 解析一个 tag / version 字符串到 [SemVer]，失败返回 null。
         *
         * 接受：`1.0.0`、`v1.0.0`、`V1.10.2`
         * 拒绝：`1.0.0.1`、`1.0`、`v1.0.0-rc1`、`1.0.0+build1`、`2024.05.25`、空串。
         */
        fun fromString(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            // 拒绝预发版 / 元数据后缀（暂不支持，遇到当作"格式不支持"静默跳过）
            if (trimmed.contains('-') || trimmed.contains('+')) return null
            val parts = trimmed.split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            // 日期 tag (如 2024.05.25) 检测：第一段 ≥ 1900 视作日期年份，拒绝。
            // 真实 App 在 2078 年才会被这条规则误伤，那时候轮也轮到下一代项目维护者了。
            if (nums[0] >= 1900) return null
            return SemVer(nums[0], nums[1], nums[2])
        }
    }
}
