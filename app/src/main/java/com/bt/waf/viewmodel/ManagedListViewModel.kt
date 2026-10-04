package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.ui.model.ListConfig
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import kotlinx.coroutines.launch

/**
 * 通用受管列表 ViewModel：分页列表 + 搜索 + 日期筛选 + 新建 + 删除 + 启停 + 清空，
 * 全部由 [ListConfig] 驱动，无需为每个模块单独写 ViewModel。
 */
class ManagedListViewModel(
    private val repo: WafRepository,
    val config: ListConfig
) : ViewModel() {

    var items by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var total by mutableStateOf(0)
    var page by mutableStateOf(1)
    var keyword by mutableStateOf("")
    var dateRange by mutableStateOf("")

    private val pageSize = 20

    val hasMore: Boolean get() = items.size < total

    fun load() {
        page = 1
        fetch(1, replace = true)
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        fetch(page + 1, replace = false)
    }

    private fun fetch(targetPage: Int, replace: Boolean) {
        viewModelScope.launch {
            if (replace) loading = true else loadingMore = true
            error = null
            try {
                val params = HashMap<String, Any?>(config.listParams)
                params["p"] = targetPage
                params["p_size"] = pageSize
                if (config.keywordSearch) params[config.keywordParam] = keyword
                if (config.dateFilter) params["query_data"] = dateRange

                val r = repo.safeCall(config.listPath, params)
                val batch: List<Map<String, Any?>> = if (config.rootArrayResponse) {
                    r.res.asList()
                } else {
                    val map = r.asMap()
                    val raw = map["list"] ?: map["data"] ?: map["rows"]
                    val parsed = raw.asList()
                    total = map["total"]?.asString()?.toIntOrNull()
                        ?: map["count"]?.asString()?.toIntOrNull()
                        ?: parsed.size
                    parsed
                }
                if (config.rootArrayResponse) total = batch.size
                items = if (replace) batch else items + batch
                page = targetPage
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    fun create(values: Map<String, String>, onResult: (String?) -> Unit) {
        val path = config.createPath ?: return
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.createExtraParams)
                for (f in config.createFields) {
                    val v = values[f.key] ?: f.defaultValue
                    // 多行字段按换行拆成数组提交，其余按字符串
                    params[f.key] = if (f.multiline) {
                        v.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        v
                    }
                }
                val r = repo.safeCall(path, params)
                val msg = r.asMap()["msg"]?.asString()?.takeIf { it.isNotBlank() }
                    ?: r.res?.asString()?.takeIf { r.res is String }
                    ?: ""
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) {
                    message = msg.ifBlank { "操作成功" }
                    load()
                }
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    /** 删除单条。[index] 为该项在当前列表中的位置（idFromPosition 模式必需）。 */
    fun delete(index: Int, onResult: (String?) -> Unit) {
        val path = config.deletePath ?: return
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.deleteExtraParams)
                val id: Any = if (config.idFromPosition) {
                    index
                } else {
                    val item = items.getOrNull(index) ?: return@launch
                    config.deleteItemIdKeys.firstNotNullOfOrNull {
                        items[index][it]?.asString()?.takeIf { s -> s.isNotBlank() }
                    } ?: return@launch onResult("无法确定该条目的唯一标识")
                }
                params[config.deleteParamKey] = if (config.deleteParamIsArray) listOf(id) else id
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun toggle(index: Int, onResult: (String?) -> Unit) {
        val path = config.togglePath ?: return
        val item = items.getOrNull(index) ?: return
        val currentlyOn = readToggleState(item)
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.toggleExtraParams)
                for ((paramKey, fieldKey) in config.toggleItemParams) {
                    params[paramKey] = if (fieldKey == "__pos__") index else item[fieldKey]
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

    /** 一键清空（日志类模块）。 */
    fun clear(onResult: (String?) -> Unit) {
        val path = config.clearPath ?: return
        viewModelScope.launch {
            try {
                val params = HashMap<String, Any?>(config.clearExtraParams)
                if (config.dateFilter) params["query_data"] = dateRange
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun consumeMessage() {
        message = null
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
