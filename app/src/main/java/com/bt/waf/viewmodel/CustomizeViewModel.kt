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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 自定义防护规则（对应 aawaf 后端 modules/customize.go，共 11 个导出方法）。
 *
 * 接口契约（源码核对，action 由 Go 方法名 SnakeCase 得到，路径为 customize/{action}）：
 *
 *  1. list              { keyword, p, p_size, type } → { total, list: 元素为 Entry + hit }
 *     注意参数名是 `type`（不是 types）。type > 1 时按 `src = type` 精确过滤，
 *     否则查 `src < 2`（即普通 WAF 模式规则）。排序固定为 priority desc, create_time desc。
 *  2. create            types.Entry 结构，成功返回「创建成功」
 *  3. update            types.Entry 结构（必须带 id），成功返回「编辑成功」
 *  4. remove            { ids: number数组 } —— 必须是 JSON 数组
 *  5. get_config_help   { type } → { action列表, options列表, operators字典, sitemap, status_code列表 }
 *  6. parse_expression  { expr } → types.Node（把表达式文本解析成 root 树）
 *  7. to_expression     { id } → 表达式字符串
 *  8. export            无参数 GET，返回 application/octet-stream 文件下载
 *  9. import            multipart/form-data，字段名固定 `customize_rule`
 *  10. export_example   { options: 数组 } → { rules字典, allsite列表 }（纯内存生成，不读写数据库）
 *  11. create_with_block_log { data: 元素含 server_name / uri / method } → 访问日志白名单批量转放行规则
 *
 * 校验顺序（服务端 create 与 update 不一致，必须知道）：
 *  - create: Validate(root非空且children非空) → src==3 时校验 response.body 是 http(s) URL
 *  - update: id!=0 → 规则存在 → src==3 校验 URL → Validate
 *    （update 先查存在性再校验 root，且 src 字段不会写库，源码里 delete(updateData,"src")）
 *
 * 客户端做同等前置校验的目的是：把「不能添加空规则」这类错误在弹窗内即时反馈，
 * 而不是等一次往返。服务端文案才是权威，客户端只做常见错误的快速拦截。
 */
class CustomizeViewModel(private val repo: WafRepository) : ViewModel() {

    // ---------------- 列表 ----------------

    var rules by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var total by mutableStateOf(0)
    var page by mutableStateOf(1)
    var keyword by mutableStateOf("")
    var listLoading by mutableStateOf(false)
    var listError by mutableStateOf<String?>(null)

    /** 规则类型过滤：0 = 全部普通模式(src<2)，2 = CC 模式，3 = 重定向模式。 */
    var filterType by mutableStateOf(0)

    val hasMore: Boolean get() = rules.size < total

    /**
     * 拉取规则列表。
     * @param type 0/1 查src<2；2/3 查 src=type（与 ListConfig 里的默认参数保持一致）
     */
    fun load(type: Int = filterType, keywordOverride: String? = null) {
        val kw = keywordOverride ?: keyword
        viewModelScope.launch {
            listLoading = true
            listError = null
            try {
                val r = repo.safeCall(
                    "customize/list",
                    mapOf(
                        "p" to 1,
                        "p_size" to PAGE_SIZE,
                        "keyword" to kw,
                        "type" to type
                    )
                )
                val map = r.asMap()
                rules = map["list"].asList()
                total = map["total"].asString().toIntOrNull() ?: rules.size
                filterType = type
                page = 1
            } catch (e: Exception) {
                listError = e.message
            } finally {
                listLoading = false
            }
        }
    }

    // ---------------- 编辑器状态 ----------------

    /** 新建/编辑弹窗的错误文案。必须在弹窗内部渲染，不能走 Snackbar。 */
    var formError by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** 规则编辑器的当前草稿（结构对应 types.Entry）。 */
    var draft by mutableStateOf(RuleDraft())

    /** 新建规则：以草稿为内容调用 create。 */
    fun create(draft: RuleDraft, onResult: (String?) -> Unit) =
        save("customize/create", draft, onResult)

