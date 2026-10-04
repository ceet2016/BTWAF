package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import kotlinx.coroutines.launch

class AuthViewModel(private val repo: WafRepository) : ViewModel() {

    /** 该实例的网页版地址（含后台入口路径），便于首次使用。 */
    var webUrl by mutableStateOf("https://81.70.19.168:8379/d75e7a5401")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var trust by mutableStateOf(true)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** 图形验证码图片（data:image/png;base64,...）。 */
    var captchaImage by mutableStateOf<String?>(null)
    private var captchaId: String? = null

    /** 用户输入的验证码。 */
    var captchaInput by mutableStateOf("")

    init {
        if (repo.authStore.webUrl.isNotBlank()) webUrl = repo.authStore.webUrl
        if (repo.authStore.username.isNotBlank()) username = repo.authStore.username
        trust = repo.authStore.trustSelfSigned
    }

    /**
     * 服务端在同一 IP 登录失败后会强制要求图形验证码，
     * 缺 validate_code_id / validate_code 任一参数都返回 400，因此必须支持。
     */
    private fun probeCaptcha(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { repo.fetchValidateCode() }
                .onSuccess { pair ->
                    if (pair != null) {
                        captchaId = pair.first
                        captchaImage = pair.second
                        captchaInput = ""
                    }
                }
            onDone?.invoke()
        }
    }

    /** 主动刷新图形验证码（点图片可换一张）。 */
    fun refreshCaptcha() = probeCaptcha()

    /** 连接信息变化后调用，重新建立会话 Cookie 并同步验证码状态。 */
    fun syncConnection() = probeCaptcha()

    fun login(onSuccess: () -> Unit) {
        if (webUrl.isBlank() || username.isBlank() || password.isBlank()) {
            error = "请填写服务器地址、账号与密码"
            return
        }
        if (captchaImage != null && captchaInput.isBlank()) {
            error = "请输入图形验证码"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                repo.authStore.username = username
                repo.applyConnection(webUrl, trust)
                repo.login(username, password, captchaId, captchaInput.takeIf { it.isNotBlank() })
                    .onSuccess { onSuccess() }
                    .onFailure {
                        error = it.message ?: "登录失败"
                        // 失败后服务端通常已要求验证码，立即取一张新的
                        probeCaptcha()
                    }
            } catch (e: Exception) {
                error = e.message ?: "登录失败"
                probeCaptcha()
            } finally {
                loading = false
            }
        }
    }
}
