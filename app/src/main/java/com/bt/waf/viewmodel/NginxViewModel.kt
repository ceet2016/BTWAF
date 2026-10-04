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
 * nginx 反向代理与负载均衡模块。
 *
 * 对应后端 Go 方法 `(n *Nginx) Xxx`。路由 key 由 `core.SnakeCase("Nginx/Xxx")` 生成，
 * 因此调用形式是 `POST /api/nginx/<action>`，action 为 Go 方法名的 snake_case 版本。
 * 例：`GetReturnSource` → `nginx/get_return_source`，`ModifyProxyinfo` → `nginx/modify_proxyinfo`。
 *
 * 全局约定（源码 core/tools.go GetParamsFromRequest + public/public.go ParamsCheck）：
 *  - 全部接口均为 POST + application/json。**数组类参数必须传 JSON 数组**，
 *    传逗号分隔字符串会走进无检查的类型断言分支，直接 panic（HTTP 500）。
 *  - `ParamsCheck` 只校验「键是否存在」，不校验是否为空。但键名为 `site_id` 且值非空时，
 *    会额外查 site_info 表，站点不存在直接返回 `site_id不存在`。
 *  - 处理器普遍使用 `params["x"].([]interface{})` / `.(string)` 无检查断言，
 *    所以类型不符表现为 500 panic 而**不是**「参数错误」，界面文案不要按后者预期。
 *  - 字符串参数会被 `FilterParamsSimple` 去掉首尾空白。
 */
class NginxViewModel(private val repo: WafRepository) : ViewModel() {

    // ---------------------------------------------------------------- 状态

    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** get_sites 返回的当前页站点。 */
    var sites by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var siteTotal by mutableStateOf(0)

    /** get_real_time_data：全站实时流量/拦截数。 */
    var realTimeData by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_return_source：当前回源配置（upstream）。 */
    var returnSource by mutableStateOf<Map<String, Any?>>(emptyMap())

