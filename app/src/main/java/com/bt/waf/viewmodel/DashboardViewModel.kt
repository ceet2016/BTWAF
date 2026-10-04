package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asMap
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: WafRepository) : ViewModel() {

    var countInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var infos by mutableStateOf<Map<String, Any?>>(emptyMap())
    var attackMap by mutableStateOf<Map<String, Any?>>(emptyMap())
    var sysInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var statusMap by mutableStateOf<Map<String, Any?>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                countInfo = repo.safeCall("overview/count_info", emptyMap()).asMap()
                infos = repo.safeCall("overview/infos", emptyMap()).asMap()
                attackMap = repo.safeCall("overview/attack_map", emptyMap()).asMap()
                sysInfo = repo.safeCall("sys/get_system_info", emptyMap()).asMap()
                statusMap = runCatching { repo.safeCall("overview/get_status_map", emptyMap()).asMap() }.getOrDefault(emptyMap())
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
}
