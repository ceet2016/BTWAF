package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import kotlinx.coroutines.launch

/**
 * 防护网站管理。
 *
 * create/modify 使用 aawaf 的 types.SiteJson 结构（源码 types/waf_master.go）：
 * { domain_list, site_name, server: { listen_port, server_name, upstream:{ server:[{address}] }, ssl:{is_ssl} } }
 * 删除使用 site_id；站点日志需 site_id + types；SSL 列表由 get_all_ssl 返回顶层数组。
 */
class SitesViewModel(private val repo: WafRepository) : ViewModel() {

    var sites by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "wafmastersite/get_site_list",
                    mapOf("p" to 1, "p_size" to 50, "site_name" to "")
                )
                val map = r.asMap()
                sites = toMapList(map["list"])
                total = map["total"].asString().toIntOrNull() ?: sites.size
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 按监测到的字段取站点 ID（不同版本字段名可能为 site_id / id / name）。 */
    fun siteId(site: Map<String, Any?>): String =
        listOf("site_id", "id", "name")
            .firstNotNullOfOrNull { site[it]?.asString()?.takeIf { s -> s.isNotBlank() } } ?: ""

    fun create(name: String, port: String, source: String, onResult: (String?) -> Unit) {
        val domain = name.trim()
        val upstream = source.trim().let { if (it.contains(":") || it.startsWith("http")) it else "$it:$port" }
            .removePrefix("http://").removePrefix("https://")
        val payload = mapOf<String, Any?>(
            "site_name" to domain,
            "domain_list" to listOf(domain),
            "is_cdn" to 0,
            "server" to mapOf<String, Any?>(
                "listen_port" to listOf(port.trim()),
                "listen_ssl_port" to emptyList<String>(),
                "listen_ipv6" to 0,
                "server_name" to listOf(domain),
                "index" to emptyList<String>(),
                "ssl" to mapOf("is_ssl" to 0),
                "upstream" to mapOf<String, Any?>(
                    "name" to domain,
                    "polling_algorithm" to "ip_hash",
                    "source_protocol" to "http",
                    "server" to listOf(
                        mapOf<String, Any?>(
                            "address" to upstream,
                            "weight" to "1",
                            "max_fails" to "3",
                            "fail_timeout" to "10",
                            "status" to "1",
                            "ps" to ""
                        )
                    )
                ),
                "location_list" to emptyMap<String, Any?>()
            )
        )
        act("wafmastersite/create_site", payload, onResult, ::load)
    }

    /** 启用/停用站点：modify_site + types=startStopSite。 */
    fun setStatus(site: Map<String, Any?>, open: Boolean, onResult: (String?) -> Unit) =
        act(
            "wafmastersite/modify_site",
            mapOf("types" to "startStopSite", "site_id" to siteId(site), "status" to if (open) 1 else 0),
            onResult,
            ::load
        )

    fun delete(site: Map<String, Any?>, onResult: (String?) -> Unit) =
        act("wafmastersite/delete_site", mapOf("site_id" to siteId(site)), onResult, ::load)

    fun getLog(siteId: String, onResult: (List<Map<String, Any?>>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "wafmastersite/get_site_log",
                    mapOf("site_id" to siteId, "types" to "all", "p" to 1, "p_size" to 50)
                )
                onResult(toMapList(r.asMap()["list"]), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    fun clearLog(siteId: String, onResult: (String?) -> Unit) =
        act("wafmastersite/clear_site_log", mapOf("site_id" to siteId), onResult, {})

    /** SSL 证书列表（顶层数组，无需站点参数）。 */
    fun getSsl(onResult: (List<Map<String, Any?>>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("wafmastersite/get_all_ssl", emptyMap())
                onResult(toMapList(r.res), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    fun deploySsl(params: Map<String, Any?>, onResult: (String?) -> Unit) =
        act("wafmastersite/deploy_ssl", params, onResult, {})

    fun deleteSsl(params: Map<String, Any?>, onResult: (String?) -> Unit) =
        act("wafmastersite/delete_ssl", params, onResult, {})

    private fun act(path: String, params: Map<String, Any?>, onResult: (String?) -> Unit, after: () -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) after()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toMapList(raw: Any?): List<Map<String, Any?>> =
        (raw as? List<*>)?.mapNotNull { if (it is Map<*, *>) it as Map<String, Any?> else null } ?: emptyList()
}
