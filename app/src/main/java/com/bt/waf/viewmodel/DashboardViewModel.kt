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
 * 概览页数据源（全部对照 aawaf modules/overview.go 核对）：
 * - overview/count_info      实时统计 + 系统信息
 * - overview/infos           最近拦截 + 近两日分钟级请求趋势
 * - overview/attack_map      近 30 日按国家聚合的攻击量
 * - overview/nginx_count     QPS / 流量 / 回源耗时
 * - overview/back_to_source  回源耗时与请求趋势
 * - sys/get_system_info      主机 / CPU / 内存 / 磁盘 / 网卡
 */
class DashboardViewModel(private val repo: WafRepository) : ViewModel() {

    var countInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var infos by mutableStateOf<Map<String, Any?>>(emptyMap())
    var attackMap by mutableStateOf<Map<String, Any?>>(emptyMap())
    var sysInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    var nginxCount by mutableStateOf<Map<String, Any?>>(emptyMap())
    var backToSource by mutableStateOf<Map<String, Any?>>(emptyMap())
    var attackReport by mutableStateOf<Map<String, Any?>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                // 并发拉取，单个接口失败不影响整页
                val dCount = async { runCatching { repo.safeCall("overview/count_info", emptyMap()).asMap() } }
                val dInfos = async { runCatching { repo.safeCall("overview/infos", emptyMap()).asMap() } }
                val dMap = async { runCatching { repo.safeCall("overview/attack_map", emptyMap()).asMap() } }
                val dSys = async { runCatching { repo.safeCall("sys/get_system_info", emptyMap()).asMap() } }
                val dNginx = async { runCatching { repo.safeCall("overview/nginx_count", emptyMap()).asMap() } }
                val dBack = async { runCatching { repo.safeCall("overview/back_to_source", emptyMap()).asMap() } }
                val dReport = async {
                    runCatching { repo.safeCall("report/attack_report_count", mapOf("query_data" to "")).asMap() }
                }

                val rCount = dCount.await()
                val rInfos = dInfos.await()
                val rMap = dMap.await()
                val rSys = dSys.await()

                countInfo = rCount.getOrDefault(emptyMap())
                infos = rInfos.getOrDefault(emptyMap())
                attackMap = rMap.getOrDefault(emptyMap())
                sysInfo = rSys.getOrDefault(emptyMap())
                nginxCount = dNginx.await().getOrDefault(emptyMap())
                backToSource = dBack.await().getOrDefault(emptyMap())
                attackReport = dReport.await().getOrDefault(emptyMap())

                val failures = mutableListOf<String>()
                if (rCount.isFailure) failures += "实时统计"
                if (rInfos.isFailure) failures += "拦截明细"
                if (rMap.isFailure) failures += "攻击地图"
                if (rSys.isFailure) failures += "系统信息"
                if (failures.isNotEmpty() && countInfo.isEmpty() && sysInfo.isEmpty()) {
                    error = "部分数据加载失败：${failures.joinToString("、")}"
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
}
