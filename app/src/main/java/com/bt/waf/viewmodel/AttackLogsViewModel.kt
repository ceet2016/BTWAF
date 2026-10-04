package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import kotlinx.coroutines.launch

class AttackLogsViewModel(private val repo: WafRepository) : ViewModel() {

    var logs by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)
    var keyword by mutableStateOf("")

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "report/attack_report_log",
                    mapOf("p" to 1, "p_size" to 50, "keyword" to keyword, "query_data" to "", "filters" to emptyList<Any>())
                )
                val map = r.asMap()
                logs = (map["list"] ?: emptyList<Any>()).asList()
                total = map["total"].asString().toIntOrNull() ?: logs.size
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun info(id: Any?, onResult: (Map<String, Any?>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("report/attack_report_log_info", mapOf("id" to id))
                onResult(r.asMap(), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    fun ccList(onResult: (List<Map<String, Any?>>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("report/cc_list", mapOf("p" to 1, "p_size" to 50))
                onResult((r.asMap()["list"] ?: emptyList<Any>()).asList(), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }
}