    /** 编辑规则：以草稿为内容调用 update（draft.id 必须非 0）。 */
    fun update(draft: RuleDraft, onResult: (String?) -> Unit) {
        if (draft.id == 0) {
            formError = "规则ID不能为空"
            onResult("规则ID不能为空")
            return
        }
        save("customize/update", draft, onResult)
    }

    private fun save(path: String, draft: RuleDraft, onResult: (String?) -> Unit) {
        val precheck = draft.validate()
        if (precheck != null) {
            formError = precheck
            onResult(precheck)
            return
        }
        viewModelScope.launch {
            saving = true
            formError = null
            try {
                val r = repo.safeCall(path, draft.toPayload())
                val err = if (r.isSuccess) null else r.errorMessage()
                formError = err
                onResult(err)
                if (r.isSuccess) load()
            } catch (e: Exception) {
                formError = e.message
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    /**
     * 删除规则。
     *
     * 坑：ids 必须是 JSON 数组而不是逗号分隔字符串 —— 源码是 `Ids` 声明为 int 切片，
     * 传 "1,2" 会被 mapstructure 解不出来（或解成单元素），批量删除会静默只删一条。
     *
     * 另一个坑：服务端只校验 `WhereIn(...).Exists()`（存在任意一个即通过），
     * 所以「部分 id 不存在」不会报错，而是把存在的那些删掉、不存在的忽略。
     */
    fun remove(ids: List<Int>, onResult: (String?) -> Unit) {
        if (ids.isEmpty()) {
            val msg = "规则ID不能为空"
            formError = msg
            onResult(msg)
            return
        }
        viewModelScope.launch {
            saving = true
            formError = null
            try {
                val r = repo.safeCall("customize/remove", mapOf("ids" to ids))
                val err = if (r.isSuccess) null else r.errorMessage()
                formError = err
                onResult(err)
                if (r.isSuccess) load()
            } catch (e: Exception) {
                formError = e.message
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    // ---------------- 配置帮助 ----------------

    var help by mutableStateOf(ConfigHelp())
    var helpLoading by mutableStateOf(false)
    var helpError by mutableStateOf<String?>(null)

    /**
     * 获取编辑器可选项（action / options / operators / sitemap）。
     *
     * 坑：
     *  - 参数名是 `type`。type==2 返回 CC 模式配置（带 cc、block_time、status_code），
     *    type==3 返回重定向模式配置，其他值静默回落到默认 WAF 模式（allow/deny/validate/record）。
     *  - 中英文返回文案不同，且 EN 版缺少 post_param / body_param 选项、
     *    CN 版缺少 ip_belongs 选项，所以不能把选项写死在客户端。
     */
    fun loadConfigHelp(type: Int, onLoaded: ((ConfigHelp) -> Unit)? = null) {
        viewModelScope.launch {
            helpLoading = true
            helpError = null
            try {
                val r = repo.safeCall("customize/get_config_help", mapOf("type" to type))
                help = ConfigHelp.parse(r.asMap())
                onLoaded?.invoke(help)
            } catch (e: Exception) {
                helpError = e.message
            } finally {
                helpLoading = false
            }
        }
    }

    // ---------------- 表达式 ----------------

    /** parse_expression 的结果（types.Node 树），用于把文本表达式灌进编辑器。 */
    var parsedExpression by mutableStateOf<Map<String, Any?>>(emptyMap())
    var exprError by mutableStateOf<String?>(null)

    /**
     * 解析表达式文本 → types.Node 树。
     *
     * 语法（源码 parseExpression）：
     *   `<type>.<left_factor> <op> <right>`，多条用 ` or ` / ` and ` 分隔，
     *   可用() 包裹分组，in / not_in 的右值写成 `{a,b}`。
     *   op 集合：regexp ^= $= %= = != <> in "not in" > >= < <=
     * 服务端会先 html.UnescapeString，所以 &gt; 这类实体也能过。
     */
    fun parseExpression(expr: String, onResult: ((Map<String, Any?>) -> Unit)? = null) {
        val text = expr.trim()
        if (text.isBlank()) {
            exprError = "表达式不能为空"
            onResult?.invoke(emptyMap())
            return
        }
        viewModelScope.launch {
            exprError = null
            try {
                val r = repo.safeCall("customize/parse_expression", mapOf("expr" to text))
                val node = r.asMap()
                parsedExpression = node
                onResult?.invoke(node)
            } catch (e: Exception) {
                exprError = e.message
                onResult?.invoke(emptyMap())
            }
        }
    }

    /**
     * 把规则反向还原为表达式文本（to_expression）。
     * 服务端用 id 查库再ToExpression()，只支持 block/option 两层嵌套，
     * 中间 block 会被跳过。
     */
    fun toExpression(id: Int, onResult: (String?, String?) -> Unit) {
        if (id == 0) {
            onResult(null, "规则ID不能为空")
            return
        }
        viewModelScope.launch {
            try {
                val r = repo.safeCall("customize/to_expression", mapOf("id" to id))
                onResult(r.res.asString(), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    // ---------------- 导入 / 导出 ----------------

    var transferring by mutableStateOf(false)
    var transferError by mutableStateOf<String?>(null)

    /** 最近一次导出成功的本地文件，供界面发起分享。 */
    var lastExport by mutableStateOf<WafRepository.DownloadedFile?>(null)

    /**
     * 导出规则到本地文件（application/octet-stream）。
     * 文件名取自响应头 Content-Disposition，服务端给的是
     * `attachment;filename=自定义规则__YYYY-MM-DD.json`（中文被 URL 编码）。
     * 落在 app 缓存目录，无需任何权限；界面用 FileProvider 分享给用户。
     */
    fun export(onResult: (WafRepository.DownloadedFile?, String?) -> Unit) {
        viewModelScope.launch {
            transferring = true
            transferError = null
            try {
                val today = SimpleDateFormat("yyyy-MM-dd").format(Date())
                val f = repo.downloadFile("customize/export", "自定义规则__$today.json", ".json")
                lastExport = f
                onResult(f, null)
            } catch (e: Exception) {
                transferError = e.message
                onResult(null, e.message)
            } finally {
                transferring = false
            }
        }
    }

    /**
     * 导入规则文件（multipart/form-data，字段名固定 customize_rule）。
     *
     * 服务端约束：
     *  - 文件名必须 .json 后缀，否则「无效的规则文件 - 1」
     *  - 内容是 types.Entry 组成的 JSON 数组，空数组报「不能导入空数据」
     *  - **每条 entry.Id 必须非 0**，否则整批事务回滚（报「导入数据失败：无效的数据」）
     *    —— 所以别拿列表接口的裁剪结果去导出会失败，建议用 export_example 的产物
     *  - 规则名会被强制追加 __FROM_IMPORT 后缀，id 全部重新自增（不会覆盖已有规则）
     */
    fun importRule(file: File, onResult: (String?) -> Unit) {
        if (!file.name.endsWith(".json", ignoreCase = true)) {
            // 服务端硬校验后缀，这里提前拦一道，省一次往返
            val msg = "无效的规则文件：文件名必须以 .json 结尾"
            transferError = msg
            onResult(msg)
            return
        }
        viewModelScope.launch {
            transferring = true
            transferError = null
            try {
                val r = repo.uploadFile(
                    path = "customize/import",
                    fieldName = "customize_rule",
                    file = file,
                    mimeType = "application/json"
                )
                val err = if (r.isSuccess) null else r.errorMessage()
                transferError = err
                onResult(err)
                if (r.isSuccess) load()
            } catch (e: Exception) {
                transferError = e.message
                onResult(e.message)
            } finally {
                transferring = false
            }
        }
    }

    /**
     * 生成示例规则（export_example）。
     *
     * 纯内存生成，**不读库也不写库**，所以列表不会变化。
     * 入参 options 就是 get_config_help 返回的 options 数组（需要带 examples 字段，
     * 由界面补示例值），返回 { rules: 以 id 为键的 Entry 字典, allsite: id 列表 }。
     */
    fun exportExample(options: List<Map<String, Any?>>, onResult: (Map<String, Any?>?, String?) -> Unit) {
        if (options.isEmpty()) {
            onResult(null, "请至少提供一个匹配项")
            return
        }
        viewModelScope.launch {
            try {
                val r = repo.safeCall("customize/export_example", mapOf("options" to options))
                onResult(r.asMap(), null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    // ---------------- 访问日志白名单批量转放行 ----------------

    var batchLogMessage by mutableStateOf<String?>(null)

    /**
     * 把访问日志里的白名单条目批量转成「放行」自定义规则。
     *
     * 坑（服务端 CreateWithBlockLog）：
     *  - data 每项需要 server_name / uri / method 三者都非空，否则计入失败数
     *  - uri 用 url.ParseRequestURI 解析，取 u.Path（**丢掉 query**），
     *    query 里的参数名会被额外转成一条 param_name in {...} 条件
     *  - method 会转大写；同server+uri+method 重复项会被静默跳过（既不算成功也不算失败）
     *  - **每条独立事务，且每条都调一次 syncConfigFile 热加载** —— N 条就是 N 次热加载，
     *    条数多时很慢，界面必须先提示用户
     *  - 全部失败时接口返回 Fail（HTTP 400），但文案里带成功/失败条数，仍要展示给用户
     */
    fun createWithBlockLog(
        data: List<Map<String, String>>,
        onResult: (String?, String?) -> Unit
    ) {
        if (data.isEmpty()) {
            onResult(null, "没有可转换的访问日志记录")
            return
        }
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall("customize/create_with_block_log", mapOf("data" to data))
                // 成功与失败都要把「成功N条,失败M条」这句话透出去
                val msg = r.res.asString().ifBlank { if (r.isSuccess) "转换完成" else r.errorMessage() }
                batchLogMessage = msg
                if (r.isSuccess) load()
                onResult(msg, null)
            } catch (e: Exception) {
                batchLogMessage = e.message
                onResult(null, e.message)
            } finally {
                saving = false
            }
        }
    }

    /** 批量转换的规模提示文案，界面在确认前展示。 */
    fun blockLogWarning(count: Int): String =
        "即将把 $count 条访问日志记录转成放行规则。服务端每条都会单独写库并热加载一次，" +
            "条数较多时耗时较长，请确认后继续。"

    fun consumeMessage() {
        batchLogMessage = null
        transferError = null
        formError = null
    }

    // -----------------------------------------------------------------
    // 数据结构
    // -----------------------------------------------------------------

    /**
     * 规则草稿，对应 types.Entry。
     * 字段名与后端 json tag 保持一致，减少映射出错。
     */
    data class RuleDraft(
        val id: Int = 0,
        val name: String = "",
        val servers: List<String> = emptyList(),
        val isGlobal: Int = 0,
        val src: Int = 1,
        val status: Int = 1,
        val priority: Int = 0,
        val executePhase: String = "access",
        val actionType: String = "deny",
        val responseType: String = "black_page",
        val responseBody: String = "",
        val blockTime: Int = 0,
        val ccInterval: Int = 0,
        val ccThreshold: Int = 0,
        val logic: String = "or",
        val conditions: List<Condition> = emptyList()
    ) {
        /** 转换成请求体。servers 必须是数组，root 必须是嵌套树。 */
        fun toPayload(): Map<String, Any?> = mapOf(
            "id" to id,
            "name" to name.trim(),
            "servers" to servers,
            "is_global" to isGlobal,
            "src" to src,
            "status" to status,
            "priority" to priority,
            "execute_phase" to executePhase,
            "action" to mapOf(
                "type" to actionType,
                "response" to mapOf(
                    "type" to responseType,
                    "body" to responseBody
                ),
                "block_time" to blockTime,
                "cc" to mapOf(
                    "interval" to ccInterval,
                    "threshold" to ccThreshold
                )
            ),
            "root" to mapOf(
                "type" to "block",
                "logic" to logic,
                "children" to conditions.map { it.toNode() }
            )
        )

        /**
         * 客户端前置校验，复刻 types.Entry.Validate 的常见分支。
         * 返回 null 表示通过。服务端文案才是最终权威，这里只是提前反馈。
         */
        fun validate(): String? {
            if (name.isBlank()) return "请填写规则名称"
            if (conditions.isEmpty()) return "不能添加空规则"
            for (c in conditions) {
                if (c.type.isBlank()) return "不能添加空规则"
                if (c.operator.isBlank()) return "不能添加空规则"
                if (c.type == "ip") {
                    if (c.rightFactor.isBlank()) return "客户端IP不能为空"
                    val list = if (c.operator == "in" || c.operator == "not_in") {
                        c.rightFactor.split(",")
                    } else {
                        listOf(c.rightFactor)
                    }
                    list.firstOrNull { !isIpv4OrIpv6(it.trim()) }?.let {
                        return "客户端IP ${it.trim()} 格式错误"
                    }
                }
                if (c.type == "ip_range") {
                    if (c.rightFactor.isBlank()) return "IP段不能为空"
                    val list = if (c.operator == "in" || c.operator == "not_in") {
                        c.rightFactor.split(",")
                    } else {
                        listOf(c.rightFactor)
                    }
                    list.firstOrNull { !isCidr(it.trim()) }?.let {
                        return "IP段 ${it.trim()} 格式错误，必须为CIDR表达式"
                    }
                }
            }
            // src==3（重定向模式）时 response.body 必须是合法 http(s) URL
            if (src == 3 && !isHttpUrl(responseBody)) return "url不正确"
            return null
        }
    }

    /** 单条匹配条件，对应 types.Option。 */
    data class Condition(
        val type: String,
        val operator: String,
        val rightFactor: String,
        val leftFactor: String = ""
    ) {
        fun toNode(): Map<String, Any?> = mapOf(
            "type" to "option",
            "logic" to "",
            "option" to mapOf(
                "type" to type,
                "left_factor" to leftFactor,
                "operator" to operator,
                "right_factor" to rightFactor
            ),
            "children" to emptyList<Any>()
        )
    }

    /** get_config_help 的结构化结果。 */
    data class ConfigHelp(
        val actions: List<Map<String, Any?>> = emptyList(),
        val options: List<Map<String, Any?>> = emptyList(),
        val operators: Map<String, Map<String, Any?>> = emptyMap(),
        val sitemap: Map<String, String> = emptyMap(),
        val statusCode: List<String> = emptyList()
    ) {
        companion object {
            fun parse(m: Map<String, Any?>): ConfigHelp {
                val ops = m["operators"].asMap()
                @Suppress("UNCHECKED_CAST")
                val opsTyped = ops.mapValues { (_, v) -> v.asMap() }
                return ConfigHelp(
                    actions = m["action"].asList(),
                    options = m["options"].asList(),
                    operators = opsTyped,
                    sitemap = m["sitemap"].asMap().mapValues { it.value.asString() },
                    statusCode = m["status_code"].asList().map { it.asString() }
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50

        /** 简单 IPv4/IPv6 判定（与服务端 net.ParseIP 的严格度不同，够用即可）。 */
        private fun isIpv4OrIpv6(s: String): Boolean {
            if (s.isBlank()) return false
            if (s.contains(':')) return s.all { it.isDigit() || it in "abcdefABCDEF:." } && s.count { it == ':' } >= 2
            val parts = s.split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                    (p.toIntOrNull() ?: -1) in 0..255 && (p.length == 1 || p[0] != '0')
            }
        }

        /** CIDR 粗判：前缀长度合法 + 左侧是合法 IP。 */
        private fun isCidr(s: String): Boolean {
            val parts = s.split('/')
            if (parts.size != 2) return false
            val bits = parts[1].toIntOrNull() ?: return false
            val ip = parts[0]
            val max = if (ip.contains(':')) 128 else 32
            if (bits < 0 || bits > max) return false
            return isIpv4OrIpv6(ip)
        }

        /** http(s) 前缀 + 有 host。服务端 public.IsUrl 还要求 host 合法，这里放宽到可用即可。 */
        private fun isHttpUrl(s: String): Boolean {
            val t = s.trim()
            if (!t.startsWith("http://") && !t.startsWith("https://")) return false
            val host = t.removePrefix("http://").removePrefix("https://")
                .substringBefore('/').substringBefore('?')
            return host.isNotBlank() && host.contains('.')
        }
    }
}