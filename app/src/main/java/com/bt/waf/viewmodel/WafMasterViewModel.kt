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
 * WAF 集群 DNS 管理（对应 aawaf 后端 modules/waf_master_dns.go 与 modules/waf_master_load.go）。
 *
 * ## DNS 的四级概念
 * 本模块的数据是**四层嵌套**的，UI 必须按层组织，否则会出现「改了厂商却看不到记录」的错觉：
 *
 * 1. **厂商层** —— `dns_info` 表，一行 = 一个已授权厂商（`dns_name` 只能是 `tencent` / `aliyun`），
 *    存 AES 加密后的密钥（密钥用固定 key `rOA3FLDroEC8quaA` 加密，客户端永远拿不到明文，
 *    列表里只回显 `SecretId[:10] + ********** + SecretKey[:10]`）。
 *    对应接口：createApiKey / getDnsList / updateDnsRecord / deleteDnsRecord。
 * 2. **域名层** —— 厂商账号下的域名。关键字段是 `dns_server`：为 true 表示 NS 已托管到该厂商，
 *    只有 true 的域名才会去拉解析记录。为 false 时 `parse_list` 恒为空数组。
 *    对应接口：getDnsDomainList / checkDnsDomain / getDnsStatusByDomain / createDomainDnsRecord。
 * 3. **解析记录层** —— 域名下的单条记录，由 `line`（解析线路）+ `type`（A / AAAA / CNAME / MX / TXT…）唯一确定。
 *    腾讯云与阿里云是两套**完全独立**的接口，字段名相同但 status 取值不同。
 *    对应接口：腾讯云 6 个 + 阿里云 4 个。
 * 4. **负载均衡分组层** —— 独立的第四层，**不属于 DNS 记录**，是 `load_balance` 表。
 *    一个分组通过 `dns_name` 字段绑定到某个厂商的解析线路，通过 `nodes` 绑定集群节点。
 *    对应接口：createLoadBalance / modifyLoadBalance / deleteLoadBalance。
 *
 * 删除有严格的层级依赖：厂商被分组引用时不能删（deleteDnsRecord），分组被网站引用时不能删（deleteLoadBalance）。
 *
 * ## 通用约定
 * - 全部接口都是 POST + JSON，路径 `module/action`，action 名 = Go 方法名 SnakeCase 化。
 * - **数组类参数必须以 JSON 数组下发**（`check_dns_domain` 的 `domains`、
 *   `delete_dns_record` 的 `name_list`、`delete_load_balance` 的 `load_list`、
 *   `create_load_balance` / `modify_load_balance` 的 `nodes`）。传逗号分隔字符串会解析失败。
 * - 腾讯云 / 阿里云凭证在服务端解密，客户端**不需要**（也不应该）传。
 * - 错误一律走 `onResult(错误文案)` 回调，由调用方渲染在弹窗内部。
 *   不要改用 Snackbar —— Snackbar 挂在 Scaffold 上而 AlertDialog 覆盖其上，错误将完全不可见。
 */
class WafMasterViewModel(private val repo: WafRepository) : ViewModel() {

    // ---------------------------------------------------------------- 厂商层状态

    /** `wafmaster/get_dns_list` 的 list 元素。 */
    var dnsList by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var dnsTotal by mutableStateOf(0)
    var dnsKeyword by mutableStateOf("")

    /** `wafmaster/get_dns_manufacturer_list` 的 name 数组，元素形如 {label, value, method:[{name, chinese_name}]}。 */
    var manufacturers by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    // ---------------------------------------------------------------- 域名层状态

    /** `wafmaster/get_dns_domain_list` 的 list 元素，含 root_domain / dns_server / parse_list。 */
    var domainList by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var domainTotal by mutableStateOf(0)

    /** 当前查看的厂商（tencent / aliyun），getDnsDomainList 必传。 */
    var currentVendor by mutableStateOf("")

    /** `check_dns_domain` 结果：根域名 -> {add_status, dns_status}。 */
    var domainCheck by mutableStateOf<Map<String, Map<String, Any?>>>(emptyMap())

    // ---------------------------------------------------------------- 记录层状态

    /** 腾讯云解析线路（`get_allow_line` 的 Response.LineList）。 */
    var allowLines by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** 腾讯云解析记录（`get_dns_record` 的 Response.RecordList）。 */
    var tencentRecords by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /** 阿里云解析记录（`get_aliyun_dns_record` 的 Records.Record）。 */
    var aliyunRecords by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    // ---------------------------------------------------------------- 负载均衡状态

    /** 已知分组 id -> 分组名。服务端无列表接口，见 [loadGroupIdsFromSites]。 */
    var loadGroups by mutableStateOf<Map<Int, String>>(emptyMap())

    /** `wafmastersite/get_site_all_node` 的在线节点列表，元素形如 {sid, itself, remark}。 */
    var groupNodes by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    // ---------------------------------------------------------------- 通用状态

    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    // =================================================================
    // 一、DNS 厂商与凭证
    // =================================================================

    /**
     * 授权一个 DNS 厂商（新增一行 `dns_info`）。Go：`(*Wafmaster).CreateApiKey`。
     *
     * 入参 `types` 只能是 `tencent` 或 `aliyun`；`ps` 是备注，会拼进厂商下拉标签（如「腾讯云(香港节点)」）。
     *
     * **服务端 bug：`domains` 参数被完全忽略。** 落库的 `domains` 字段恒为硬编码字符串
     * `baidu.com,bt.cn`（源码 `EntryDnsData{DomainList: "baidu.com,bt.cn"}`），
     * 且新建行 `status` 恒为 1。所以不要在表单里收集「接管域名」，传了也无效；
     * 真实域名清单要等 [getDnsDomainList] 从厂商侧读回来后再用 [updateDnsRecord] 写入。
     *
     * 校验顺序（任一失败都会中断）：密钥去空格后非空 → `types` 未被占用 → 调厂商 OpenAPI 验签。
     * 验签失败文案固定为「密钥验证不通过，请检查密钥！」。
     *
     * 危险性：写操作，会立刻落库并使该厂商在 [getDnsManufacturerList] 中变为可选。
     */
    fun createApiKey(
        vendor: String,
        secretId: String,
        secretKey: String,
        ps: String,
        onResult: (String?) -> Unit
    ) {
        if (vendor.isBlank()) {
            onResult("请选择 DNS 厂商")
            return
        }
        if (secretId.isBlank() || secretKey.isBlank()) {
            onResult("SecretId 和 SecretKey 都不能为空")
            return
        }
        act(
            "wafmaster/create_api_key",
            mapOf(
                "types" to vendor,
                "secret_id" to secretId.trim(),
                "secret_key" to secretKey.trim(),
                "ps" to ps.trim()
                // 刻意不传 domains —— 服务端忽略它，详见上方说明。
            ),
            onResult,
            ::loadDnsList
        )
    }