    /**
     * get_domain：单站域名列表，每项 `{ domain, port }`。
     * 同一域名可能因多端口出现多条，删除时要连 port 一起带给 [delDomain] 才有意义。
     */
    var siteDomains by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_all_domain：全部站点精简信息。 */
    var allDomains by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_add_domain_info：新增前的域名可探测结果。 */
    var addDomainInfo by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_all_ssl：证书夹内全部证书。 */
    var allSsl by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_ssl_info：单站当前证书详情。 */
    var siteSslInfo by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** get_ssl_protocols_and_ciphers：可选值 + 当前生效值。 */
    var sslProtocolsAndCiphers by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** get_tcp_load_balance 的 tcp_load_balance 字段：load_balance_name → 配置。 */
    var tcpLoadBalances by mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap())

    /** get_log_list：历史日志文件列表。 */
    var logFiles by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** get_site_log：日志正文（纯文本，最多 1000 行）。 */
    var siteLog by mutableStateOf("")

    /** get_user_config：站点自定义配置文件内容。 */
    var userConfig by mutableStateOf("")

    /** get_site_setting：单站完整设置。 */
    var siteSetting by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** get_replace_open：该站是否已开启替换响应。 */
    var replaceOpen by mutableStateOf(false)

    // ------------------------------------------------------------ 内部工具

    /**
     * 只读查询：把 `res` 原始值交给 [onData]，错误文案交给 [onError]。
     * 回调形式是为了让错误渲染在弹窗内部，而不是走 Snackbar。
     */
    private fun query(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        onError: (String?) -> Unit,
        onData: (Any?) -> Unit
    ) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                onData(repo.safeCall(action, params).res)
                onError(null)
            } catch (e: Exception) {
                error = e.message
                onError(e.message ?: "请求失败")
            } finally {
                loading = false
            }
        }
    }

    /** 写操作：成功后回调 null，并可触发 [after] 刷新。 */
    private fun act(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        onError: (String?) -> Unit,
        after: () -> Unit = {}
    ) {
        viewModelScope.launch {
            saving = true
            error = null
            try {
                repo.safeCall(action, params)
                onError(null)
                after()
            } catch (e: Exception) {
                error = e.message
                onError(e.message ?: "操作失败")
            } finally {
                saving = false
            }
        }
    }

    private fun requireSiteId(siteId: String, onError: (String?) -> Unit): Boolean {
        if (siteId.isBlank()) {
            onError("site_id 不能为空")
            return false
        }
        return true
    }

    // ============================================================ 站点与回源

    /**
     * Go `GetSites` → `nginx/get_sites`。
     *
     * 陷阱：必传键只有 `p` / `p_size`，但函数体里无条件读取 `params["search"]`，
     * **漏传 search 会 panic 返回 500，而不是「参数错误」**。所以 search 必须显式传，
     * 无搜索条件时传空字符串。
     *
     * 第二个陷阱：`PaginateData` 在 startIndex >= 总数时返回的是**完整列表**而非空列表，
     * 且 `p` 小于 1 时 startIndex 为负数，切片会 panic。**p 必须 >= 1**。
     *
     * 返回 `{ list: [...], total: N }`。
     */
    fun getSites(
        page: Int = 1,
        pageSize: Int = 50,
        search: String = "",
        onError: (String?) -> Unit = {}
    ) {
        query(
            "nginx/get_sites",
            mapOf("p" to page.coerceAtLeast(1), "p_size" to pageSize, "search" to search),
            onError
        ) { raw ->
            val map = raw.asMap()
            sites = map["list"].asList()
            siteTotal = map["total"].asString().toIntOrNull() ?: sites.size
        }
    }

    /**
     * Go `CreateSite` → `nginx/create_site`。
     *
     * 必传键（缺一即「参数错误」）：privkey, fullchain, sitename, polling_algorithm,
     * cdn, ip_list, domain, is_https, host。
     *
     * 陷阱：
     *  - `domain` 与 `ip_list` 必须是 **JSON 数组**（`.([]interface{})` 无检查断言）。
     *  - `host` 必传但允许空串，为空时后端自动填 `$host`。
     *  - `is_https` / `cdn` 走 `InterfaceToBool`，JSON 布尔最稳。
     *  - `sitename` 里含 `:` 时后端会额外调用 `AllowPort` 放行该端口（防火墙副作用）。
     *
     * 副作用：写站点配置 + 重载 nginx + 备份 WAF 配置。
     */
    fun createSite(
        sitename: String,
        domain: List<String>,
        ipList: List<String>,
        isHttps: Boolean,
        privkey: String,
        fullchain: String,
        cdn: Boolean,
        pollingAlgorithm: String,
        host: String = "",
        onError: (String?) -> Unit = {}
    ) = act(
        "nginx/create_site",
        mapOf(
            "sitename" to sitename,
            "domain" to domain,
            "ip_list" to ipList,
            "is_https" to isHttps,
            "privkey" to privkey,
            "fullchain" to fullchain,
            "cdn" to cdn,
            "polling_algorithm" to pollingAlgorithm,
            "host" to host
        ),
        onError,
        ::refreshSites
    )

    /**
     * Go `ModifySite` → `nginx/modify_site`。
     *
     * 必传键：site_id, sitename, cdn, polling_algorithm, domain, ip_list, is_https,
     * privkey, fullchain。注意**没有 host 键**。
     *
     * 陷阱：
     *  - `is_https` 传 false 时后端**不会**关闭该站的 SSL（`if isHttps { data.IsSSL = isHttps }`），
     *    要关 SSL 必须走 `remove_site_ssl`。
     *  - 后端固定以 `sslName = ""` 调用 ModifySiteJson，`is_https=true` 时会把证书写到
     *    `SslPath + "/"` 根目录（而非按证书名建子目录）。这是后端既有行为，编辑站点时需留意。
     *  - `domain` / `ip_list` 必须是 JSON 数组。
     *
     * 副作用：重写站点 json + 重载 nginx；失败时后端会尝试 RestoreSite 回滚。
     */
    fun modifySite(
        siteId: String,
        sitename: String,
        domain: List<String>,
        ipList: List<String>,
        isHttps: Boolean,
        privkey: String,
        fullchain: String,
        cdn: Boolean,
        pollingAlgorithm: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/modify_site",
            mapOf(
                "site_id" to siteId,
                "sitename" to sitename,
                "domain" to domain,
                "ip_list" to ipList,
                "is_https" to isHttps,
                "privkey" to privkey,
                "fullchain" to fullchain,
                "cdn" to cdn,
                "polling_algorithm" to pollingAlgorithm
            ),
            onError,
            ::refreshSites
        )
    }

    /**
     * Go `ModifySiteInfo` → `nginx/modify_site_info`。
     *
     * 必传键：site_id, site_name。只改站点名，不动域名/回源。
     * 副作用：写站点配置。
     */
    fun modifySiteInfo(
        siteId: String,
        siteName: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/modify_site_info",
            mapOf("site_id" to siteId, "site_name" to siteName),
            onError,
            ::refreshSites
        )
    }

    /**
     * Go `DeleteSite` → `nginx/delete_site`。
     *
     * 必传键：site_id。
     *
     * **危险**：删除站点会连带清理反代配置与证书并重载 nginx，界面必须二次确认。
     * 副作用：删站点 + 重载 nginx + 备份 WAF 配置。
     */
    fun deleteSite(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act("nginx/delete_site", mapOf("site_id" to siteId), onError, ::refreshSites)
    }

    /**
     * Go `DeleteAllSite` → `nginx/delete_all_site`。
     *
     * **无参数、无二次确认、不可撤销。** 后端直接读 `conf.d/other/siteid.json`
     * 把里面所有站点逐个删掉（单个站点失败只 continue，不中断）。
     * 它甚至不写操作日志、也不备份 WAF 配置。
     *
     * 界面必须加二次确认弹窗，且文案要明确「将删除全部站点」。
     */
    fun deleteAllSite(onError: (String?) -> Unit = {}) =
        act("nginx/delete_all_site", emptyMap(), onError, ::refreshSites)

    /**
     * Go `GetRealTimeData` → `nginx/get_real_time_data`。
     *
     * 无参数。返回顶层数组，每项 `{ site_id, access_num, interception_num,
     * real_time_send, real_time_recv }`。后端并发拉取所有站点，单站失败降级为 0。
     */
    fun getRealTimeData(onError: (String?) -> Unit = {}) =
        query("nginx/get_real_time_data", onError = onError) { realTimeData = it.asList() }

    /**
     * Go `GetReturnSource` → `nginx/get_return_source`。
     *
     * 必传键：site_id（不存在返回 `site_id不存在`）。
     * 返回 public.UpstreamJson：`{ name, polling_algorithm, host, server: [String],
     * server_new: [UpstreamSingleInfo], enable_note, check_dns }`。
     */
    fun getReturnSource(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_return_source", mapOf("site_id" to siteId), onError) {
            returnSource = it.asMap()
        }
    }

    /**
     * Go `AddReturnSourceIp` → `nginx/add_return_source_ip`。
     *
     * 必传键（缺一即「参数错误」，共 10 个）：
     * site_id, source_ip, polling_algorithm, host, max_fails, fail_timeout, weight, status, ps。
     *
     * 陷阱：max_fails / fail_timeout / weight / ps 都是 **字符串**（InterfaceToString），
     * 只有 status 是 **整数**。ps 必传但可以传空串。
     *
     * 副作用：改回源节点配置并重载 nginx。
     */
    fun addReturnSourceIp(
        siteId: String,
        sourceIp: String,
        pollingAlgorithm: String,
        host: String,
        maxFails: String,
        failTimeout: String,
        weight: String,
        status: Int,
        ps: String = "",
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/add_return_source_ip",
            mapOf(
                "site_id" to siteId,
                "source_ip" to sourceIp,
                "polling_algorithm" to pollingAlgorithm,
                "host" to host,
                "max_fails" to maxFails,
                "fail_timeout" to failTimeout,
                "weight" to weight,
                "status" to status,
                "ps" to ps
            ),
            onError
        ) { getReturnSource(siteId) }
    }

    /**
     * Go `ModifyReturnSourceIp` → `nginx/modify_return_source_ip`。
     *
     * 必传键：在 Add 的 10 个基础上多 `source_id` 与 `old_status`（整数）。
     *
     * 陷阱：类型规则同 Add（除 status/old_status 为整数外都是字符串）。
     * 后端按 site_id + source_id 定位节点，传错 source_id 会改到别的节点或报节点不存在。
     *
     * 副作用：改回源节点配置并重载 nginx。
     */
    fun modifyReturnSourceIp(
        siteId: String,
        sourceId: String,
        sourceIp: String,
        pollingAlgorithm: String,
        host: String,
        maxFails: String,
        failTimeout: String,
        weight: String,
        status: Int,
        oldStatus: Int,
        ps: String = "",
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/modify_return_source_ip",
            mapOf(
                "site_id" to siteId,
                "source_id" to sourceId,
                "source_ip" to sourceIp,
                "polling_algorithm" to pollingAlgorithm,
                "host" to host,
                "max_fails" to maxFails,
                "fail_timeout" to failTimeout,
                "weight" to weight,
                "status" to status,
                "old_status" to oldStatus,
                "ps" to ps
            ),
            onError
        ) { getReturnSource(siteId) }
    }

    /**
     * Go `DelReturnSourceIp` → `nginx/del_return_source_ip`。
     *
     * 必传键：site_id, source_id, source_ip。
     * 陷阱：`source_ip` 只用于写操作日志，真正删除只用 site_id + source_id，
     * 但**仍必须传**，否则「参数错误」。
     *
     * 副作用：删回源节点并重载 nginx。
     */
    fun delReturnSourceIp(
        siteId: String,
        sourceId: String,
        sourceIp: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/del_return_source_ip",
            mapOf("site_id" to siteId, "source_id" to sourceId, "source_ip" to sourceIp),
            onError
        ) { getReturnSource(siteId) }
    }

    /**
     * Go `ReturnSource` → `nginx/return_source`。
     *
     * 必传键：site_id, polling_algorithm, host。
     * 陷阱：polling_algorithm 与 site_id 任一为空串都直接返回「参数错误」，
     * 所以 host 传空串没问题，polling_algorithm 不行。
     *
     * 副作用：整体重写回源配置 + 重载 nginx，失败时后端尝试 RestoreSite。
     */
    fun returnSource(
        siteId: String,
        pollingAlgorithm: String,
        host: String = "",
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        if (pollingAlgorithm.isBlank()) {
            onError("polling_algorithm 不能为空")
            return
        }
        act(
            "nginx/return_source",
            mapOf(
                "site_id" to siteId,
                "polling_algorithm" to pollingAlgorithm,
                "host" to host
            ),
            onError
        ) { getReturnSource(siteId) }
    }

    /**
     * Go `CheckReturnSourceAuth` → `nginx/check_return_source_auth`。
     *
     * 无参数。检查是否购买了三版本（`GetIsSpecifyVersion(3)`），
     * 成功返回字符串「授权检测成功」，失败返回「授权检测失败」。
     * 这是纯查询，但结果决定回源相关高级功能是否可用，界面应据此置灰入口。
     */
    fun checkReturnSourceAuth(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                repo.safeCall("nginx/check_return_source_auth")
                onResult(true, null)
            } catch (e: Exception) {
                error = e.message
                onResult(false, e.message ?: "授权检测失败")
            } finally {
                loading = false
            }
        }
    }

    // ================================================================== 域名

    /**
     * Go `AddDomain` → `nginx/add_domain`。
     *
     * 必传键：site_id, domain。**`domain` 是单数**，且必须是 **JSON 数组**。
     *
     * 陷阱：site_id 为 `default_wildcard_domain_server`（通配所有域名站点）时
     * 会被拒绝，需提示用户新建站点。
     *
     * 副作用：加域名 + 重载 nginx + 备份 WAF 配置。
     */
    fun addDomain(siteId: String, domain: List<String>, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act("nginx/add_domain", mapOf("site_id" to siteId, "domain" to domain), onError) {
            getDomain(siteId)
        }
    }

    /**
     * Go `DelDomain` → `nginx/del_domain`。
     *
     * 必传键：site_id, domain（单数 + JSON 数组）。
     * 陷阱：通配站点 `default_wildcard_domain_server` 不允许删域名，只能删站点。
     *
     * 副作用：删域名 + 重载 nginx。
     */
    fun delDomain(siteId: String, domain: List<String>, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act("nginx/del_domain", mapOf("site_id" to siteId, "domain" to domain), onError) {
            getDomain(siteId)
        }
    }

    /**
     * Go `GetDomain` → `nginx/get_domain`。
     *
     * 必传键：site_id。返回顶层数组，每项 `{ domain, port }`。
     * 陷阱：读站点 json 失败时后端返回 `null`（不是空数组），界面要按空态处理。
     */
    fun getDomain(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_domain", mapOf("site_id" to siteId), onError) {
            siteDomains = it.asList()
        }
    }

    /**
     * Go `GetAllDomain` → `nginx/get_all_domain`。
     *
     * 无参数。返回 `[{ site_id, site_name, create_time }]`，按创建时间倒序。
     */
    fun getAllDomain(onError: (String?) -> Unit = {}) =
        query("nginx/get_all_domain", onError = onError) { allDomains = it.asList() }

    /**
     * Go `GetAddDomainInfo` → `nginx/get_add_domain_info`。
     *
     * 必传键：**`domains`（复数！）**，且必须是 JSON 数组。
     * 这与 `AddDomain` 的单数 `domain` 不一致，极易写错。
     *
     * 重要：后端虽然构造了带 `default_config` 的 `res`，但最后 `return core.Success(result)`
     * 返回的是**未经加工的原始 result**。也就是说响应里**没有 default_config 字段**，
     * 界面不要依赖 `{ip, port, is_https}` 这组默认值，得自己按 `is_https` 推导端口
     * （http→80，https→443）。
     */
    fun getAddDomainInfo(domains: List<String>, onError: (String?) -> Unit = {}) {
        if (domains.isEmpty()) {
            onError("请先填写要添加的域名")
            return
        }
        query("nginx/get_add_domain_info", mapOf("domains" to domains), onError) {
            addDomainInfo = it.asList()
        }
    }

    /**
     * Go `GetDomainResponseStatus` → `nginx/get_domain_response_status`。
     *
     * 必传 7 个键：url, extranet_ip_list, intranet_ip_list, is_force_https,
     * http_protocol, is_ssl, is_ssl_check。两个 IP 列表必须是 **JSON 数组**。
     *
     * 陷阱（对 UI 最关键）：这个接口**失败时 res 仍是一个对象**而不是字符串：
     * `{ status: false, error: "源站有开启强制https…", resolver: 301, status_code: 200 }`。
     * 仓库的 `errorMessage()` 只在 res 为 String 时返回文案，所以走 `safeCall`
     * 只会拿到无意义的「请求失败 (code=…)」。这里因此改用 `repo.call` 手工拆包。
     *
     * 另有后端逻辑瑕疵：两个 IP 列表都为空时只把 status 置 false 并**不 return**，
     * 仍会继续做真实 HTTP 探测并可能追加错误文案。
     *
     * 副作用：无（只发探测请求，不改配置），但会阻塞最多约 9 秒 + 跟随一次重定向。
     */
    fun getDomainResponseStatus(
        url: String,
        intranetIpList: List<String> = emptyList(),
        extranetIpList: List<String> = emptyList(),
        isForceHttps: Boolean = false,
        httpProtocol: String = "http",
        isSsl: Boolean = false,
        isSslCheck: Boolean = true,
        onResult: (Map<String, Any?>, String?) -> Unit
    ) {
        if (url.isBlank()) {
            onResult(emptyMap(), "url 不能为空")
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.call(
                    "nginx/get_domain_response_status",
                    mapOf(
                        "url" to url,
                        "intranet_ip_list" to intranetIpList,
                        "extranet_ip_list" to extranetIpList,
                        "is_force_https" to isForceHttps,
                        "http_protocol" to httpProtocol,
                        "is_ssl" to isSsl,
                        "is_ssl_check" to isSslCheck
                    )
                )
                val data = r.asMap()
                val msg = data["error"].asString().ifBlank { r.errorMessage() }
                if (!r.isSuccess) error = msg
                onResult(data, if (r.isSuccess) null else msg)
            } catch (e: Exception) {
                error = e.message
                onResult(emptyMap(), e.message ?: "检测失败")
            } finally {
                loading = false
            }
        }
    }

    // ================================================================== 证书

    /**
     * Go `GetAllSsl` → `nginx/get_all_ssl`。
     *
     * 无参数。返回顶层数组，每项 SslInfo：`{ not_after, brand, Domains, fullchain,
     * privkey, ssl_name, force_https, is_https, ssl_ciphers, ssl_protocols }`。
     *
     * 陷阱：域名字段是 **大写 `Domains`**（Go tag 就是 `Domains`），不是 `domains`。
     * 另外响应里带回了 fullchain / privkey 明文，界面不要渲染到日志或崩溃上报里。
     */
    fun getAllSsl(onError: (String?) -> Unit = {}) =
        query("nginx/get_all_ssl", onError = onError) { allSsl = it.asList() }

    /**
     * Go `GetSslInfo` → `nginx/get_ssl_info`。
     *
     * 必传键：site_id。返回**单个对象**（不是数组），字段同 SslInfo。
     */
    fun getSslInfo(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_ssl_info", mapOf("site_id" to siteId), onError) {
            siteSslInfo = it.asMap()
        }
    }

    /**
     * Go `DeployCert` → `nginx/deploy_cert`。
     *
     * 必传键：site_id, ssl_name，两者为空串都返回「参数错误」。
     *
     * 副作用：把证书夹里的证书部署到站点（会 EnableHttps 级别的重写）+ 重载 nginx，
     * 失败时后端尝试 RestoreSite。属于会直接影响线上流量的操作，需二次确认。
     */
    fun deployCert(siteId: String, sslName: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        if (sslName.isBlank()) {
            onError("请选择证书")
            return
        }
        act(
            "nginx/deploy_cert",
            mapOf("site_id" to siteId, "ssl_name" to sslName),
            onError
        ) { getSslInfo(siteId) }
    }

    /**
     * Go `InstallCert` → `nginx/install_cert`。
     *
     * 必传键：privkey, fullchain, site_id。
     * 陷阱一：**`site_id` 这里是 JSON 数组**（`.([]interface{})`），可一次装多个站。
     * 陷阱二：注意 ParamsCheck 对 site_id 还会做一次 `site_info` 存在性查询，
     * 而传入值是数组，该查询的行为取决于底层驱动，数组站点 id 有被直接判为
     * `site_id不存在` 的风险——首次接入建议先用单元素数组验证。
     * 陷阱三：返回语义特殊。**只要有一个站点成功就返回 code=0**，
     * 且 res 是用 `</br>` 拼接的「成功站点 + 失败站点」混合文案。
     * 界面不能只看 code，必须把 res 拆开展示部分失败项。
     *
     * 副作用：逐站写证书 + 重载 nginx；部分站点失败时会 RestoreSite。
     */
    fun installCert(
        siteIds: List<String>,
        privkey: String,
        fullchain: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        if (siteIds.isEmpty()) {
            onResult(false, "请选择站点")
            return
        }
        if (privkey.isBlank() || fullchain.isBlank()) {
            onResult(false, "私钥与证书链不能为空")
            return
        }
        viewModelScope.launch {
            saving = true
            error = null
            try {
                val r = repo.call(
                    "nginx/install_cert",
                    mapOf("site_id" to siteIds, "privkey" to privkey, "fullchain" to fullchain)
                )
                val msg = r.res.asString()
                if (!r.isSuccess) error = msg
                onResult(r.isSuccess, if (r.isSuccess) null else msg)
            } catch (e: Exception) {
                error = e.message
                onResult(false, e.message ?: "安装失败")
            } finally {
                saving = false
            }
        }
    }

    /**
     * Go `RemoveSiteSsl` → `nginx/remove_site_ssl`。
     *
     * 必传键：site_id，且不能为空串。
     * 这是**关闭某站点 SSL 的正确入口**——`modify_site` 传 is_https=false 不会关 SSL。
     *
     * 副作用：移除站点证书 + 重载 nginx，会导致该站 HTTPS 立即不可用，需二次确认。
     */
    fun removeSiteSsl(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act("nginx/remove_site_ssl", mapOf("site_id" to siteId), onError) {
            getSslInfo(siteId)
        }
    }

    /**
     * Go `DeleteSsl` → `nginx/delete_ssl`。
     *
     * 必传键：ssl_name（注意**不是 site_id**），为空串返回「参数错误」。
     *
     * **危险**：删除的是证书夹里的证书实体，任何引用它的站点都会受影响，
     * 界面必须二次确认。副作用：删证书目录 + 重载 nginx + 备份 WAF 配置。
     */
    fun deleteSsl(sslName: String, onError: (String?) -> Unit = {}) {
        if (sslName.isBlank()) {
            onError("请选择证书")
            return
        }
        act("nginx/delete_ssl", mapOf("ssl_name" to sslName), onError) { getAllSsl(onError) }
    }

    /**
     * Go `GetSslProtocolsAndCiphers` → `nginx/get_ssl_protocols_and_ciphers`。
     *
     * 必传键：site_id。返回 4 个字段：
     *  - `ssl_protocols` / `ssl_ciphers`：系统支持的可选值
     *  - `current_ssl_protocols` / `current_ssl_ciphers`：该站当前生效值
     *
     * 注意可选值里 protocols 是列表、ciphers 是冒号分隔字符串，界面控件形态不同。
     */
    fun getSslProtocolsAndCiphers(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_ssl_protocols_and_ciphers", mapOf("site_id" to siteId), onError) {
            sslProtocolsAndCiphers = it.asMap()
        }
    }

    /**
     * Go `SetSslProtocolsOrCiphers` → `nginx/set_ssl_protocols_or_ciphers`。
     *
     * 必传键：site_id, ssl_protocols, ssl_ciphers。
     *
     * **核心类型陷阱（类型不对称）**：
     *  - `ssl_protocols` 必须是 **JSON 数组**（`.([]interface{})`，对应 Go []string）
     *  - `ssl_ciphers` 必须是 **冒号分隔的字符串**（InterfaceToString）
     * 两者不能互换，传错都是 500 panic。
     *
     * 副作用：改站点 TLS 配置，重载 nginx 后立即影响线上握手，界面需二次确认。
     */
    fun setSslProtocolsOrCiphers(
        siteId: String,
        sslProtocols: List<String>,
        sslCiphers: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        if (sslProtocols.isEmpty()) {
            onError("请至少选择一个安全协议")
            return
        }
        act(
            "nginx/set_ssl_protocols_or_ciphers",
            mapOf(
                "site_id" to siteId,
                "ssl_protocols" to sslProtocols,
                "ssl_ciphers" to sslCiphers
            ),
            onError
        ) { getSslProtocolsAndCiphers(siteId) }
    }

    /**
     * Go `SetForceHttps` → `nginx/set_force_https`。
     *
     * 必传键：site_id, force_https（布尔）。
     * 副作用：改站点强制跳转 HTTPS 配置 + 重载 nginx。开启后 HTTP 访问会被 301 到 HTTPS。
     */
    fun setForceHttps(siteId: String, forceHttps: Boolean, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/set_force_https",
            mapOf("site_id" to siteId, "force_https" to forceHttps),
            onError
        ) { getSslInfo(siteId) }
    }

    /**
     * Go `DownloadSsl` → `nginx/download_ssl`。
     *
     * 必传键：site_id。
     *
     * **未实现**：该接口返回的是二进制 zip 流（core.DownloadResponse），不是 JSON。
     * 现有 `WafRepository.safeCall` 会用 Gson 解析响应体，二进制流会被解析失败。
     *
     * 需要 Repository 增加二进制下载方法，例如：
     * ```
     * @Streaming
     * @POST("{path}")
     * suspend fun downloadBinary(
     *     @Path("path") path: String,
     *     @Body params: Map<String, Any?>
     * ): Response<ResponseBody>   // 读 Content-Disposition 拿文件名，读 body().bytes()
     * ```
     * 调用时 action = `nginx/download_ssl`，params = `{ "site_id": <siteId> }`。
     * 拿到 ByteArray 后用 ActivityResultContracts.CreateDocument 落盘，
     * 或写入应用私有目录后再分享。
     */
    fun downloadSsl(siteId: String, onError: (String?) -> Unit = { "暂不支持下载，请使用网页端" }) {
        if (!requireSiteId(siteId, onError)) return
        // TODO(ssl-download): 待 Repository 增加二进制下载方法后接入 nginx/download_ssl
        onError("证书下载返回二进制流，待 Repository 支持二进制下载后开放")
    }

    // ========================================================== TCP 负载均衡

    /**
     * Go `GetTcpLoadBalance` → `nginx/get_tcp_load_balance`。
     *
     * 无参数。返回的是 **nginx.json 的完整内容**（顶层 Map），
     * 端口转发配置在其中的 `tcp_load_balance` 字段，是一个
     * **以 load_balance_name 为 key 的 Map**（不是数组）。
     *
     * 陷阱：nginx.json 不存在时后端 `core.Success(nil)`，res 为 `null`，
     * 此时应展示空态而不是报错。
     */
    fun getTcpLoadBalance(onError: (String?) -> Unit = {}) =
        query("nginx/get_tcp_load_balance", onError = onError) { raw ->
            tcpLoadBalances = raw.asMap()["tcp_load_balance"].asMap()
                .mapValues { (_, v) -> v.asMap() }
        }

    /**
     * 构建 `add_tcp_load_balance` 的 node_info 单项。
     *
     * 键名与 Modify 版本**不同**：这里是 `node_weight` / `node_max_fails` /
     * `node_fail_timeout` / `node_status`，备注字段是 `ps`（没有 node_ 前缀），
     * 跟随开关是 `node_address_follow`。
     *
     * 全部值都走 `m["x"].(string)` 无检查断言，唯独 node_address_follow 是 `.(bool)`：
     *  - 这 7 个键**一个都不能少**，少一个直接 500 panic
     *  - node_status 必须是**字符串**（后端用 strconv.Atoi 解析），传 JSON 数字会 panic
     *  - node_address_follow 必须传 JSON 布尔，传 "0"/"1" 字符串会 panic
     */
    fun tcpNode(
        address: String,
        port: String,
        weight: String = "1",
        maxFails: String = "3",
        failTimeout: String = "10",
        status: String = "1",
        ps: String = "",
        addressFollow: Boolean = false
    ): Map<String, Any?> = mapOf(
        "node_address" to address,
        "node_port" to port,
        "node_weight" to weight,
        "node_max_fails" to maxFails,
        "node_fail_timeout" to failTimeout,
        "node_status" to status,
        "ps" to ps,
        "node_address_follow" to addressFollow
    )

    /**
     * Go `AddTcpLoadBalance` → `nginx/add_tcp_load_balance`。
     *
     * 必传键：protocol, listen_address, listen_port, max_timeout, not_timeout, ps, node_info。
     *
     * 校验顺序（严格按此顺序返回错误，文案可用于表单逐项标红）：
     *  1. protocol 必须是 `tcp` / `udp` / `tcp/udp`，否则「协议类型不正确」
     *  2. max_timeout 必须能 Atoi，否则「最大超时时间不正确」
     *  3. not_timeout 必须能 Atoi，否则「非超时时间不正确」
     *  4. listen_address 只能是 `127.0.0.1` 或 `0.0.0.0`，否则「监听地址不正确」
     *  5. 逐个校验 node_info（见 [tcpNode]）
     *  6. listen_port 必须是合法端口，否则「waf 服务端口不正确」
     *  7. 端口已被占用则「waf 服务端口已被占用」
     *
     * 陷阱：max_timeout / not_timeout 虽然后端按字符串解析，传 JSON 整数也能过 Atoi，
     * 但 listen_port 必须走 `validate.IsPort` 的字符串解析，**建议全部传字符串**。
     *
     * 副作用：写 nginx.json + tcp.conf + 重载 nginx + 改防火墙放行规则，影响面较大。
     */
    fun addTcpLoadBalance(
        protocol: String,
        listenAddress: String,
        listenPort: String,
        maxTimeout: String,
        notTimeout: String,
        ps: String,
        nodeInfo: List<Map<String, Any?>>,
        onError: (String?) -> Unit = {}
    ) {
        if (nodeInfo.isEmpty()) {
            onError("请至少配置一个后端节点")
            return
        }
        act(
            "nginx/add_tcp_load_balance",
            mapOf(
                "protocol" to protocol,
                "listen_address" to listenAddress,
                "listen_port" to listenPort,
                "max_timeout" to maxTimeout,
                "not_timeout" to notTimeout,
                "ps" to ps,
                "node_info" to nodeInfo
            ),
            onError
        ) { getTcpLoadBalance() }
    }

    /**
     * 构建 `modify_tcp_load_balance` 的 `load_info.node_address_map` 单项。
     *
     * 键名与 Add 版本**不同**：这里是 `weight` / `max_fails` / `fail_timeout` / `status`
     * （没有 node_ 前缀），`ps` 同名。
     * 对应 Go types.LoadNodeInfo 的 json tag。
     */
    fun loadInfoNode(
        address: String,
        port: String,
        weight: String = "1",
        maxFails: String = "3",
        failTimeout: String = "10",
        status: String = "1",
        ps: String = "",
        addressFollow: Boolean = false
    ): Map<String, Any?> = mapOf(
        "node_address" to address,
        "node_port" to port,
        "weight" to weight,
        "max_fails" to maxFails,
        "fail_timeout" to failTimeout,
        "status" to status,
        "ps" to ps,
        "node_address_follow" to addressFollow
    )

    /**
     * Go `ModifyTcpLoadBalance` → `nginx/modify_tcp_load_balance`。
     *
     * 必传键：load_balance_name, load_info。
     *
     * **核心类型陷阱**：`load_info` 必须是 **JSON 对象**（`map[string]interface{}`），
     * 传数组会直接命中 `if _, ok := params["load_info"].(map[string]interface{}); !ok`
     * 而返回「参数错误」。其结构对应 Go types.SingleTcpLoadBalance：
     * `{ protocol, listen_address, listen_port, max_timeout, not_timeout, ps,
     *    node_address_map: { "<地址>": <loadInfoNode> } }`
     *
     * 重要行为差异：
     *  - `load_info` **不包含 listen_port 生效逻辑**，端口取的是已存配置；
     *    响应文案里的端口也是旧端口。
     *  - `node_address_map` 只能**修改已存在的节点**（后端有 `if _, ok := m.NodeAddressMap[k]; ok`
     *    判断），**无法通过修改新增节点**，新增只能用 add。
     *  - load_balance_name 不存在时后端跳过整个 if 块，却仍返回「编辑成功」，
     *    界面应据此提示未真正生效。
     *  - 改 listen_address 或 protocol 时后端会 kill 进程并增删防火墙端口，
     *    期间该端口转发会短暂中断。
     */
    fun modifyTcpLoadBalance(
        loadBalanceName: String,
        protocol: String,
        listenAddress: String,
        maxTimeout: String,
        notTimeout: String,
        ps: String,
        nodeAddressMap: Map<String, Map<String, Any?>>,
        onError: (String?) -> Unit = {}
    ) {
        if (loadBalanceName.isBlank()) {
            onError("缺少 load_balance_name")
            return
        }
        act(
            "nginx/modify_tcp_load_balance",
            mapOf(
                "load_balance_name" to loadBalanceName,
                "load_info" to mapOf(
                    "protocol" to protocol,
                    "listen_address" to listenAddress,
                    "max_timeout" to maxTimeout,
                    "not_timeout" to notTimeout,
                    "ps" to ps,
                    "node_address_map" to nodeAddressMap
                )
            ),
            onError
        ) { getTcpLoadBalance() }
    }

    /**
     * Go `DelTcpLoadBalance` → `nginx/del_tcp_load_balance`。
     *
     * 必传键：load_balance_name, is_del_port, port。
     *
     * 陷阱（后端逻辑缺陷，务必知悉）：`is_del_port` 传什么都没用——
     * 只要 load_balance_name 命中，后端会**强制** `isDelPort = true` 并删除防火墙端口。
     * 保留该参数只是为了通过 ParamsCheck，固定传 true 即可。
     *
     * 另：`port` 必须是合法端口（否则「waf 服务端口不正确」），
     * 而 name 不存在时后端不删任何东西却仍返回「删除成功」。
     *
     * **危险**：删除端口转发会 kill 监听进程、删防火墙放行、删 tcp.conf 并重载 nginx，
     * 线上该端口业务立即中断。必须二次确认。
     */
    fun delTcpLoadBalance(
        loadBalanceName: String,
        port: String,
        onError: (String?) -> Unit = {}
    ) {
        if (loadBalanceName.isBlank()) {
            onError("缺少 load_balance_name")
            return
        }
        act(
            "nginx/del_tcp_load_balance",
            mapOf(
                "load_balance_name" to loadBalanceName,
                "is_del_port" to true,
                "port" to port
            ),
            onError
        ) { getTcpLoadBalance() }
    }

    /**
     * Go `ClearTcpLoadBalanceCount` → `nginx/clear_tcp_load_balance_count`。
     *
     * 必传键：load_balance_name。作用是把该转发的连接计数清零并刷新 count_time。
     * 副作用：写 nginx.json（不重载 nginx，不影响转发）。
     */
    fun clearTcpLoadBalanceCount(
        loadBalanceName: String,
        onError: (String?) -> Unit = {}
    ) {
        if (loadBalanceName.isBlank()) {
            onError("缺少 load_balance_name")
            return
        }
        act(
            "nginx/clear_tcp_load_balance_count",
            mapOf("load_balance_name" to loadBalanceName),
            onError
        ) { getTcpLoadBalance() }
    }

    // ================================================================== 日志

    /**
     * Go `GetSiteLog` → `nginx/get_site_log`。
     *
     * 参数用 struct 绑定（不是 ParamsCheck），键：site_id, types, clear。
     *  - site_id 在 site_info 表里查不到时返回 `查询site_id失败`
     *  - types 支持 `access` / `error` / **`slow`**，其他值**静默降级为 access**
     *    （不报错，界面传错也不会有提示）
     *  - clear 传 1 时执行清空并返回字符串「清空成功」，不返回日志正文
     *
     * 返回值是**纯文本字符串**（日志尾部最多 1000 行），不是 JSON 数组。
     * slow 类型下后端读的是站点 .log 主文件，与 access 实际同源。
     */
    fun getSiteLog(
        siteId: String,
        types: String = "access",
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        query(
            "nginx/get_site_log",
            mapOf("site_id" to siteId, "types" to types, "clear" to 0),
            onError
        ) { siteLog = it.asString() }
    }

    /**
     * Go `GetSiteLog` 的 clear 分支 → 同样是 `nginx/get_site_log`，clear=1。
     *
     * **副作用**：清空该站点对应类型的日志文件且不可恢复，需二次确认。
     */
    fun clearSiteLog(
        siteId: String,
        types: String = "access",
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/get_site_log",
            mapOf("site_id" to siteId, "types" to types, "clear" to 1),
            onError
        ) { getSiteLog(siteId, types) }
    }

    /**
     * Go `GetLogList` → `nginx/get_log_list`。
     *
     * 参数用 struct 绑定，键：site_id, types。
     *
     * 陷阱一：`types` **只接受 `access` 和 `error`**，传 `slow` 会返回
     * 「日志类型错误」——与 get_site_log 的枚举不一致，这里没有 slow。
     * 陷阱二：site_id 不存在同样返回 `查询site_id失败`。
     * 陷阱三：目录不存在时后端 `result, _ := n.getFilesInDirectory(...)` **吞掉了错误**，
     * res 为 `null`，界面按空态处理而不是报错。
     *
     * 返回 `[{ name, timestamp, size }]`，已按 timestamp 倒序。
     */
    fun getLogList(siteId: String, types: String = "access", onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        if (types != "access" && types != "error") {
            onError("日志类型仅支持 access 或 error")
            return
        }
        query("nginx/get_log_list", mapOf("site_id" to siteId, "types" to types), onError) {
            logFiles = it.asList()
        }
    }

    /**
     * Go `DownloadLog` → `nginx/download_log`，**删除分支**（delete=1）。
     *
     * 必传键：site_id, types, filename, download, delete 全部由 struct 绑定，
     * 但 site_id 不存在或 types 非 access/error 都会失败。
     *
     * 注意文件名字段是**全小写 `filename`**（与直觉的 fileName 不同）。
     *
     * 后端删除判断写反了：`os.Remove` 失败时才 return「删除成功」，
     * 成功删除则落到最后返回「操作成功」。两种情况都是 code=0，
     * 所以删除后建议重新 [getLogList] 确认文件确实消失。
     *
     * **危险**：删除历史日志不可恢复，需二次确认。
     */
    fun deleteLog(
        siteId: String,
        types: String,
        filename: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        if (filename.isBlank()) {
            onError("缺少文件名")
            return
        }
        act(
            "nginx/download_log",
            mapOf(
                "site_id" to siteId,
                "types" to types,
                "filename" to filename,
                "delete" to 1,
                "download" to 0
            ),
            onError
        ) { getLogList(siteId, types) }
    }

    /**
     * Go `DownloadLog` 的 download=1 分支。
     *
     * **未实现**：download=1 时后端返回 `core.DownloadFile(...)`，是二进制文件流，
     * 用 Gson 解析会失败。
     *
     * 需要 Repository 增加二进制下载方法（签名参考 [downloadSsl] 里的说明），
     * 调用时 action = `nginx/download_log`，params 为：
     * ```
     * { "site_id": <siteId>, "types": "access"|"error", "filename": <小写文件名>, "download": 1 }
     * ```
     * 另外当 download=0 且 delete=0 时该接口返回字符串「操作成功」，是个无副作用的空操作。
     */
    fun downloadLog(
        siteId: String,
        types: String,
        filename: String,
        onError: (String?) -> Unit = { "暂不支持下载，请使用网页端" }
    ) {
        if (!requireSiteId(siteId, onError)) return
        if (filename.isBlank()) {
            onError("缺少文件名")
            return
        }
        // TODO(log-download): 待 Repository 增加二进制下载方法后接入 nginx/download_log
        onError("日志下载返回二进制流，待 Repository 支持二进制下载后开放")
    }

    // ============================================================ 代理与其它

    /**
     * Go `ModifyProxyinfo` → `nginx/modify_proxyinfo`（注意 action 少一个 i）。
     *
     * 用 struct 绑定，键：site_id, proxy_connect_timeout, proxy_send_timeout,
     * proxy_read_timeout, client_max_body_size, client_body_buffer_size。
     * **都不在 ParamsCheck 里，所以缺失不会报「参数错误」**，
     * 但后端用零值覆盖：漏传就会把超时/包体限制清空。
     *
     * 陷阱：`client_body_buffer_size` 虽然能被绑定，但后端**从未写进配置**
     * （只写了 Client.MaxBodySize），传了无效，界面上不要提供该输入项。
     *
     * 另外 site_id 不存在时返回「网站不存在」（不是 site_id不存在）。
     *
     * 副作用：备份站点 json 与 vhost conf → 改配置 → 重载 nginx，失败会 RestoreFile。
     */
    fun modifyProxyinfo(
        siteId: String,
        proxyConnectTimeout: String,
        proxySendTimeout: String,
        proxyReadTimeout: String,
        clientMaxBodySize: String,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/modify_proxyinfo",
            mapOf(
                "site_id" to siteId,
                "proxy_connect_timeout" to proxyConnectTimeout,
                "proxy_send_timeout" to proxySendTimeout,
                "proxy_read_timeout" to proxyReadTimeout,
                "client_max_body_size" to clientMaxBodySize
            ),
            onError
        ) { getSiteSetting(siteId) }
    }

    /**
     * Go `GetSiteSetting` → `nginx/get_site_setting`。
     *
     * 必传键：site_id。返回单站完整设置，并保证 `waf_info.rewrite_url` 存在
     * （后端会补空对象），所以界面取该字段不必判空。
     */
    fun getSiteSetting(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_site_setting", mapOf("site_id" to siteId), onError) {
            siteSetting = it.asMap()
        }
    }

    /**
     * Go `GetReplaceOpen` → `nginx/get_replace_open`。
     *
     * 用 struct 绑定，只有 site_id，且为空串时返回「参数错误」。
     * 返回 `{ replace_open: true|false }`。
     */
    fun getReplaceOpen(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_replace_open", mapOf("site_id" to siteId), onError) {
            replaceOpen = it.asMap()["replace_open"] == true
        }
    }

    /**
     * Go `UserConfig` → `nginx/user_config`。
     *
     * 必传键：site_id, content。
     *
     * 陷阱：content 里包含以下任一 Lua 指令会被直接拒绝，返回
     * 「用户配置文件中包含非法指令」：init_by_lua, init_worker_by_lua,
     * ssl_certificate_by_lua, set_by_lua, rewrite_by_lua, access_by_lua,
     * content_by_lua, header_filter_by_lua, body_filter_by_lua, log_by_lua,
     * balancer_by_lua, preread_by_lua。
     * 界面应在提交前本地拦一次并给出具体命中的指令名。
     *
     * 副作用：写站点自定义配置 + 重载 nginx；失败时后端会删文件再 RestoreFile。
     * 错误的 nginx 语法会导致重载失败，务必提示用户。
     */
    fun userConfig(siteId: String, content: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        act("nginx/user_config", mapOf("site_id" to siteId, "content" to content), onError) {
            getUserConfig(siteId)
        }
    }

    /**
     * Go `GetUserConfig` → `nginx/get_user_config`。
     *
     * 必传键：site_id。返回**纯文本字符串**（配置文件内容），
     * 文件不存在时返回空串（不是 null）。
     */
    fun getUserConfig(siteId: String, onError: (String?) -> Unit = {}) {
        if (!requireSiteId(siteId, onError)) return
        query("nginx/get_user_config", mapOf("site_id" to siteId), onError) {
            userConfig = it.asString()
        }
    }

    /**
     * Go `ModifyResolver` → `nginx/modify_resolver`。
     *
     * 必传键：site_id, status, inspection_time。
     *  - status 是**整数**：1 开启巡检，其它值关闭
     *  - inspection_time 是**整数**（巡检间隔，按后端语义填秒）
     *
     * 陷阱：status=1 时若该站没有回源域名，返回
     * 「此站点无回源域名，无法开启自动巡检回源域名解析变化」，
     * 界面应在开启前先检查 [returnSource] 是否有 server/server_new。
     *
     * 副作用：写/删 site_return_domain_check 表记录。
     */
    fun modifyResolver(
        siteId: String,
        status: Int,
        inspectionTime: Int,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/modify_resolver",
            mapOf(
                "site_id" to siteId,
                "status" to status,
                "inspection_time" to inspectionTime
            ),
            onError
        ) { getReturnSource(siteId) }
    }

    /**
     * Go `SetListenIpvSixBySite` → `nginx/set_listen_ipv_six_by_site`。
     *
     * action 名是 `set_listen_ipv_six_by_site`（IpvSix 之间也有下划线），
     * **不是** `set_listen_ipv6`。
     *
     * 必传键：listen_ipv6（布尔）, site_id。
     * 副作用：直接 `os.WriteFile` 改站点 json（不经过 public.WriteFile）→ 重载 nginx；
     * 重载失败时**不会回滚**，配置已落盘，界面应提示用户手动确认站点状态。
     */
    fun setListenIpvSixBySite(
        siteId: String,
        listenIpv6: Boolean,
        onError: (String?) -> Unit = {}
    ) {
        if (!requireSiteId(siteId, onError)) return
        act(
            "nginx/set_listen_ipv_six_by_site",
            mapOf("site_id" to siteId, "listen_ipv6" to listenIpv6),
            onError
        ) { getSiteSetting(siteId) }
    }

    // ---------------------------------------------------------------- 刷新

    /** 站点类操作成功后的统一刷新。 */
    private fun refreshSites() {
        getSites()
    }
}
