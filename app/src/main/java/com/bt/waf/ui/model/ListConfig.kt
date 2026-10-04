package com.bt.waf.ui.model

/**
 * 通用“受管列表”配置：用一个界面 + 一个 ViewModel 覆盖大量后台模块
 * （IP/URL/UA 黑白名单、操作日志、自定义规则、限速、人机验证、区域拦截等）。
 *
 * 约定（均来自 aawaf 源码核对结果）：
 * - limit 模块：列表项以 "index"（字符串）作为唯一标识；types 区分黑白（0=白，1=黑）；
 *   删除 limit/del_data 接收 index 数组 + types；启停 limit/off_* 接收 index + open + types。
 * - customize 模块：删除接收 ids 数组。
 * - manm 模块：删除接收 key 数组；启停 off_authtype 接收 key + open。
 * - speed 模块：按站点维度，启停 open_rule_status 接收 site_name + site_id + open(bool)。
 * - wafrules 区域规则：删除接收 region_id 数组；启停接收 region_id + status(bool)。
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val hint: String = "",
    val defaultValue: String = "",
    /** 下拉选项：展示文本 -> 提交值 */
    val options: List<Pair<String, String>>? = null
)

data class ListConfig(
    val key: String,
    val title: String,
    val listPath: String,
    val listParams: Map<String, Any?> = mapOf("p" to 1, "p_size" to 50),
    val titleKeys: List<String> = listOf(
        "site_name", "name", "ip", "url", "ua", "rule", "domain", "ps", "data", "id"
    ),
    val subtitleKeys: List<String> = listOf(
        "notes", "remark", "time", "add_time", "create_time", "expire",
        "open", "operator", "log_type", "content"
    ),
    val createPath: String? = null,
    val createFields: List<FieldSpec> = emptyList(),
    val createExtraParams: Map<String, Any?> = emptyMap(),

    val deletePath: String? = null,
    val deleteParamKey: String = "index",
    val deleteParamIsArray: Boolean = false,
    /** 依次尝试从列表项中取 id 的候选键，取到第一个非空值即用 */
    val deleteItemIdKeys: List<String> = listOf("index"),
    val deleteExtraParams: Map<String, Any?> = emptyMap(),

    val togglePath: String? = null,
    /** 状态值写入的参数名 */
    val toggleValueKey: String = "open",
    /** 状态值是否用布尔量（true/false），否则用 0/1 */
    val toggleValueBool: Boolean = false,
    /** 需要从列表项原样透传的参数：参数名 -> 列表项字段名 */
    val toggleItemParams: Map<String, String> = mapOf("index" to "index"),
    val toggleExtraParams: Map<String, Any?> = emptyMap(),
    /** 读取当前开关状态的列表项字段 */
    val toggleStateKey: String = "open",

    val keywordSearch: Boolean = false
)

object ManagedLists {
    private val matchOptions = listOf(
        "包含" to "0", "前缀匹配" to "1", "后缀匹配" to "2", "等于" to "3"
    )
    private val ipTypeOptions = listOf("IPv4" to "0", "IPv6" to "1")

    private fun ipConfig(key: String, title: String, types: Int) = ListConfig(
        key = key,
        title = title,
        listPath = "limit/get_ip",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        createPath = "limit/set_ip",
        createFields = listOf(
            FieldSpec("start", "起始 IP", hint = "必填"),
            FieldSpec("end", "结束 IP（可选）"),
            FieldSpec("notes", "备注"),
            FieldSpec("ip_type", "IP 类型", options = ipTypeOptions, defaultValue = "0")
        ),
        createExtraParams = mapOf("types" to types),
        deletePath = "limit/del_data",
        deleteParamKey = "index",
        deleteParamIsArray = true,
        deleteExtraParams = mapOf("types" to types),
        togglePath = "limit/off_ip",
        toggleItemParams = mapOf("index" to "index"),
        toggleExtraParams = mapOf("types" to types)
    )

