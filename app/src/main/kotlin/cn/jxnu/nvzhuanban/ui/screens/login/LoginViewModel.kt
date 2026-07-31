package cn.jxnu.nvzhuanban.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    /** 本机是否存有可一键重登的加密凭证——true 时登录页展示「用已保存的密码登录」按钮。 */
    val hasSavedCredentials: Boolean = false,
)

class LoginViewModel(
    private val authRepo: AuthRepository = AuthRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            username = authRepo.rememberedUsername().orEmpty(),
            rememberMe = authRepo.isRememberMeOn(),
            hasSavedCredentials = authRepo.hasSavedCredentials(),
        )
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 暴露全局认证态：Application 启动期间如果 cookie 恢复成功，登录页会跳过自身。 */
    val authState: StateFlow<AuthState> = authRepo.state

    init {
        val authError = authRepo.state.value as? AuthState.Error
        if (authError != null) {
            _state.update { it.copy(error = authError.message) }
        }
    }

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onCaptchaChange(v: String) = _state.update { it.copy(captcha = v, error = null) }
    fun onRememberMeChange(v: Boolean) {
        val actual = authRepo.updateRememberMe(v)
        _state.update {
            it.copy(
                rememberMe = actual,
                error = if (v && !actual) "当前设备安全存储不可用，已关闭自动登录" else null,
            )
        }
    }

    fun submit() {
        val cur = _state.value
        if (cur.isLoading) return
        if (cur.username.isBlank() || cur.password.isBlank()) {
            _state.update { it.copy(error = "请输入学号和密码") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepo.login(cur.username, cur.password, cur.captcha)
                _state.update { it.copy(isLoading = false, success = true, password = "") }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.toUserMessage("登录失败，请稍后再试"),
                    )
                }
            }
        }
    }

    /**
     * 「使用已保存的密码登录」一键重登。走手动登录通道（不受自动通道节流阻挡、驱动 Loading/LoggedIn），
     * 成功后与手输登录同样置 success 触发导航。失败（含凭证被并发清掉）落 error 文案，用户可改手输。
     */
    fun submitSaved() {
        val cur = _state.value
        if (cur.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepo.loginWithSavedCredentials()
                _state.update { it.copy(isLoading = false, success = true, password = "") }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.toUserMessage("自动登录失败，请手动输入密码"),
                        // 凭证可能已被清（密码已改场景）——重新查一次决定还要不要显示该按钮
                        hasSavedCredentials = authRepo.hasSavedCredentials(),
                    )
                }
            }
        }
    }

    /**
     * 一次性消费"登录成功但有衍生异常"的告警（目前只有"keystore 写失败 → rememberMe 被关"
     * 一种）。LoginScreen 在 success 触发导航前调用，用 Toast 显示——snackbar 在导航瞬间
     * 会被吃掉。
     */
    fun consumeLoginWarning(): String? = authRepo.consumeLastLoginWarning()
}
