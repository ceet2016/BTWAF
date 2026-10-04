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
import com.bt.waf.util.pickString
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 内容替换（关键词替换）规则管理，对应 aawaf 后端 replace 模块。
 *
 * 源码核对（modules/replacement.go）：
 *  - replace/get_rules      { site_name, site_id } -> 顶层数组，每项含 key/match_type/match_value/keyward/re_value/hit/timestamp
 *  - replace/add_rules      { site_name, site_id, open: bool }  仅开关整站替换总开关
 *  - replace/add_rules_info { site_name, site_id, match_type, keyward, re_value } 新增单条规则
 *  - replace/update_rules   { site_name, site_id, match_type, key, keyward, re_value } 更新单条规则
 *  - replace/delete_rules   { site_name, site_id, match_type, key } 删除单条规则
 *  - replace/clear_hit      { site_id, key, type } 清空命中数（type: "0" 站点级 "1" 规则级）
 * 注意：match_type 只能是 site_rules（全局）或 uri_rules（按 URI）。
 */
class ReplaceViewModel(private val repo: WafRepository) : ViewModel() {

    var sites by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var selectedSite by mutableStateOf<Map<String, Any?>?>(null)
    var rules by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    val siteId: String get() = selectedSite?.let { pickString(it, "site_id", "id") } ?: ""
    val siteName: String get() = selectedSite?.let { pickString(it, "site_name", "name", "domain") } ?: ""

    fun loadSites() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "wafmastersite/get_site_list",
                    mapOf("p" to 1, "p_size" to 100, "site_name" to "")
                )
                val list = r.asMap()["list"].asList()
                sites = list
                if (selectedSite == null && list.isNotEmpty()) {
                    selectedSite = list.first()
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 切换站点：并发拉取该站点的替换开关与规则列表。 */
    fun selectSite(site: Map<String, Any?>) {
        selectedSite = site
        loadRules()
    }

    fun loadRules() {
        val s = selectedSite ?: return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val dRules = async {
                    runCatching {
                        repo.safeCall(
                            "replace/get_rules",
                            mapOf(
                                "site_name" to pickString(s, "site_name", "name", "domain"),
                                "site_id" to pickString(s, "site_id", "id")
                            )
                        )
                    }
                }
                val r = dRules.await()
                if (r.isFailure) {
                    error = r.exceptionOrNull()?.message
                    rules = emptyList()
                } else {
                    // get_rules 成功时 res 是顶层数组
                    rules = r.getOrNull()?.res.asList()
                        .ifEmpty { r.getOrNull()?.asMap()?.get("list").asList() }
                }
            } finally {
                loading = false
            }
        }
    }

    /** 整站替换总开关。 */
    fun setOpen(open: Boolean, onResult: (String?) -> Unit) {
        val s = selectedSite ?: return
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall(
                    "replace/add_rules",
                    mapOf(
                        "site_name" to pickString(s, "site_name", "name", "domain"),
                        "site_id" to pickString(s, "site_id", "id"),
                        "open" to open
                    )
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = if (open) "已开启内容替换" else "已关闭内容替换"
                    loadRules()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    /** 新增规则。matchType: site_rules / uri_rules。 */
    fun addRule(
        matchType: String,
        keyward: String,
        reValue: String,
        onResult: (String?) -> Unit
    ) {
        val s = selectedSite ?: return
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall(
                    "replace/add_rules_info",
                    mapOf(
                        "site_name" to pickString(s, "site_name", "name", "domain"),
                        "site_id" to pickString(s, "site_id", "id"),
                        "match_type" to matchType,
                        "keyward" to keyward,
                        "re_value" to reValue
                    )
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = "规则已添加"
                    loadRules()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    fun updateRule(rule: Map<String, Any?>, keyward: String, reValue: String, onResult: (String?) -> Unit) {
        val s = selectedSite ?: return
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall(
                    "replace/update_rules",
                    mapOf(
                        "site_name" to pickString(s, "site_name", "name", "domain"),
                        "site_id" to pickString(s, "site_id", "id"),
                        "match_type" to rule["match_type"].asString().ifBlank { "site_rules" },
                        "key" to rule["key"].asString(),
                        "keyward" to keyward,
                        "re_value" to reValue
                    )
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = "规则已更新"
                    loadRules()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    fun deleteRule(rule: Map<String, Any?>, onResult: (String?) -> Unit) {
        val s = selectedSite ?: return
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall(
                    "replace/delete_rules",
                    mapOf(
                        "site_name" to pickString(s, "site_name", "name", "domain"),
                        "site_id" to pickString(s, "site_id", "id"),
                        "match_type" to rule["match_type"].asString().ifBlank { "site_rules" },
                        "key" to rule["key"].asString()
                    )
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = "规则已删除"
                    loadRules()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    /** 清空命中统计。type: "0" 站点级 / "1" 单条规则级。 */
    fun clearHit(rule: Map<String, Any?>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "replace/clear_hit",
                    mapOf(
                        "site_id" to siteId,
                        "key" to rule["key"].asString(),
                        "type" to "1"
                    )
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) loadRules()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun consumeMessage() {
        message = null
    }
}
