package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asMap
import kotlinx.coroutines.launch

/**
 * 系统设置。
 *
 * config/get_config 返回结构为 { config:{...}, basic_auth:{...}, apiinfo:{...}, port, syslog, ... }，
 * 因此本 ViewModel 拆分为三段展示；写参数名严格对齐源码 modules/config.go：
 *  - set_title        -> { title, logo }
 *  - set_domain       -> { accept_domain }
 *  - set_timeout      -> { session_timeout }
 *  - set_language     -> { lan }
 *  - set_ip           -> { accept_ip }  （逗号分隔字符串，非数组）
 *  - set_pwd          -> { password_complexity } （0/1，密码复杂度开关）
 *  - set_open_api     -> { type, limit_addr }  （type:1重置Token 2开关 3限制地址；limit_addr 以 \n 分隔）
 * 改用户密码走 user/update_profile -> { password }。
 */
class SettingsViewModel(private val repo: WafRepository) : ViewModel() {

    /** config/get_config 的原始返回 */
    var raw by mutableStateOf<Map<String, Any?>>(emptyMap())
    /** 其中的 config 节点 */
    var config by mutableStateOf<Map<String, Any?>>(emptyMap())
    var basicAuth by mutableStateOf<Map<String, Any?>>(emptyMap())
    var apiInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var port by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                raw = repo.safeCall("config/get_config", emptyMap()).asMap()
                config = raw["config"].asMap()
                basicAuth = raw["basic_auth"].asMap()
                apiInfo = raw["apiinfo"].asMap()
                port = raw["port"]?.toString() ?: ""
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun reload() = load()

    private fun set(path: String, params: Map<String, Any?>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            message = null
            try {
                val r = repo.safeCall(path, params)
                message = if (r.isSuccess) "操作成功" else r.errorMessage()
                onResult(if (r.isSuccess) null else r.errorMessage())
                load()
            } catch (e: Exception) {
                message = e.message
                onResult(e.message)
            }
        }
    }

    /** 修改账号（用户名/密码）。密码需满足复杂度要求，否则服务端会报错。 */
    fun updatePassword(newPwd: String, onResult: (String?) -> Unit) =
        set("user/update_profile", mapOf("password" to newPwd), onResult)

    fun updateUsername(name: String, onResult: (String?) -> Unit) =
        set("user/update_profile", mapOf("username" to name), onResult)

    fun setPasswordComplexity(on: Boolean, onResult: (String?) -> Unit) =
        set("config/set_pwd", mapOf("password_complexity" to if (on) 1 else 0), onResult)

    fun setTitle(title: String, onResult: (String?) -> Unit) =
        set("config/set_title", mapOf("title" to title, "logo" to (config["logo"] ?: "")), onResult)

    fun setDomain(domain: String, onResult: (String?) -> Unit) =
        set("config/set_domain", mapOf("accept_domain" to domain), onResult)

    fun setAcceptIp(ips: List<String>, onResult: (String?) -> Unit) =
        set("config/set_ip", mapOf("accept_ip" to ips.joinToString(",")), onResult)

    fun setLanguage(lang: String, onResult: (String?) -> Unit) =
        set("config/set_language", mapOf("lan" to lang), onResult)

    fun setSessionTimeout(seconds: String, onResult: (String?) -> Unit) =
        set("config/set_timeout", mapOf("session_timeout" to seconds.toIntOrNull()), onResult)

    fun setHelp(open: Boolean, onResult: (String?) -> Unit) =
        set("config/set_help", mapOf("open" to if (open) 1 else 0), onResult)

    /** type: 1 重置 Token，2 开关 API，3 设置限制地址（limit_addr 以 \n 分隔） */
    fun setOpenApi(type: Int, limitAddr: String = "", onResult: (String?) -> Unit) =
        set("config/set_open_api", mapOf("type" to type, "limit_addr" to limitAddr), onResult)

    fun setAdminPath(path: String, onResult: (String?) -> Unit) =
        set("config/set_admin_path", mapOf("admin_path" to path.trimStart('/')), onResult)

    fun setPort(p: String, onResult: (String?) -> Unit) =
        set("config/set_port", mapOf("port" to p.toIntOrNull()), onResult)

    fun setBasicAuth(open: Boolean, user: String, pwd: String, onResult: (String?) -> Unit) =
        set(
            "config/set_basic_auth",
            mapOf("open" to if (open) 1 else 0, "basic_user" to user, "basic_pwd" to pwd),
            onResult
        )
}