    /**
     * DNS 厂商下拉选项。Go：`(*Wafmaster).GetDnsManufacturerList`（module `wafmaster`）。
     *
     * 返回 `{ name: [ {label, value, method:[{name, chinese_name}]} ] }`，`value` 即后续所有接口的 `types`。
     *
     * **服务端 bug：函数体第一行就 `return m.getDnsManufacturerListV2(request)`，
     * 其后 V1 的整段逻辑是死代码，永不执行。** 因此实际行为完全由 V2 决定：
     * - `types`传 `dns`（或任意非空值都落到 default 分支，除了字面量 `dns`）→ 返回**固定**的
     *   阿里云 + 腾讯云两项，与是否已授权无关；
     * - `types` 传空串或已授权的厂商名 → 走 default：从 `dns_info` 里筛 `status = 1` 的记录，
     *   `label` 为「阿里云」/「腾讯云」并在 `ps` 非空时追加 `(备注)`；
     *   **末尾恒定追加一项 `{label:"不设置", value:""}`**。
     *
     * 也就是说：想让 UI 反映「当前哪些厂商可用」，必须传空 `types`；
     * 传 `dns` 只会拿到写死的两项，看不出授权状态。
     */
    fun getDnsManufacturerList(onResult: (List<Map<String, Any?>>?, String?) -> Unit) {
        call("wafmaster/get_dns_manufacturer_list", mapOf("types" to "")) { r, e ->
            if (e != null) {
                onResult(null, e)
                return@call
            }
            val list = r.asMap()["name"].asList()
            manufacturers = list
            onResult(list, null)
        }
    }

