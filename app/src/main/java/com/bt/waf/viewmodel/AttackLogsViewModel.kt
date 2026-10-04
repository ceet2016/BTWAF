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
import com.bt.waf.util.i
import kotlinx.coroutines.launch

/**
 * 拦截日志（report/attack_report_log，aawaf 用 public.SimplePage 分页，
 * 必须传 query_data 字符串，keyword 可选，clear=1 时清空）。
 */
class AttackLogsViewModel(private val repo: WafRepository) : ViewModel() {

    var logs by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)
    var keyword by mutableStateOf("")
    var dateRange by mutableStateOf("")

    private val pageSize = 20
    val hasMore: Boolean get() = logs.size < total

    /** 今日拦截数（按日志时间戳落在今天的条数近似）。 */
    val todayCount: Int
        get() {
            val start = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }.timeInMillis / 1000
            return logs.count { (it["time"] as? Number)?.toLong()?.let { t -> t >= start } == true }
        }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "report/attack_report_log",
                    mapOf(
                        "p" to 1, "p_size" to pageSize,
                        "keyword" to keyword,
                        "query_data" to dateRange
                    )
                )
                val map = r.asMap()
                logs = map["list"].asList()
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

    fun clear(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "report/attack_report_log",
                    mapOf("clear" to 1, "query_data" to dateRange)
                )
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }
}
