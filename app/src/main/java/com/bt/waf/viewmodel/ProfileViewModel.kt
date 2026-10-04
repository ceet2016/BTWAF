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
 * 账号信息与运维操作。
 * - user/profile 返回 { id, username, create_time, version, admin_path, title, language, logo, ... }
 * - sys/get_system_info 返回 { host, cpu, mem, disk_list, net_io_list, loadavg }
 * - user/restart     重启控制台
 * - user/update      升级到最新版本（已是最新时返回 Success）
 */
class ProfileViewModel(private val repo: WafRepository) : ViewModel() {

    var profile by mutableStateOf<Map<String, Any?>>(emptyMap())
    var sysInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                profile = repo.safeCall("user/profile", emptyMap()).asMap()
            } catch (e: Exception) {
                error = e.message
            }
            try {
                sysInfo = repo.safeCall("sys/get_system_info", emptyMap()).asMap()
            } catch (e: Exception) {
                if (error == null) error = e.message
            }
            loading = false
        }
    }

    fun restart(onResult: (String?) -> Unit) = act("user/restart", emptyMap(), onResult)

    fun updateVersion(onResult: (String?) -> Unit) = act("user/update", emptyMap(), onResult)

    fun updateUsername(name: String, onResult: (String?) -> Unit) =
        act("user/update_profile", mapOf("username" to name), onResult, ::load)

    fun updatePassword(pwd: String, onResult: (String?) -> Unit) =
        act("user/update_profile", mapOf("password" to pwd), onResult)

    private fun act(
        path: String,
        params: Map<String, Any?>,
        onResult: (String?) -> Unit,
        after: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            message = null
            try {
                val r = repo.safeCall(path, params)
                val err = if (r.isSuccess) null else r.errorMessage()
                message = err ?: (r.res as? String)?.takeIf { it.isNotBlank() } ?: "操作成功"
                onResult(err)
                if (r.isSuccess) after?.invoke()
            } catch (e: Exception) {
                message = e.message
                onResult(e.message)
            }
        }
    }
}
