package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.l
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 攻击报表（对照 aawaf modules/attack_report.go）：
 * - report/attack_report_count     type/ip/uri 三张聚合表（query_data 为空即近 30 日）
 * - report/attack_report_ip_log    blocking_ip 封禁记录分页
 * - report/get_rule_hit_list       规则命中日志
 * - report/get_rule_hit_type_list  命中类型开关（types.RuleHitType）
 * - report/set_hit_type            保存命中类型开关
 * - report/unset_ip / unset_all_ip 解封
 * - report/delete_rule_hit_list    清空命中日志
 */
class ReportViewModel(private val repo: WafRepository) : ViewModel() {

    var typeTop by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var ipTop by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var uriTop by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var ipList by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var ruleHit by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var hitTypes by mutableStateOf<Map<String, Any?>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    /** 当前仍处于封禁状态的 IP（blocking_time > 0）。 */
    val blockedList: List<Map<String, Any?>> get() = ipList.filter { it.l("blocking_time") > 0 }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val dCount = async { runCatching { repo.safeCall("report/attack_report_count", mapOf("query_data" to "")).asMap() } }
                val dIp = async {
                    runCatching {
                        repo.safeCall(
                            "report/attack_report_ip_log",
                            mapOf("p" to 1, "p_size" to 100, "keyword" to "", "query_data" to "")
                        ).asMap()
                    }
                }
                val dHit = async {
                    runCatching {
                        repo.safeCall(
                            "report/get_rule_hit_list",
                            mapOf("p" to 1, "p_size" to 100, "keyword" to "", "filter" to "")
                        ).asMap()
                    }
                }
                val dTypes = async { runCatching { repo.safeCall("report/get_rule_hit_type_list", emptyMap()).asMap() } }

                val count = dCount.await().getOrDefault(emptyMap())
                typeTop = count["type"].asList().sortedByDescending { it.l("type_total") }
                ipTop = count["ip"].asList().sortedByDescending { it.l("visits") }
                uriTop = count["uri"].asList().sortedByDescending { it.l("uri_total") }

                val ipRes = dIp.await().getOrDefault(emptyMap())
                ipList = ipRes["list"].asList()

                val hitRes = dHit.await().getOrDefault(emptyMap())
                ruleHit = hitRes["list"].asList()

                hitTypes = dTypes.await().getOrDefault(emptyMap())
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun unsetIp(id: Double, ip: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("report/unset_ip", mapOf("id" to id, "ip" to ip))
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun unsetAllIp(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("report/unset_all_ip", emptyMap())
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun setHitType(key: String, value: Boolean) {
        val snapshot = hitTypes
        hitTypes = snapshot + (key to value)
        viewModelScope.launch {
            try {
                val payload = HashMap<String, Any?>()
                snapshot.forEach { (k, v) -> payload[k] = v }
                payload[key] = value
                val r = repo.safeCall("report/set_hit_type", payload)
                if (!r.isSuccess) {
                    hitTypes = snapshot
                    error = r.errorMessage()
                }
            } catch (e: Exception) {
                hitTypes = snapshot
                error = e.message
            }
        }
    }

    fun clearRuleHit() {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("report/delete_rule_hit_list", emptyMap())
                if (r.isSuccess) {
                    message = "命中日志已清空"
                    load()
                } else {
                    error = r.errorMessage()
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun consumeMessage() {
        message = null
    }
}
