package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.ui.model.ListConfig
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import kotlinx.coroutines.launch

/**
 * 通用受管列表 ViewModel：列表 + 新建 + 删除 + 启停，由 [ListConfig] 驱动。
 */
class ManagedListViewModel(
    private val repo: WafRepository,
    private val config: ListConfig
) : ViewModel() {

    var items by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)
    var keyword by mutableStateOf("")

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val params = HashMap(config.listParams)
                if (config.keywordSearch) params["keyword"] = keyword
                val r = repo.safeCall(config.listPath, params)
                val map = r.asMap()
                items = when (val listRaw = map["list"] ?: map["data"]) {
                    is List<*> -> listRaw.asMapList()
                    else -> emptyList()
                }
                total = map["total"].asString().toIntOrNull() ?: items.size
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun create(values: Map<String, String>, onResult: (String?) -> Unit) {
        val path = config.createPath ?: return
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.createExtraParams)
                for (f in config.createFields) {
                    params[f.key] = values[f.key] ?: f.defaultValue
                }
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun delete(item: Map<String, Any?>, onResult: (String?) -> Unit) {
        val path = config.deletePath ?: return
        val id = config.deleteItemIdKeys.firstNotNullOfOrNull { item[it]?.asString()?.takeIf { s -> s.isNotBlank() } }
        if (id == null) {
            onResult("无法确定该条目的唯一标识")
            return
        }
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.deleteExtraParams)
                params[config.deleteParamKey] = if (config.deleteParamIsArray) listOf(id) else id
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun toggle(item: Map<String, Any?>, onResult: (String?) -> Unit) {
        val path = config.togglePath ?: return
        val currentlyOn = readToggleState(item)
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.toggleExtraParams)
                for ((paramKey, fieldKey) in config.toggleItemParams) {
                    params[paramKey] = item[fieldKey]
                }
                params[config.toggleValueKey] =
                    if (config.toggleValueBool) !currentlyOn else (if (currentlyOn) 0 else 1)
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    /** 读取条目开关状态，兼容布尔 / 数字 / 字符串三种后端写法。 */
    private fun readToggleState(item: Map<String, Any?>): Boolean {
        return when (val v = item[config.toggleStateKey]) {
            null -> false
            is Boolean -> v
            is Number -> v.toInt() == 1
            else -> v.asString() == "1" || v.asString().equals("true", true)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun List<*>.asMapList(): List<Map<String, Any?>> =
    mapNotNull { if (it is Map<*, *>) it as Map<String, Any?> else null }
