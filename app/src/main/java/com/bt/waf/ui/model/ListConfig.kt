package com.bt.waf.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 通用“受管列表”配置：用一个界面 + 一个 ViewModel 覆盖大量后台模块。
 *
 * 所有字段均对照 aawaf 后端源码（modules 目录下的 Go 文件）核对，注释里标注了对应函数。
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val hint: String = "",
    val defaultValue: String = "",
    /** 下拉选项：展示文本 -> 提交值 */
    val options: List<Pair<String, String>>? = null,
    /** 多行输入（长文本、IP 列表等） */
    val multiline: Boolean = false
)

data class ListConfig(
    val key: String,
    val title: String,
    val listPath: String,
    /** 列表项图标，全站统一由 ListRow 渲染 */
    val icon: ImageVector = Icons.Filled.ListAlt,
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

    val keywordSearch: Boolean = false,
    /** 搜索框提交时使用的参数名，默认 keyword */
    val keywordParam: String = "keyword",
    /** 时间筛选参数（report 系列用 query_data，格式 "YYYY-MM-DD"） */
    val dateFilter: Boolean = false,
    /** 一键清空接口（列表页顶部菜单） */
    val clearPath: String? = null,
    val clearExtraParams: Map<String, Any?> = emptyMap(),

    /**
     * true 表示接口直接返回顶层数组（res 就是 list，不是 {list:[]}）。
     * 例：inset/get_malicious_ip_list、exclusive/get_list。
     */
    val rootArrayResponse: Boolean = false,
    /**
     * true 表示删除/启停的标识是「该项在当前列表中的下标」而非列表项字段。
     * 例：exclusive/del_rules 接收 index 数组，rule_status 接收单条 index。
     */
    val idFromPosition: Boolean = false
)

object ManagedLists {
    private val matchOptions = listOf(
        "包含" to "0", "前缀匹配" to "1", "后缀匹配" to "2", "等于" to "3"
    )
    private val ipTypeOptions = listOf("IPv4" to "0", "IPv6" to "1")

    /* ───────── limit 模块：IP / URL / UA 黑白名单 ───────── */

    private fun ipConfig(key: String, title: String, types: Int) = ListConfig(
        key = key,
        title = title,
        icon = if (types == 1) Icons.Filled.CheckCircle else Icons.Filled.Block,
        listPath = "limit/get_ip",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        titleKeys = listOf("start", "notes", "index"),
        subtitleKeys = listOf("end", "notes", "ip_type", "index"),
        createPath = "limit/set_ip",
        createFields = listOf(
            FieldSpec("start", "起始 IP", hint = "必填，支持单 IP"),
            FieldSpec("end", "结束 IP", hint = "留空表示单个 IP"),
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
        icon = Icons.Filled.Link,
        listPath = "limit/get_url",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        titleKeys = listOf("url", "notes", "index"),
        subtitleKeys = listOf("type", "param", "notes", "index"),
        createPath = "limit/set_url",
        createFields = listOf(
            FieldSpec("url", "URL / 路径", hint = "不能以 / 开头且不为根"),
            FieldSpec("type", "匹配方式", options = matchOptions, defaultValue = "0"),
            FieldSpec("param", "参数", hint = "可选"),
            FieldSpec("notes", "备注")
        ),
        createExtraParams = mapOf("types" to types),
        deletePath = "limit/del_data",
        deleteParamKey = "index",
        deleteParamIsArray = true,
        deleteExtraParams = mapOf("types" to types),
        togglePath = "limit/off_url",
        toggleItemParams = mapOf("index" to "index"),
        toggleExtraParams = mapOf("types" to types)
    )

    private fun uaConfig(key: String, title: String, types: Int) = ListConfig(
        key = key,
        title = title,
        icon = Icons.Filled.Fingerprint,
        listPath = "limit/get_ua",
        listParams = mapOf("p" to 1, "p_size" to 50, "types" to types, "keyword" to ""),
        titleKeys = listOf("data", "notes", "index"),
        subtitleKeys = listOf("notes", "index"),
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
        toggleItemParams = mapOf("index" to "index"),
        toggleExtraParams = mapOf("types" to types)
    )

    /* ───────── 各模块独立配置 ───────── */

    private val exclusive = ListConfig(
        key = "exclusive",
        title = "专属规则（例外放行）",
        listPath = "exclusive/get_list",
        icon = Icons.Filled.OpenInNew,
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to ""),
        rootArrayResponse = false,
        idFromPosition = true,
        titleKeys = listOf("rule_name", "site_name"),
        subtitleKeys = listOf("ps", "count", "timestamp"),
        createPath = "exclusive/add_rules",
        createFields = listOf(
            FieldSpec("rule_name", "规则名", hint = "滑动验证 / 无感验证 / 等待5s验证"),
            FieldSpec("site_name", "防护网站", hint = "站点名，如 example.com"),
            FieldSpec("ps", "备注")
        ),
        deletePath = "exclusive/del_rules",
        deleteParamKey = "index",
        deleteParamIsArray = true,
        togglePath = "exclusive/rule_status",
        toggleValueKey = "status",
        toggleItemParams = mapOf("index" to "__pos__"),
        toggleStateKey = "status",
        keywordSearch = true
    )

