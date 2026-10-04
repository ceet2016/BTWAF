package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asMap
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 系统信息（sys/get_system_info → types.SystemInfo），
 * 顺带取 user/latest_version 与 config/title 做版本展示。
 */
class SysInfoViewModel(private val repo: WafRepository) : ViewModel() {

    var info by mutableStateOf<Map<String, Any?>>(emptyMap())
    var version by mutableStateOf("")
    var latest by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val dInfo = async { runCatching { repo.safeCall("sys/get_system_info", emptyMap()).asMap() } }
                val dVer = async {
                    runCatching { repo.safeCall("user/latest_version", emptyMap()).asMap() }
                }
                info = dInfo.await().getOrDefault(emptyMap())
                val verMap = dVer.await().getOrDefault(emptyMap())
                version = verMap["cur_version"]?.toString().orEmpty().ifBlank {
                    verMap["version"]?.toString().orEmpty()
                }
                latest = verMap["latest_version"]?.toString().orEmpty()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
}
