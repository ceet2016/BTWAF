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

    init {
        if (repo.authStore.webUrl.isNotBlank()) webUrl = repo.authStore.webUrl
        if (repo.authStore.username.isNotBlank()) username = repo.authStore.username
        trust = repo.authStore.trustSelfSigned
    }

    fun login(onSuccess: () -> Unit) {
        if (webUrl.isBlank() || username.isBlank() || password.isBlank()) {
            error = "请填写服务器地址、账号与密码"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                repo.authStore.username = username
                repo.applyConnection(webUrl, trust)
                repo.login(username, password)
                    .onSuccess { onSuccess() }
                    .onFailure { error = it.message ?: "登录失败" }
            } catch (e: Exception) {
                error = e.message ?: "登录失败"
            } finally {
                loading = false
            }
        }
    }
}
