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
 * 告警通知配置。
 *
 * notification/list 返回顶层数组，元素形如：
 * { name, type(email/dingding/feishu/weixin), is_configured, config:{...} }
 * - email  config: { email, host, port, password, receivers[] }
 * - 其余   config: { url }
 * notification/update -> { type, config }；notification/clear -> { type }
 */
class NotificationsViewModel(private val repo: WafRepository) : ViewModel() {

    var channels by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("notification/list", emptyMap())
                @Suppress("UNCHECKED_CAST")
                channels = (r.res as? List<*>)
                    ?.mapNotNull { if (it is Map<*, *>) it as Map<String, Any?> else null }
                    ?: emptyList()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun save(type: String, config: Map<String, Any?>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            message = null
            try {
                val r = repo.safeCall("notification/update", mapOf("type" to type, "config" to config))
                message = if (r.isSuccess) "保存成功" else r.errorMessage()
                onResult(if (r.isSuccess) null else r.errorMessage())
                load()
            } catch (e: Exception) {
                message = e.message
                onResult(e.message)
            }
        }
    }

    fun clear(type: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            message = null
            try {
                val r = repo.safeCall("notification/clear", mapOf("type" to type))
                message = if (r.isSuccess) "已清除配置" else r.errorMessage()
                onResult(if (r.isSuccess) null else r.errorMessage())
                load()
            } catch (e: Exception) {
                message = e.message
                onResult(e.message)
            }
        }
    }
}
