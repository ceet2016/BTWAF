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

/**
 * IP 分组管理（对应 aawaf 后端 inset 模块）。
 *
 * 源码核对（modules/ip_group.go）：
 *  - inset/ip_list      无参，返回 { 组名: [ip, ...] }（顶层 Map）
 *  - inset/ip_name_list 无参，返回 [ 组名, ... ]（顶层数组）
 *  - inset/set_ip_group  { name: string, data: string[] }  —— data 必须是数组，名称已存在会失败
 *  - inset/edit_ip_group { name: string, data: string[] }  —— 存在则覆盖
 *  - inset/del_ip_group  { name: string }  —— 用名称而非 id；若已被黑白名单引用会拒绝删除
 *  - inset/get_by_name_ip { name: string }  —— 返回该组内的 IP 列表
 */
class IpGroupViewModel(private val repo: WafRepository) : ViewModel() {

    /** 组名 -> IP 列表 */
    var groups by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    val groupCount: Int get() = groups.size
    val ipCount: Int get() = groups.values.sumOf { it.size }

    /** 组名按 IP 数量降序，业务上更有参考价值。 */
    val sortedNames: List<String>
        get() = groups.entries.sortedByDescending { it.value.size }.map { it.key }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("inset/ip_list", emptyMap())
                val raw = r.asMap()
                groups = raw.mapValues { (_, v) -> v.asList().map { it.asString() } }
                    .filterKeys { it.isNotBlank() }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 新建分组（名称重复会失败）。 */
    fun create(name: String, ips: List<String>, onResult: (String?) -> Unit) =
        save("inset/set_ip_group", name, ips, onResult)

    /** 覆盖已有分组。 */
    fun update(name: String, ips: List<String>, onResult: (String?) -> Unit) =
        save("inset/edit_ip_group", name, ips, onResult)

    private fun save(path: String, name: String, ips: List<String>, onResult: (String?) -> Unit) {
        val n = name.trim()
        if (n.isBlank()) {
            onResult("分组名称不能为空")
            return
        }
        if (ips.isEmpty()) {
            onResult("至少需要一个 IP")
            return
        }
        viewModelScope.launch {
            saving = true
            error = null
            try {
                val r = repo.safeCall(path, mapOf("name" to n, "data" to ips))
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = if (path.endsWith("set_ip_group")) "分组「$n」已创建" else "分组「$n」已更新"
                    load()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    fun delete(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            saving = true
            error = null
            try {
                val r = repo.safeCall("inset/del_ip_group", mapOf("name" to name))
                val err = if (r.isSuccess) null else r.errorMessage()
                onResult(err)
                if (r.isSuccess) {
                    message = "分组「$name」已删除"
                    load()
                }
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    fun consumeMessage() {
        message = null
    }
}