    private val customize = ListConfig(
        key = "customize",
        title = "自定义防护规则",
        listPath = "customize/list",
        icon = Icons.Filled.Rule,
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "type" to 0),
        titleKeys = listOf("name", "root", "id"),
        subtitleKeys = listOf("status", "priority", "create_time", "execute_phase", "action"),
        createPath = "customize/create",
        createFields = listOf(
            FieldSpec("name", "规则名称", hint = "必填"),
            FieldSpec("root", "匹配内容", hint = "匹配到的内容，如 User-Agent"),
            FieldSpec("expression", "规则表达式", hint = "如 eq(test)", multiline = true),
            FieldSpec("action", "处置动作", options = listOf("拦截" to "1", "记录" to "0", "放行" to "2"), defaultValue = "1"),
            FieldSpec("execute_phase", "执行阶段", options = listOf("访问前" to "0", "访问后" to "1"), defaultValue = "0")
        ),
        deletePath = "customize/remove",
        deleteParamKey = "ids",
        deleteParamIsArray = true,
        deleteItemIdKeys = listOf("id"),
        keywordSearch = true
    )

    private val speed = ListConfig(
        key = "speed",
        title = "访问限速",
        listPath = "speed/get_list",
        icon = Icons.Filled.Speed,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("site_name", "domain", "site_id"),
        subtitleKeys = listOf("open", "expire", "size", "site_id"),
        createPath = "speed/add_rules",
        createFields = listOf(
            FieldSpec("site_name", "防护网站", hint = "站点名"),
            FieldSpec("site_id", "站点 ID", hint = "如 abc123"),
            FieldSpec("size", "限速大小", hint = "KB/s"),
            FieldSpec("expire", "有效期(秒)", hint = "0 表示永久")
        ),
        togglePath = "speed/open_rule_status",
        toggleValueKey = "open",
        toggleValueBool = true,
        toggleItemParams = mapOf("site_name" to "site_name", "site_id" to "site_id"),
        toggleStateKey = "open"
    )

    private val manm = ListConfig(
        key = "manm",
        title = "人机验证",
        listPath = "manm/get_list",
        icon = Icons.Filled.SmartToy,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("ps", "auth_type", "key"),
        subtitleKeys = listOf("open", "key", "auth_type"),
        createPath = "manm/add_rules",
        createFields = listOf(
            FieldSpec("ps", "规则名称", hint = "必填"),
            FieldSpec("auth_type", "验证方式", options = listOf("滑动验证" to "huadong", "无感验证" to "js", "等待验证" to "renji"), defaultValue = "huadong"),
            FieldSpec("rule_log", "匹配内容", hint = "如 匹配ip组-[名称]", multiline = true)
        ),
        deletePath = "manm/del_rules",
        deleteParamKey = "key",
        deleteParamIsArray = true,
        deleteItemIdKeys = listOf("key", "index", "id"),
        togglePath = "manm/off_authtype",
        toggleItemParams = mapOf("key" to "key"),
        toggleStateKey = "open"
    )

    private val regional = ListConfig(
        key = "regional",
        title = "区域拦截",
        listPath = "wafrules/get_regional_restrictions_rules",
        icon = Icons.Filled.Public,
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
        toggleStateKey = "status",
        keywordSearch = true,
        keywordParam = "searchStr"
    )

    private val httpHeaderLen = ListConfig(
        key = "http_header_len",
        title = "HTTP 头长度限制",
        listPath = "wafrules/get_http_header_len_rules",
        icon = Icons.Filled.Compress,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("site_name", "site_id", "id"),
        subtitleKeys = listOf("open", "header_len", "id"),
        createPath = "wafrules/add_http_header_len_rules",
        createFields = listOf(
            FieldSpec("site_name", "防护网站"),
            FieldSpec("site_id", "站点 ID"),
            FieldSpec("header_len", "长度上限(字节)", hint = "如 8192")
        ),
        deletePath = "wafrules/del_http_header_len_rules",
        deleteParamKey = "ids",
        deleteParamIsArray = true,
        deleteItemIdKeys = listOf("id"),
        togglePath = "wafrules/modify_http_header_len_rules",
        toggleValueKey = "open",
        toggleItemParams = mapOf("id" to "id"),
        toggleStateKey = "open"
    )

    private val userLimit = ListConfig(
        key = "user_limit",
        title = "单 IP 访问限制",
        listPath = "wafrules/get_user_limit",
        icon = Icons.Filled.PersonOutline,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("site_name", "site_id", "id"),
        subtitleKeys = listOf("open", "max_req", "time", "id"),
        createPath = "wafrules/set_user_limit",
        createFields = listOf(
            FieldSpec("site_name", "防护网站"),
            FieldSpec("site_id", "站点 ID"),
            FieldSpec("max_req", "最大请求数", hint = "如 100"),
            FieldSpec("time", "统计周期(秒)", hint = "如 60"),
            FieldSpec("uri", "限定 URI", hint = "留空表示全站")
        ),
        deletePath = "wafrules/del_user_limit",
        deleteParamKey = "ids",
        deleteParamIsArray = true,
        deleteItemIdKeys = listOf("id")
    )

    private val spider = ListConfig(
        key = "spider",
        title = "搜索引擎爬虫",
        listPath = "wafrules/get_spider_rules",
        icon = Icons.Filled.TravelExplore,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("name", "rule_log", "id"),
        subtitleKeys = listOf("open", "id"),
        togglePath = "wafrules/update_spider_rules",
        toggleValueKey = "open",
        toggleItemParams = mapOf("id" to "id"),
        toggleStateKey = "open"
    )

    private val httpMethodType = ListConfig(
        key = "http_method",
        title = "HTTP 请求方法限制",
        listPath = "wafrules/get_http_method_type_rules",
        icon = Icons.Filled.SwapHoriz,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("site_name", "site_id", "id"),
        subtitleKeys = listOf("open", "method", "id"),
        togglePath = "wafrules/set_http_method_type_rules",
        toggleValueKey = "open",
        toggleItemParams = mapOf("id" to "id", "site_id" to "site_id"),
        toggleStateKey = "open"
    )

    private val optLogs = ListConfig(
        key = "opt_logs",
        title = "操作日志",
        listPath = "opt_log/list",
        icon = Icons.Filled.History,
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("content", "operator", "log_type", "id"),
        subtitleKeys = listOf("create_time", "uid", "id"),
        keywordSearch = true
    )

    private val ccEvents = ListConfig(
        key = "cc_events",
        title = "CC 攻击事件",
        listPath = "report/cc_list",
        icon = Icons.Filled.Bolt,
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "query_data" to ""),
        titleKeys = listOf("servername", "server_name", "id"),
        subtitleKeys = listOf("uri", "host", "create_time", "id"),
        keywordSearch = true,
        dateFilter = true,
        clearPath = "report/cc_list",
        clearExtraParams = mapOf("clear" to 1)
    )

    private val ipBlockLog = ListConfig(
        key = "ip_block_log",
        title = "IP 封禁记录",
        listPath = "report/attack_report_ip_log",
        icon = Icons.Filled.Lock,
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "query_data" to ""),
        titleKeys = listOf("ip", "id"),
        subtitleKeys = listOf("server_name", "ip_country", "ip_city", "risk_type", "time"),
        keywordSearch = true,
        dateFilter = true,
        clearPath = "report/attack_report_ip_log",
        clearExtraParams = mapOf("clear" to 1, "query_data" to "")
    )

    private val ruleHit = ListConfig(
        key = "rule_hit",
        title = "规则命中日志",
        listPath = "report/get_rule_hit_list",
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "filter" to ""),
        titleKeys = listOf("rule_name", "server_name", "key"),
        subtitleKeys = listOf("uri", "ip", "ip_country", "rule_type", "timestimp"),
        keywordSearch = true,
        clearPath = "report/delete_rule_hit_list"
    )

    private val attackLogs = ListConfig(
        key = "attack_logs_full",
        title = "拦截日志",
        listPath = "report/attack_report_log",
        listParams = mapOf("p" to 1, "p_size" to 50, "keyword" to "", "query_data" to ""),
        titleKeys = listOf("server_name", "uri", "id"),
        subtitleKeys = listOf("ip", "ip_country", "risk_type", "filter_rule", "time", "action"),
        keywordSearch = true,
        dateFilter = true,
        clearPath = "report/attack_report_log",
        clearExtraParams = mapOf("clear" to 1, "query_data" to "")
    )

    private val maliciousIp = ListConfig(
        key = "malicious_ip",
        title = "恶意 IP 库",
        listPath = "inset/get_malicious_ip_list",
        listParams = mapOf("p" to 1, "p_size" to 100, "keyword" to "", "sort" to 3),
        rootArrayResponse = true,
        titleKeys = listOf("ip"),
        subtitleKeys = listOf("release_time"),
        keywordSearch = true
    )

    private val loadBalance = ListConfig(
        key = "load_balance",
        title = "负载均衡分组",
        listPath = "multi/node_list",
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("name", "load_name", "id"),
        subtitleKeys = listOf("method", "dns_name", "ps", "create_time", "id"),
        deletePath = "wafmasterload/delete_load_balance",
        deleteParamKey = "id",
        deleteItemIdKeys = listOf("id", "group_id")
    )

    private val users = ListConfig(
        key = "users",
        title = "用户列表",
        listPath = "multi/user_list",
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("username", "name", "id"),
        subtitleKeys = listOf("role_name", "status", "create_time", "id")
    )

    private val roles = ListConfig(
        key = "roles",
        title = "角色列表",
        listPath = "multi/role_list",
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("name", "role_name", "id"),
        subtitleKeys = listOf("ps", "create_time", "id")
    )

    private val nodes = ListConfig(
        key = "nodes",
        title = "集群节点",
        listPath = "multi/node_list",
        listParams = mapOf("p" to 1, "p_size" to 50),
        titleKeys = listOf("name", "node_name", "id"),
        subtitleKeys = listOf("ip", "status", "id")
    )

    val all: List<ListConfig> = listOf(
        ipConfig("ip_black", "IP 黑名单", 1),
        ipConfig("ip_white", "IP 白名单", 0),
        urlConfig("url_black", "URL 黑名单", 1),
        urlConfig("url_white", "URL 白名单", 0),
        uaConfig("ua_black", "UA 黑名单", 1),
        uaConfig("ua_white", "UA 白名单", 0),

        exclusive,
        customize,
        speed,
        manm,
        regional,
        httpHeaderLen,
        userLimit,
        spider,
        httpMethodType,

        optLogs,
        attackLogs,
        ipBlockLog,
        ccEvents,
        ruleHit,
        maliciousIp,

        loadBalance,
        users,
        roles,
        nodes
    )

    fun byKey(key: String): ListConfig? = all.firstOrNull { it.key == key }
}
