package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asMap
import kotlinx.coroutines.launch

class RulesViewModel(private val repo: WafRepository) : ViewModel() {

    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var lastResult by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** 通用动作：调用任意规则接口并返回错误信息（null 表示成功）。 */
    fun run(path: String, params: Map<String, Any?>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            loading = true
            message = null
            try {
                val r = repo.safeCall(path, params)
                lastResult = r.asMap()
                onResult(if (r.isSuccess) null else r.errorMessage())
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                loading = false
            }
        }
    }

    fun getGlobalRules(onResult: (Map<String, Any?>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("wafrules/get_global_rules", emptyMap())
                onResult(r.asMap(), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }
}