    private fun urlConfig(key: String, title: String, types: Int) = ListConfig(
        key = key,
        title = title,
        listPath = "limit/get_url",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        createPath = "limit/set_url",
        createFields = listOf(
            FieldSpec("url", "URL / 路径", hint = "不能以 / 开头且不为根"),
            FieldSpec("type", "匹配方式", options = matchOptions, defaultValue = "0"),
            FieldSpec("param", "参数（可选）"),
            FieldSpec("notes", "备注")
        ),
        createExtraParams = mapOf("types" to types),
        deletePath = "limit/del_data",
        deleteParamKey = "index",
        deleteParamIsArray = true,
        deleteExtraParams = mapOf("types" to types),
        togglePath = "limit/off_url",
        toggleExtraParams = mapOf("types" to types)
    )

    private fun uaConfig(key: String, title: String, types: Int) = ListConfig(
        key = key,
        title = title,
        listPath = "limit/get_ua",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        createPath = "limit/set_ua",
        createFields = listOf(
            FieldSpec("data", "UA 字符串", hint = "必填"),
            FieldSpec("notes", "备注")
        ),
        createExtraParams = mapOf("types" to types),
        deletePath = "limit/del_data",
        deleteParamKey = "index",
        deleteParamIsArray = true,
        deleteExtraParams = mapOf("types" to types),
        togglePath = "limit/off_ua",
        toggleExtraParams = mapOf("types" to types)
    )

    val all: List<ListConfig> = listOf(
        ipConfig("ip_black", "IP 黑名单", 1),
        ipConfig("ip_white", "IP 白名单", 0),
        urlConfig("url_black", "URL 黑名单", 1),
        urlConfig("url_white", "URL 白名单", 0),
        uaConfig("ua_black", "UA 黑名单", 1),
        uaConfig("ua_white", "UA 白名单", 0),

        ListConfig(
            key = "opt_logs",
            title = "操作日志",
            listPath = "opt_log/list",
            listParams = mapOf("p" to 1, "p_size" to 50),
            titleKeys = listOf("content", "operator", "log_type", "id"),
            subtitleKeys = listOf("create_time", "uid", "id"),
            keywordSearch = true
        ),

        ListConfig(
            key = "customize",
            title = "自定义规则",
            listPath = "customize/list",
            listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "type" to 0),
            titleKeys = listOf("name", "root", "id"),
            subtitleKeys = listOf("status", "priority", "create_time", "execute_phase", "action"),
            deletePath = "customize/remove",
            deleteParamKey = "ids",
            deleteParamIsArray = true,
            deleteItemIdKeys = listOf("id"),
            keywordSearch = true
        ),

        ListConfig(
            key = "speed",
            title = "访问控制（站点限速）",
            listPath = "speed/get_list",
            listParams = mapOf("p" to 1, "p_size" to 50),
            titleKeys = listOf("site_name", "domain", "site_id"),
            subtitleKeys = listOf("open", "expire", "size", "site_id"),
            togglePath = "speed/open_rule_status",
            toggleValueKey = "open",
            toggleValueBool = true,
            toggleItemParams = mapOf("site_name" to "site_name", "site_id" to "site_id"),
            toggleStateKey = "open"
        ),

        ListConfig(
            key = "manm",
            title = "人机验证",
            listPath = "manm/get_list",
            listParams = mapOf("p" to 1, "p_size" to 50),
            titleKeys = listOf("ps", "auth_type", "key"),
            subtitleKeys = listOf("open", "key", "auth_type"),
            deletePath = "manm/del_rules",
            deleteParamKey = "key",
            deleteParamIsArray = true,
            deleteItemIdKeys = listOf("key", "index", "id"),
            togglePath = "manm/off_authtype",
            toggleItemParams = mapOf("key" to "key"),
            toggleStateKey = "open"
        ),

        ListConfig(
            key = "regional",
            title = "区域拦截",
            listPath = "wafrules/get_regional_restrictions_rules",
            listParams = mapOf("p" to 1, "p_size" to 50, "searchStr" to ""),
            titleKeys = listOf("region", "label", "name", "id"),
            subtitleKeys = listOf("status", "site_id", "id", "type"),
            deletePath = "wafrules/del_regional_restrictions_rules",
            deleteParamKey = "region_id",
            deleteParamIsArray = true,
            deleteItemIdKeys = listOf("id", "index", "key"),
            togglePath = "wafrules/set_regional_restrictions_rules",
            toggleValueKey = "status",
            toggleValueBool = true,
            toggleItemParams = mapOf("region_id" to "id"),
            toggleStateKey = "status"
        )
    )

    fun byKey(key: String): ListConfig? = all.firstOrNull { it.key == key }
}
