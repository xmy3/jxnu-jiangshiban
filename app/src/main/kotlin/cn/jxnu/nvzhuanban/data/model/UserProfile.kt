package cn.jxnu.nvzhuanban.data.model

data class UserProfile(
    val studentId: String,
    val name: String,
    val college: String,
    val major: String,
    val className: String,
    val grade: Int,
    val avatarUrl: String? = null,
    val cumulativeCredits: Float = 0f,
    val graduationMinimumCredits: Float? = null,
)

/**
 * 拉首页失败时的姓名降级占位（学号通常来自本地保存的参数仍在，唯独姓名解析不出）。
 * 抽成常量是为了让"是否占位"在多处可判断：[UserProfile.hasPlaceholderName] 用它触发姓名自愈
 * （进 Profile 时重取首页 / 用成绩页 meta 回填），避免占位名钉死整个会话。
 */
const val PLACEHOLDER_USER_NAME = "同学"

/** 姓名是否为占位或空：true 表示这条 profile 的姓名还没真正拿到，可以尝试自愈覆盖。 */
val UserProfile.hasPlaceholderName: Boolean
    get() = name.isBlank() || name == PLACEHOLDER_USER_NAME

/** 登录状态：未登录 / 登录中 / 已登录 / 错误 */
sealed interface AuthState {
    data object LoggedOut : AuthState
    data object Loading : AuthState
    data class LoggedIn(val profile: UserProfile) : AuthState
    data class Error(val message: String) : AuthState
}