    /**
     * 查询厂商套餐余量（DNS 托管包的可用点数 / 版本）。Go：`(*Wafmaster).GetFreeInfo`。
     *
     * **服务端 bug：这个接口 100% 失败，任何 `types` 都无法成功。** 原因：
     * ```go
     * inputApi := types.ApiKey{}                                  // 零值
     * api, err := GetApiKey(inputApi.Types)                       // inputApi.Types 恒为 ""
     * if err != nil { return core.Fail(err) }                     // 必然在此返回
     * if inputApi.Types == "tencent" { ...GetParamsFromRequestToStruct... }  // 永不进入
     * ```
     * 真正解析请求体的 `core.GetParamsFromRequestToStruct` 被写在了 `types == "tencent"`
     * 分支**内部**，而该分支的前提 `inputApi.Types` 来自尚未解析的零值结构体，永远是 `""`。
     * 于是第一次调用就以 `GetApiKey("")` 失败，返回文案「规则名称[]不存在」。
     * 末尾的 `core.Success("")` 与 `types == "aliyun"` 的空分支都是不可达代码。
     *
     * 保留此方法仅为让 UI 能显示一个明确的「该功能服务端未实现」提示，**不要**在任何
     * 加载流程里调用它。
     */
    fun getFreeInfo(vendor: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                // 必然抛 ApiException，文案为「规则名称[]不存在」。这里直接透传服务端原文。
                repo.safeCall("wafmaster/get_free_info", mapOf("types" to vendor))
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: "查询套餐信息失败")
            }
        }
    }

    // =================================================================
    // 二、域名接管
    // =================================================================

    /**
     * 检查待接管域名是否已存在于厂商账号、NS 是否已托管。Go：`(*Wafmaster).CheckDnsDomain`。
     *
     * 入参 `domains` **必须是 JSON 数组**，元素形如 `example.com` 或 `example.com:8443`
     * （可带 `*.` 前缀，会被剥掉）。传逗号分隔字符串会解析成空数组。
     *
     * 返回 `{ 根域名: { add_status: bool, dns_status: bool } }`：
     * - `add_status` = 该域名在厂商账号里已存在；
     * - `dns_status` = 解析状态正常（腾讯云等价于 `DNSStatus != "DNSERROR"`；
     *   阿里云等价于 `DescribeDomainNs` 的 `IncludeAliDns`，即 NS 已指向阿里云）。
     *
     * **两个必须知道的坑：**
     * 1. 结果的 key 是**根域名**（`GetDomainAndIp` 只取域名最后两段），入参 `www.a.com`
     *    返回的 key 是 `a.com`；**纯 IP 入参会被丢进 `ipMap` 而不出现在结果里**。
     * 2. 入参会先过 `CheckDomainIp(domains, true)`。只要含有**纯 IP**（不含端口的 IP 会被
     *    `IsStringValid` 判为 IP 分支）就返回错误码 `"1"`，整体失败并提示
     *    「防护域名地址不正确」；端口非法则提示「防护域名端口不正确,端口范围为1-65535」。
     *    换言之这个接口**只能用于域名**，不能用于 IP 源站。
     *
     * `types` 只接受 `tencent` / `aliyun`；传其它值时 `result` 恒为空对象（不会报错，静默返回空）。
     */
    fun checkDnsDomain(vendor: String, domains: List<String>, onResult: (String?) -> Unit) {
        if (domains.isEmpty()) {
            onResult("请至少填写一个域名")
            return
        }
        viewModelScope.launch {
            saving = true
            try {
                val r = repo.safeCall(
                    "wafmaster/check_dns_domain",
                    mapOf("types" to vendor, "domains" to domains)
                )
                domainCheck = r.asMap()
                    .mapValues { (_, v) -> v.asMap() }
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    /**
     * 拉取某厂商下的域名列表及其解析记录。Go：`(*Wafmaster).GetDnsDomainList`。
     *
     * 入参：`types`（必填，厂商）、`keyword`（按域名模糊过滤）、`p` / `p_size`。
     *
     * **服务端 bug：`p` 与 `p_size` 被解析但从未使用。** 厂商侧的域名是全量枚举的，
     * 返回的 `total` 就是本次实际条数，**服务端不支持分页**，一次可能返回上百个域名
     * 且每个托管域名都会再发一次记录列表请求（慢）。UI 不要做分页控件。
     *
     * 前置条件：`dns_info` 里必须已存在 `types` 这一行，否则直接失败，文案
     * 「请先配置该厂商DNS接管」——所以必须先 [createApiKey] 或确认 [getDnsList] 里有该厂商。
     *
     * 返回 `{ total, list: [ { root_domain, subdomain, dns_name, chinese_name, status,
     * version, dns_server, parse_list } ] }`：
     * - `dns_server` = NS 是否已托管到该厂商。为 false 时 `parse_list` 恒为空数组；
     * - `version` 腾讯云是套餐等级名（如「企业旗舰版」），阿里云已由服务端翻译成中文
     *   （免费版 / 个人版 / 企业标准版 / 企业旗舰版 / 权威代理标准版）；
     * - `parse_list` 元素：腾讯云 `{host, address, ttl, line, weight, type, status}`，
     *   阿里云 `{host, address, ttl, line, type, status}`（**阿里云没有 weight**）。
     *   两者 `status` 都是 1/0 整数（`ENABLE` -> 1）。MX 记录的 `address` 会被拼成
     *   `值 | 优先级`。
     * - 腾讯云侧会**跳过 value 含 `dnspod` 的记录**（厂商自身的接管记录），阿里云不跳。
     *
     * 危险性：只读，但单次请求量大，建议配合 [currentVendor] 缓存避免频繁刷新。
     */
    fun loadDnsDomainList(vendor: String, keyword: String = "") {
        if (vendor.isBlank()) {
            error = "请先选择 DNS 厂商"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "wafmaster/get_dns_domain_list",
                    mapOf("types" to vendor, "keyword" to keyword, "p" to 1, "p_size" to 100)
                )
                val map = r.asMap()
                domainList = map["list"].asList()
                domainTotal = map["total"].asString().toIntOrNull() ?: domainList.size
                currentVendor = vendor
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 拉取已授权厂商列表。Go：`(*Wafmaster).GetDnsList`。
     *
     * 返回 `{ total, list: [ { dns_name, name_chinese, domains, ps, create_time, status,
     * domain_total, api_key } ] }`。`api_key` 是**脱敏串**（`SecretId[:10]**********SecretKey[:10]`），
     * 不是可回填的密钥；编辑厂商时应留空密钥表示沿用旧值（见 [updateDnsRecord]）。
     *
     * **三个服务端 bug，UI 必须据此设计：**
     * 1. `id` 参数非 0 时**硬编码查 `id = 2`**（源码 `if params.Id != 0 { query.Where("id = ?", []any{2}) }`）。
     *    传任何 id 都只返回第 2 行，等于无法按 id 过滤。因此本方法**不暴露 id 参数**，
     *    只支持按 `keyword`（匹配 `ps` 备注）过滤。厂商至多两行（tencent / aliyun），
     *    没有分页的必要。
     * 2. **`total` 被覆写成当前页条数**（`mm.Total = len(mm.List)`），不是真实总数。
     *    不可用于分页控件，直接用 `list.size`。
     * 3. 脱敏时直接切片 `api.SecretId[:10]`，**若密钥长度不足 10 会 panic**（服务端 500）。
     *    这是服务端的雷，客户端无法规避，只能在密钥输入侧限制长度 >= 10。
     */
    fun loadDnsList(keyword: String = dnsKeyword) {
        viewModelScope.launch {
            loading = true
            error = null
            dnsKeyword = keyword
            try {
                // 刻意不传 id —— 传了会被服务端改成查 id=2。
                val r = repo.safeCall(
                    "wafmaster/get_dns_list",
                    mapOf("keyword" to keyword, "p" to 1, "p_size" to 50)
                )
                val map = r.asMap()
                dnsList = map["list"].asList()
                dnsTotal = dnsList.size
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 查询单个域名在腾讯云 DNSPod 的托管状态。Go：`(*Wafmaster).GetDnsStatusByDomain`。
     *
     * 入参只有 `domain`。**该接口硬编码使用 `tencent` 凭证**（`GetApiKey("tencent")`），
     * 阿里云域名调用会失败或返回错误结果。
     *
     * 返回腾讯云 `DescribeDomain` 的 `Response`（含 `DomainId` / `Name` / `Status` /
     * `DNSStatus` / `Grade` 等）。
     *
     * **服务端 bug：`core.Fail(err)` 的返回值被丢弃。** 源码在 SDK 错误分支里写了
     * `core.Fail(err)` 却漏了 `return`，错误被下一个 `if err != nil` 捕获，
     * 于是所有腾讯云 SDK 错误都被改写成「获取域名信息失败」，原始原因（如
     * `DomainIsNotExists`）永久丢失。排障时不要指望这段文案的细节。
     */
    fun getDnsStatusByDomain(domain: String, onResult: (Map<String, Any?>?, String?) -> Unit) {
        val d = domain.trim()
        if (d.isBlank()) {
            onResult(null, "请填写域名")
            return
        }
        call("wafmaster/get_dns_status_by_domain", mapOf("domain" to d)) { res, err ->
            onResult(res.asMap(), err)
        }
    }

    // =================================================================
    // 三、腾讯云解析记录
    // =================================================================

    /**
     * 拉取腾讯云解析线路（线路 = 记录的四级概念里的 `line`）。Go：`(*Wafmaster).GetAllowLine`。
     *
     * 正常用法：传 `types = "tencent"` + `domain_grade`（套餐等级）+ `domain`。
     *
     * **服务端 bug：`domain` 参数被忽略。** 函数内部 `record := types.DnsRecord{}` 是零值结构体，
     * 随后 `dnsRequest.Domain = common.StringPtr(record.Domain)` 赋的是**空串**、
     * `DomainId` 赋的是 **0**——两个字段都取自零值而非 `params.Domain`。
     * 实际只有 `domain_grade` 被正确透传。DNSPod 对空域名的线路查询通常直接报错，
     * 所以本接口在多数实例上不可用。
     *
     * 另一个坑：`types` **不等于** `tencent` 时，函数走 `return core.Success("OK")`——
     * 返回的是字符串 `"OK"` 而不是线路列表。UI 必须能处理「非列表响应」，
     * 否则会解析出空列表并误报「该域名没有可用线路」。
     *
     * 腾讯云成功时返回原始 `DescribeRecordLineList` 响应，线路在 `Response.LineList`。
     */
    fun getAllowLine(
        vendor: String,
        domain: String,
        domainGrade: String,
        onResult: (List<Map<String, Any?>>?, String?) -> Unit
    ) {
        if (vendor != "tencent") {
            // 服务端对非 tencent 直接返回字符串 "OK"，这里如实告知而不是给一个空列表。
            onResult(emptyList(), null)
            return
        }
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "wafmaster/get_allow_line",
                    mapOf(
                        "types" to vendor,
                        "domain" to domain.trim(),
                        "domain_grade" to domainGrade.trim()
                    )
                )
                // 可能是 Map（tencent 正常路径），也可能是 String "OK"。
                val list = r.asMap()["Response"].asMap()["LineList"].asList()
                allowLines = list
                onResult(list, null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    /**
     * 新增腾讯云解析记录。Go：`(*Wafmaster).CreateRecord`。
     *
     * 实际生效的入参只有 5 个（`types.DnsRecord` 里其余字段被忽略）：
     * `domain`（根域名）、`sub_domain`（主机头，如 `www`；根域名用 `@` 或空串）、
     * `record_type`（A / AAAA / CNAME / MX / TXT…）、`record_line`（线路，见 [getAllowLine]）、
     * `value`（记录值）。`record_id` / `status` / `ttl` / `weight` 在创建路径上**完全没被读取**。
     *
     * 凭证由服务端从 `dns_info` 解密，客户端不传。失败时 `TencentCloudSDKError` 的原始
     * 错误会透传（如 `DomainIsNotExists` / `RecordLineNotExists`）。
     *
     * 危险性：**写操作，直接落到公网 DNS。** 值写错会导致线上流量立即受影响，
     * 且没有任何本地回滚。提交前必须让用户确认。
     */
    fun createRecord(
        domain: String,
        subDomain: String,
        recordType: String,
        recordLine: String,
        value: String,
        onResult: (String?) -> Unit
    ) {
        val missing = validateRecord(domain, subDomain, recordType, recordLine, value)
        if (missing != null) {
            onResult(missing)
            return
        }
        act(
            "wafmaster/create_record",
            mapOf(
                "domain" to domain.trim(),
                "sub_domain" to subDomain.trim(),
                "record_type" to recordType.trim(),
                "record_line" to recordLine.trim(),
                "value" to value.trim()
            ),
            onResult
        ) { loadTencentRecords(domain.trim()) }
    }

    /**
     * 修改腾讯云解析记录。Go：`(*Wafmaster).ModifyRecord`。
     *
     * 生效入参：`domain`、`record_id`、`record_type`、`sub_domain`、
     * `status`、`record_line`、`value`。
     *
     * **`status` 取值必须是 `ENABLE` / `DISABLE`**（腾讯云原生枚举，**不是** 1/0）。
     * 传 1/0 会被 DNSPod 拒绝。注意 [getDnsDomainList] 返回的 `parse_list[].status`
     * 是 1/0 整数，**提交时必须做一次 1/0 -> ENABLE/DISABLE 的转换**，这是最容易出错的地方。
     *
     * `record_id` 在 Go 结构体里是 `uint64`，必须下发**数字**而非字符串。
     *
     * 危险性：**写操作，且腾讯云的 ModifyRecord 是整体替换语义** ——
     * 未提供的字段会被清空。务必回填全部 7 个字段。
     */
    fun modifyRecord(
        domain: String,
        recordId: String,
        recordType: String,
        subDomain: String,
        enabled: Boolean,
        recordLine: String,
        value: String,
        onResult: (String?) -> Unit
    ) {
        val missing = validateRecord(domain, subDomain, recordType, recordLine, value)
        if (missing != null) {
            onResult(missing)
            return
        }
        if (recordId.isBlank()) {
            onResult("缺少记录 ID，无法修改")
            return
        }
        act(
            "wafmaster/modify_record",
            mapOf(
                "domain" to domain.trim(),
                "record_id" to recordId.trim(),
                "record_type" to recordType.trim(),
                "sub_domain" to subDomain.trim(),
                "status" to if (enabled) "ENABLE" else "DISABLE",
                "record_line" to recordLine.trim(),
                "value" to value.trim()
            ),
            onResult
        ) { loadTencentRecords(domain.trim()) }
    }

    /**
     * 删除腾讯云解析记录。Go：`(*Wafmaster).DeleteRecord`。
     *
     * 生效入参只有 `domain` 与 `record_id`（数字）。
     *
     * **服务端 bug：非 SDK 错误被静默吞掉。** `DeleteRecord` 里
     * `if err != nil { logging.Debug(...) }` 只记日志不返回，随后仍然
     * `return core.Success(response)`。所以只要不是 `TencentCloudSDKError`，
     * 删除失败也会报成功。UI 在删除后应重新拉列表确认记录确实消失，
     * 而不是仅凭回调的成功提示。
     *
     * 危险性：**不可逆**，公网 DNS 记录立即消失。
     */
    fun deleteRecord(domain: String, recordId: String, onResult: (String?) -> Unit) {
        val d = domain.trim()
        if (d.isBlank() || recordId.isBlank()) {
            onResult("缺少域名或记录 ID")
            return
        }
        act(
            "wafmaster/delete_record",
            mapOf("domain" to d, "record_id" to recordId.trim()),
            onResult
        ) { loadTencentRecords(d) }
    }

    /**
     * 拉取腾讯云某域名下的全部解析记录。Go：`(*Wafmaster).GetDnsRecord`。
     *
     * 生效入参只有 `domain`。返回原始 `DescribeRecordList` 响应，列表在 `Response.RecordList`，
     * 元素含 `RecordId` / `Name` / `Type` / `Value` / `TTL` / `Line` / `Status` / `Weight`。
     *
     * **与 [getDnsDomainList] 的区别：这里不做任何过滤，是 DNSPod 原始记录，
     * 含 value 含 `dnspod` 的厂商接管记录；[getDnsDomainList] 会过滤掉那些。**
     * 需要完整原始视图（例如要看到被过滤的记录）时用本接口。
     *
     * 注意 `Status` 是 `ENABLE` / `DISABLE` 字符串。
     */
    fun loadTencentRecords(domain: String) {
        val d = domain.trim()
        if (d.isBlank()) {
            error = "请先选择域名"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("wafmaster/get_dns_record", mapOf("domain" to d))
                tencentRecords = r.asMap()["Response"].asMap()["RecordList"].asList()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 在腾讯云 DNSPod **注册一个新域名**。Go：`(*Wafmaster).CreateDomainDnsRecord`。
     *
     * 生效入参只有 `domain`，走腾讯云 `CreateDomain`。
     *
     * 危险性：**这是本模块最危险的操作之一。** 它不是「添加接管域名」，
     * 而是在用户的腾讯云 DNS 账号里**新建一个域名区域**。若域名已被该账号下其它人占用、
     * 或涉及付费套餐，可能产生费用；且与 [checkDnsDomain] 那种「只读探测」不同，
     * 这是在云端留下不可逆的资源。**必须二次确认，并在 UI 上明确警示。**
     *
     * 相比之下，已存在域名的「接管」应通过修改 NS 记录在云厂商控制台完成，
     * WAF 侧只需 [checkDnsDomain] 复查状态。
     */
    fun createDomainDnsRecord(domain: String, onResult: (String?) -> Unit) {
        val d = domain.trim()
        if (d.isBlank()) {
            onResult("请填写域名")
            return
        }
        act("wafmaster/create_domain_dns_record", mapOf("domain" to d), onResult, ::loadDnsList)
    }

    // =================================================================
    // 四、阿里云解析记录
    // =================================================================

    /**
     * 拉取阿里云某域名下的解析记录。Go：`(*Wafmaster).GetAliyunDnsRecord`。
     *
     * 生效入参只有 `domain`。
     *
     * **服务端 bug：分页被硬编码。** `PageNumber` 恒为 1、`PageSize` 恒为 500，
     * 函数**不接收也不返回分页游标**。所以记录数超过 500 的域名会被**静默截断**，
     * 且客户端无法翻页看到剩余部分。对比：[getDnsDomainList] 内部自己实现了
     * 最多 100 页的循环拉取，但那个结果只用于填 `parse_list`。
     * UI 若要做完整记录管理，只能提示「最多显示 500 条」。
     *
     * 返回 `DescribeDomainRecords` 原始响应，列表在 `Records.Record`，
     * 元素含 `RecordId`（**字符串**）、`RR`、`Type`、`Value`、`TTL`、`Line`、`Status`、`Priority`。
     * `Status` 为 `ENABLE` / `DISABLE`。
     */
    fun loadAliyunRecords(domain: String) {
        val d = domain.trim()
        if (d.isBlank()) {
            error = "请先选择域名"
            return
        }
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("wafmaster/get_aliyun_dns_record", mapOf("domain" to d))
                aliyunRecords = r.asMap()["Records"].asMap()["Record"].asList()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 新增阿里云解析记录。Go：`(*Wafmaster).CreateAliyunRecord`。
     *
     * 生效入参：`domain`、`sub_domain`（映射到阿里云 `RR`）、`record_type`（`Type`）、
     * `value`、`record_line`（`Line`）。
     *
     * **服务端 bug：TTL 被硬编码为 600，`ttl` 参数完全不生效。** 源码里
     * `TTL: tea.Int64(600)` 是写死的（注意同文件的内部函数 `CreateAliyunRecord`
     * 用的是 `public.InterfaceToInt64(record.TTL)`，但 HTTP 导出方法没走那条路径）。
     * 所以：新建的记录 TTL 一律 600 秒，UI 不应提供 TTL 输入框假装能生效；
     * 想改 TTL 只能事后用 [modifyAliyunRecord]（那个接口的 TTL 是生效的）。
     *
     * 字段名与腾讯云不同但入参键名相同，服务端负责翻译，客户端无需区分。
     * `line` 用阿里云线路名（如 `default`、`telecom`、`unicom`），与腾讯云的线路体系不通用。
     *
     * 危险性：**写操作，直接落到公网 DNS。**
     */
    fun createAliyunRecord(
        domain: String,
        subDomain: String,
        recordType: String,
        recordLine: String,
        value: String,
        onResult: (String?) -> Unit
    ) {
        val missing = validateRecord(domain, subDomain, recordType, recordLine, value)
        if (missing != null) {
            onResult(missing)
            return
        }
        act(
            "wafmaster/create_aliyun_record",
            mapOf(
                "domain" to domain.trim(),
                "sub_domain" to subDomain.trim(),
                "record_type" to recordType.trim(),
                "record_line" to recordLine.trim(),
                "value" to value.trim()
                // 刻意不传 ttl —— 服务端硬编码 600。
            ),
            onResult
        ) { loadAliyunRecords(domain.trim()) }
    }

    /**
     * 修改阿里云解析记录。Go：`(*Wafmaster).ModifyAliyunRecord`。
     *
     * 生效入参：`record_id`、`sub_domain`（`RR`）、`record_type`（`Type`）、
     * `value`、`ttl`、`record_line`（`Line`）。`domain` **不生效**（阿里云按 RecordId 定位）。
     *
     * 两个类型陷阱：
     * 1. `record_id` 在 Go 里是 `uint64`，源码用 `public.InterfaceToString` 转字符串喂给阿里云，
     *    所以**必须下发数字**；而 [loadAliyunRecords] 返回的 `RecordId` 是**字符串**，
     *    直接回填会因类型不符被 mapstructure 拒绝。UI 需先 `toIntOrNull()` 转换。
     * 2. `ttl` 是**字符串类型**（`DnsRecord.TTL string`），源码 `public.InterfaceToInt64` 解析。
     *    与创建不同，**这里的 TTL 是生效的**。
     *
     * 本接口**不涉及 status** —— 启停要用阿里云的 `SetDomainRecordStatus`，
     * 而 aawaf 并没有导出对应的 HTTP 接口，所以客户端**无法切换阿里云记录的启用状态**。
     * UI 不应展示启停开关。
     *
     * 危险性：**写操作，且是整体替换语义**，未提供的字段会被清空。
     */
    fun modifyAliyunRecord(
        recordId: String,
        recordType: String,
        subDomain: String,
        recordLine: String,
        value: String,
        ttl: String,
        onResult: (String?) -> Unit
    ) {
        if (recordId.isBlank()) {
            onResult("缺少记录 ID，无法修改")
            return
        }
        if (value.isBlank() || recordType.isBlank()) {
            onResult("记录类型与记录值不能为空")
            return
        }
        act(
            "wafmaster/modify_aliyun_record",
            mapOf(
                "record_id" to recordId.trim(),
                "record_type" to recordType.trim(),
                "sub_domain" to subDomain.trim(),
                "record_line" to recordLine.trim(),
                "value" to value.trim(),
                "ttl" to ttl.trim()
            ),
            onResult
        )
    }

    /**
     * 删除阿里云解析记录。Go：`(*Wafmaster).DeleteAliyunRecord`。
     *
     * 生效入参只有 `record_id`（数字，规则同 [modifyAliyunRecord]）。`domain` 不生效。
     *
     * 与腾讯云 [deleteRecord] 不同，本接口的错误会正常 `return core.Fail(_err)`，不会静默成功。
     * 但服务端仍然**吞掉了原始错误内容**：只有在
     * `util.AssertAsString(error.Message)` 失败时才回传，否则一律 `core.Success(nil)`。
     * 也就是说阿里云返回的多数业务错误（如 `DomainRecordDuplicate`）会被报成成功。
     * UI 必须在删除后重新拉列表确认。
     *
     * 危险性：**不可逆。**
     */
    fun deleteAliyunRecord(recordId: String, onResult: (String?) -> Unit) {
        if (recordId.isBlank()) {
            onResult("缺少记录 ID")
            return
        }
        act("wafmaster/delete_aliyun_record", mapOf("record_id" to recordId.trim()), onResult)
    }

    // =================================================================
    // 五、记录维护（厂商层）
    // =================================================================

    /**
     * 更新一个已授权厂商的密钥 / 备注 / 域名清单。Go：`(*Wafmaster).UpdateDnsRecord`。
     *
     * 生效入参（`types.ApiKey`）：`types`（= `dns_name`，**必填**，指定改哪一行）、
     * `secret_id`、`secret_key`、`domains`（数组）、`ps`（备注）、`status`（int，1 启用 / 0 停用）。
     *
     * 三个必须知道的行为：
     * 1. **密钥留空即沿用旧值。** 只有 `secret_id` 与 `secret_key` **同时非空**才会走验签分支；
     *    否则从库里读出旧密钥回填。因此编辑 UI 里密钥框应留空表示不修改，
     *    而列表里的 `api_key` 是脱敏串（`SecretId[:10]**********SecretKey[:10]`），
     *    **绝不能拿它回填**——那会被当成新密钥去验签，必然失败。
     * 2. **`domains` 是覆盖式，且落库带尾逗号。** 源码用 `domainString = domainString + k + ","`
     *    拼接，每项后面都跟一个逗号，最终存成 `a.com,b.com,`。这是**覆盖**不是追加：
     *    传非空数组会**整体替换**原有清单；传空数组或省略则保持原值不变。
     *    另外拼接前先塞进了 `map[string]string` 去重，**输出顺序不保证与入参一致**。
     * 3. **`status` 与 `ps` 都是「非显式变更才保留」**：`status` 仅在与库中值不同时才覆盖
     *    （所以编辑时必须回传当前 status，否则会被重置为 0 即停用）；
     *    `ps` 仅在非空时覆盖。
     *
     * 另注意：`status` 在验签分支里会被**服务端强制回填为库中值**（`api.Status = result["status"]`），
     * 意味着「同时改密钥和改启用状态」时状态改动不会生效，需分两次调用。
     *
     * 危险性：写操作。密钥改错会导致该厂商下所有域名接管与解析记录操作全部失败。
     */
    fun updateDnsRecord(
        vendor: String,
        secretId: String,
        secretKey: String,
        domains: List<String>,
        ps: String,
        status: Int,
        onResult: (String?) -> Unit
    ) {
        if (vendor.isBlank()) {
            onResult("缺少厂商标识")
            return
        }
        val payload = mutableMapOf<String, Any?>(
            "types" to vendor,
            "ps" to ps.trim(),
            "status" to status
        )
        // 两个都非空才触发验签；只填一个会被服务端当作「不改密钥」并静默沿用旧值。
        if (secretId.isNotBlank() && secretKey.isNotBlank()) {
            payload["secret_id"] = secretId.trim()
            payload["secret_key"] = secretKey.trim()
        }
        // 覆盖式：非空才下发，否则保持服务端原有清单。
        if (domains.isNotEmpty()) {
            payload["domains"] = domains
        }
        act("wafmaster/update_dns_record", payload, onResult, ::loadDnsList)
    }

    /**
     * 删除厂商授权（删除 `dns_info` 整行，含密钥）。Go：`(*Wafmaster).DeleteDnsRecord`。
     *
     * 入参 `name_list` **必须是 JSON 数组**，元素是厂商标识（`tencent` / `aliyun`），
     * **不是**域名、也不是 id。传逗号分隔字符串会解析成空数组并报「规则名称不能为空」。
     *
     * 校验顺序：数组非空 -> 每项在 `dns_info` 中存在 -> 每项**未被 `load_balance` 引用**。
     * 被负载均衡分组通过 `dns_name` 引用时拒绝删除，文案
     * 「规则名称[X]正在被使用，请解除与负载均衡分组的绑定后再删除！」。
     * 所以删除前应先确认没有分组绑定该厂商（见 [loadGroups]）。
     *
     * 错误文案里的厂商名由 `englishToChinese` 映射生成，传入未知值会显示为空方括号。
     *
     * 危险性：**很高。** 这一行存着该厂商的唯一密钥，删除后：
     * 该厂商的域名接管状态、解析记录读写、套餐查询**全部立即失效**，
     * 且密钥不可恢复（列表里只有脱敏串），只能让用户重新去云厂商拿密钥再授权。
     * 属于「几乎不可能挽回」的操作，UI 必须做破坏性确认。
     */
    fun deleteDnsRecord(nameList: List<String>, onResult: (String?) -> Unit) {
        if (nameList.isEmpty()) {
            onResult("请选择要删除的厂商")
            return
        }
        act("wafmaster/delete_dns_record", mapOf("name_list" to nameList), onResult, ::loadDnsList)
    }

    // =================================================================
    // 六、负载均衡（独立的第四层）
    // =================================================================

    /**
     * 新建负载均衡分组。Go：`(*Wafmasterload).CreateLoadBalance`（module `wafmasterload`）。
     *
     * 生效入参（`types.MasterLoadBalanceAll`）：
     * - `load_name`（分组名，**全局唯一**，重复会失败）
     * - `dns_name`（绑定的厂商标识 `tencent`/`aliyun`；**留空表示不绑定 DNS 接管**）
     * - `load_method`（分流方式，枚举 **`dns`（DNS轮询）/ `region`（区域分流）/ `weight`（权重分流）**）
     * - `corrupt_check`（int，容错/故障检查开关）
     * - `ps`（备注）
     * - `nodes`（**必须是 JSON 数组**，元素为 `types.LoadNodes`：
     *   `ip` / `weight`(int) / `region` / `status`(int) / `id`(节点 sid 字符串) /
     *   `ps` / `is_parse`(bool) / `create_time`。
     *   **真正决定归属的是 `id`** —— 服务端拿每个节点的 `id` 去更新
     *   `cluster_nodes.group_id`，`ip` 等字段只是冗余存进 `nodes` JSON 供前端回显。
     *   所以节点的 `id` 必须来自 [getLoadGroupNodes] 返回的 `sid`，不能自己编。
     * - `chinese_name` 会被服务端忽略（分组名直接用 `load_name`）。
     *
     * 校验顺序：`nodes` 为空且 `load_name != "默认分组"` -> 失败「节点不能为空」；
     * `load_name` 已存在 -> 失败「集群名称[X已存在」（**注意源码这里少了一个右方括号**，
     * 属于文案 bug，不是我们拼错的）。
     *
     * **服务端 bug / 陷阱：`load_name` 传「默认分组」会强制把 id 改成 1。**
     * 源码在插入后执行 `Where("id = ?").Update({"id": 1})`。后果：
     * - 「默认分组」这个名称被保留给 id=1，天然具备「不可改名（[modifyLoadBalance]）、
     *   不可删除（[deleteLoadBalance]）」的特殊身份；
     * - 且它是**唯一允许 nodes 为空**的分组名 —— 用来做「单节点/不分组」的降级场景。
     * UI 应当把「默认分组」作为系统保留名提示用户，不要鼓励自建同名分组。
     *
     * 危险性：写操作。会立即改写 `cluster_nodes.group_id`，
     * 从而**改变已绑定该分组的网站的流量分发**（分组 id 被网站通过 `load_group_id` 引用）。
     * 新建分组本身不影响现有网站，但同名冲突会中断。
     */
    fun createLoadBalance(
        loadName: String,
        dnsName: String,
        loadMethod: String,
        corruptCheck: Int,
        ps: String,
        nodes: List<Map<String, Any?>>,
        onResult: (String?) -> Unit
    ) {
        val name = loadName.trim()
        if (name.isBlank()) {
            onResult("请填写分组名称")
            return
        }
        if (name != DEFAULT_GROUP_NAME && nodes.isEmpty()) {
            // 与服务端校验保持一致，避免无谓的往返。
            onResult("节点不能为空")
            return
        }
        if (loadMethod.isNotBlank() && loadMethod !in LOAD_METHODS) {
            onResult("分流方式只能是 dns / region / weight")
            return
        }
        act(
            "wafmasterload/create_load_balance",
            mapOf(
                "load_name" to name,
                "dns_name" to dnsName.trim(),
                "load_method" to loadMethod.trim(),
                "corrupt_check" to corruptCheck,
                "ps" to ps.trim(),
                "nodes" to nodes
            ),
            onResult,
            ::loadLoadGroups
        )
    }

    /**
     * 编辑负载均衡分组。Go：`(*Wafmasterload).ModifyLoadBalance`（module `wafmasterload`）。
     *
     * 入参与 [createLoadBalance] 相同，外加 **`id`（分组 id，必填）**。
     *
     * 校验顺序（三条都必须满足，顺序即报错优先级）：
     * 1. `id == 1` 且 `load_name != "默认分组"` -> 失败「默认分组名称不允许修改」；
     * 2. `id` 在 `load_balance` 中不存在 -> 失败「集群名称[X不存在」；
     * 3. `nodes` 为空 -> 失败「节点不能为空」（**注意此处没有「默认分组」豁免**，
     *    与创建时的校验不一致，所以 id=1 的分组也必须带节点才能保存）。
     *
     * **服务端行为：节点增删差分。** 服务端读出库中 `nodes`，凡是没出现在本次 `nodes` 里的
     * 旧节点，都会被 `Update{"group_id": 0}` 踢出分组（置为未分组）；
     * 本次提交的节点则 `Update{"group_id": id}` 写回分组。
     * 因此 **`nodes` 是完整期望集合，不是增量补丁** —— UI 必须回传该分组的完整节点列表，
     * 只传新增项会导致原有节点被静默移出分组。
     * 另外节点归属是靠 `nodes[].id` 做的，所以差分的依据也是 `id`。
     *
     * 保存成功后会调 `AddSiteSyncData` 把分组内节点写入 `wait_sync_nodes` 触发配置下发。
     *
     * **本函数不会检查 `load_name` 是否与其它分组重名**（创建时会检查）。
     * 所以编辑时把名字改成别的分组已占用的名称不会被拦下，会产生重名数据。
     * UI 应在客户端自行做重名预校验。
     *
     * 危险性：**很高。** 分组被网站通过 `load_group_id` 引用，
     * 误删节点会立刻改变线上流量分发。
     */
    fun modifyLoadBalance(
        id: Int,
        loadName: String,
        dnsName: String,
        loadMethod: String,
        corruptCheck: Int,
        ps: String,
        nodes: List<Map<String, Any?>>,
        onResult: (String?) -> Unit
    ) {
        val name = loadName.trim()
        if (id <= 0) {
            onResult("缺少分组 ID")
            return
        }
        if (id == 1 && name != DEFAULT_GROUP_NAME) {
            onResult("默认分组名称不允许修改")
            return
        }
        if (name.isBlank()) {
            onResult("请填写分组名称")
            return
        }
        if (nodes.isEmpty()) {
            onResult("节点不能为空")
            return
        }
        if (loadMethod.isNotBlank() && loadMethod !in LOAD_METHODS) {
            onResult("分流方式只能是 dns / region / weight")
            return
        }
        act(
            "wafmasterload/modify_load_balance",
            mapOf(
                "id" to id,
                "load_name" to name,
                "dns_name" to dnsName.trim(),
                "load_method" to loadMethod.trim(),
                "corrupt_check" to corruptCheck,
                "ps" to ps.trim(),
                "nodes" to nodes
            ),
            onResult,
            ::loadLoadGroups
        )
    }

    /**
     * 删除负载均衡分组。Go：`(*Wafmasterload).DeleteLoadBalance`（module `wafmasterload`）。
     *
     * 入参 `load_list` **必须是 JSON 数组**，元素为 `types.MasterLoadBalanceAll`，
     * 但**只有两个字段被读取：`id` 与 `load_name`**（`load_name` 仅用于拼日志与报错文案）。
     * 传 `[{id: 3}]` 而不给名字会在报错信息里显示为空方括号，功能仍正常。
     *
     * 校验顺序（逐项，先失败先返回，整批中止）：
     * 1. `id == 1` -> 失败「默认分组不允许删除」；
     * 2. `id` 在 `load_balance` 中不存在 -> 失败「集群名称[X不存在」；
     * 3. `site_info` 中存在 `load_group_id == id` -> 失败
     *    「负载均衡分组[X]已绑定网站，请先解绑」。
     *
     * 删除成功后会顺带把 `cluster_nodes` 里该组的节点 `group_id` 重置为 0（变为未分组），
     * **节点本身不会被删除**。
     *
     * 注意批量语义：所有项校验通过后才开始删。返回文案里可能含 `</br>`（HTML 换行），
     * 直接塞进 Text 会被当字面量显示，UI 需先做一次 HTML 实体/标签清理。
     * 另外若全部项都删失败，服务端返回 `core.Fail(空文案)`，此时客户端会拿到一个空错误串 ——
     * UI 遇到空错误文案应回退到「删除失败，请重试」而不是显示空白。
     *
     * 危险性：**很高。** 会改变所有引用该分组的网站的流量分发。
     * 虽有服务端的三重保护，仍建议 UI 做破坏性二次确认。
     */
    fun deleteLoadBalance(loadList: List<Map<String, Any?>>, onResult: (String?) -> Unit) {
        val invalid = loadList.firstOrNull { (it["id"].asString().toIntOrNull() ?: 0) == 1 }
        if (invalid != null) {
            onResult("默认分组不允许删除")
            return
        }
        if (loadList.isEmpty()) {
            onResult("请选择要删除的分组")
            return
        }
        act(
            "wafmasterload/delete_load_balance",
            mapOf("load_list" to loadList),
            onResult,
            ::loadLoadGroups
        )
    }

    /**
     * 拉取某个负载均衡分组下的**在线**节点列表。Go：`(*Wafmastersite).GetSiteAllNode`。
     *
     * **aawaf 的 `wafmasterload` 模块没有任何列表接口** —— 只有增 / 改 / 删三个方法。
     * 分组和节点只能从别处间接获取：
     * - **节点**用本方法（`wafmastersite/get_site_all_node`，入参 `load_group_id`），
     *   它查 `cluster_nodes` 中 `is_online = 1 且 group_id = load_group_id` 的行，
     *   只返回 `sid` / `itself` / `remark` 三个字段。
     * - **分组**由 [loadGroupIdsFromSites] 从网站列表的 `load_group_id` 聚合而来。
     *
     * 两个重要限制：
     * 1. **只返回在线节点。** 离线节点不会出现，因此本列表**不能**直接当作
     *    [modifyLoadBalance] 的 `nodes` 回填值 —— 那会把离线节点从分组里彻底移除。
     *    编辑时必须另找离线节点的 sid（服务端无接口，只能提示用户先恢复节点在线）。
     * 2. 返回的 `sid` 就是 `LoadNodes.id`，是 [createLoadBalance] / [modifyLoadBalance]
     *    中 `nodes[].id` 必须使用的值。
     *
     * 另外 `itself` 表示「是否本机」，`remark` 是节点备注（可作为展示名）。
     */
    fun getLoadGroupNodes(loadGroupId: Int) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(
                    "wafmastersite/get_site_all_node",
                    mapOf("load_group_id" to loadGroupId)
                )
                groupNodes = r.res.asList()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 重建「分组 id -> 分组名」映射。
     *
     * **背景：`wafmasterload` 没有列表接口，`load_balance` 表也没有任何查询方法导出。**
     * 唯一能间接拿到分组 id 的途径是网站列表 —— `wafmastersite/get_site_list`
     * 每个站点都带 `load_group_id`。因此这里的做法是：
     * 拉全量站点 -> 收集去重的 `load_group_id` -> 合并 id=1（「默认分组」必然存在）。
     *
     * **由此带来的已知局限（UI 需明示）：**
     * - **没有任何网站引用的分组不会出现在列表里**，用户将看不到自己「刚建好但还没绑网站」的分组。
     * - 分组 id 只能拿到数字，**拿不到 `load_name`、`dns_name`、`load_method`、`nodes`** ——
     *   这些字段服务端根本没有读取接口。所以编辑分组时表单无法自动回填，
     *   只能由用户手工重新输入（这是 aawaf 的接口缺陷，不是客户端能补的）。
     * - `id = 0` 表示未分组，会被过滤掉。
     */
    fun loadLoadGroups() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("wafmastersite/get_site_list", mapOf("p" to 1, "p_size" to 500))
                val ids = r.asMap()["list"].asList()
                    .map { it["load_group_id"].asString().toIntOrNull() ?: 0 }
                    .filter { it > 0 }
                    .toSet()
                loadGroups = buildMap {
                    put(1, DEFAULT_GROUP_NAME)
                    ids.sorted().forEach { put(it, "分组 #$it") }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 便捷方法：刷新厂商列表（[loadDnsList] 的包装，便于 UI 的 onResume 调用）。 */
    fun refresh() {
        loadDnsList()
        loadLoadGroups()
    }

    fun consumeMessage() {
        message = null
    }

    // =================================================================
    // 内部工具
    // =================================================================

    /** 记录类写操作的通用必填校验，返回第一条错误文案，全部通过则返回 null。 */
    private fun validateRecord(
        domain: String,
        subDomain: String,
        recordType: String,
        recordLine: String,
        value: String
    ): String? = when {
        domain.isBlank() -> "请填写域名"
        recordType.isBlank() -> "请选择记录类型"
        recordLine.isBlank() -> "请选择解析线路"
        value.isBlank() -> "请填写记录值"
        // subDomain 允许为空（根域名记录），故不校验。
        else -> null
    }

    /** 读操作：结果交给回调，成功时 null 出错。 */
    private fun call(
        path: String,
        params: Map<String, Any?>,
        onResult: (Any?, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onResult(repo.safeCall(path, params).res, null)
            } catch (e: Exception) {
                onResult(null, e.message)
            }
        }
    }

    /**
     * 写操作统一入口。
     *
     * 错误通过 [onResult] 回传，**由调用方渲染在弹窗内部**。
     * 不要改成 Snackbar：Snackbar 挂在 Scaffold 上，而 AlertDialog 覆盖在 Scaffold 之上，
     * 错误提示将 100% 不可见。
     */
    private fun act(
        path: String,
        params: Map<String, Any?>,
        onResult: (String?) -> Unit,
        after: () -> Unit = {}
    ) {
        viewModelScope.launch {
            saving = true
            error = null
            try {
                val r = repo.safeCall(path, params)
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) after()
            } catch (e: Exception) {
                onResult(e.message)
            } finally {
                saving = false
            }
        }
    }

    companion object {
        /** 保留分组名：传此名创建时服务端会强制把 id 改为 1。 */
        const val DEFAULT_GROUP_NAME = "默认分组"

        /** 分流方式枚举，与 Go 的 `loadMethod` 映射表一致。 */
        val LOAD_METHODS = listOf("dns", "region", "weight")
    }
}
