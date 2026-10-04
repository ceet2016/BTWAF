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
 * 防护规则模块（aawaf `modules/waf_rules.go`，Go 结构体名 `Wafrules`）。
 *
 * 路由规则：`/api/wafrules/<action>`，action 为导出方法名的 snake_case 版本。
 * 例：`SetSiteRunMode` → `wafrules/set_site_run_mode`，`ApplyAll` → `wafrules/apply_all`。
 *
 * ========================= 全局约定（源码核对结论） =========================
 *
 * 1. 全部接口 POST + application/json。`public.ParamsCheck` 只校验**键是否存在**，
 *    不校验是否为空；但键名为 `site_id` 且值非空时会额外查 site_info 表，
 *    站点不存在直接返回 `site_id不存在`。
 *
 * 2. **本模块大量使用无检查类型断言**：把 params 里的值直接断言成 map、
 *    float64、bool 或数组。类型不符不会返回「参数错误」，而是 panic → HTTP 500，
 *    界面拿到的是无意义的错误文案。
 *    因此：对象类参数必须传 JSON 对象、数值类必须传 JSON 数字（不能传字符串）、
 *    数组类必须传 JSON 数组（传逗号串会命中数组断言而 panic）。
 *
 * 3. **多个方法的 `type` 参数不在 ParamsCheck 列表里，但函数体无条件读取**。
 *    漏传就是 `nil` 断言 panic（500）。本文件所有带 type 的方法都做了枚举校验，
 *    传错枚举值在客户端就被拦下，不发请求。
 *
 * 4. **大量「假成功」**：后端把错误用 `core.Success(...)` 返回（code=0）。
 *    最典型的是 UserLimit 三兄弟：文件不存在或 id 找不到时返回字符串
 *    `没有此条规则`，而 code 仍是 0。本文件把写操作的 res 原文记在 [lastMessage]，
 *    界面必须读它才能识别这类「成功但没生效」。
 *
 * 5. 写操作副作用几乎全部包含：`public.UpdateWafConfig("config", 2)` +
 *    `UpdateWafConfig("rule", 2)`（向 127.0.0.251 提交配置更新，等价重载防护规则）
 *    以及 `public.BackupWebWafConfig()`。**不需要 nginx reload，但会即时影响防护行为。**
 *
 * 6. 授权限制：`public.GetAuthInfo() < 1` 时，开启以下功能会返回 code=403
 *    `授权网站数量不足`——智能CC、IDC限制、护网（只读模式）、地区限制启用、
 *    非 site_city 类型的地区限制新增。
 *
 * 7. 模块里还有一个 `SimulateAttack`（→ `wafrules/simulate_attack`），
 *    返回带 site_id / url / mode 的对象数组，与本文件实现的 `SimulatedAttack`
 *    （→ `wafrules/simulated_attack`，返回纯字符串数组）**是两个不同接口**，
 *    按需另接。
 *
 * ========================= 破坏性操作清单（UI 必须二次确认） =========================
 *
 * - `recoverDefaultConfig(type = "site")`：把整站规则**重置为出厂默认值**，
 *   仅保留 idc / smart_cc / cdn / cdn_baidu / readonly 五项，其余用户配置全部丢失。
 * - `recoverDefaultConfig(type = "global")`：**什么都不改，只写操作日志**（假成功）。
 * - `applyAll`：用全局规则**覆盖所有站点**的 cc 或 number_attacks，不可撤销。
 * - `applyGlobalRulesToSite`：用全局规则覆盖选中站点的某一类规则，逐站写盘。
 * - `setHvv(open = true)`：开启只读（护网）模式，后端文案明确说明会**影响登录、
 *   支付、搜索、注册、评论**等功能，属于会影响线上业务的开关。
 * - `setFileScanRules` / `setFileUploadRules(type = "global")`：改的是全局模板，
 *   影响之后所有按全局初始化的站点。
 * - `resetRegionalCountryRules`：把地区规则命中次数清零（统计数据丢失）。
 * - `delRegionalRestrictionsRules` / `delRewriteUrl` / `delHttpHeaderLenRules`：
 *   删除类，注意 region_id 与 rewrite_url 的匹配语义（见各自 KDoc）。
 * - `addUserLimit`：**当 limit.json 读取或反序列化失败时，后端会直接用「只含新规则」
 *   的数组覆盖整个文件**，等于清空已有全部限流规则。
 */
class RulesViewModel(private val repo: WafRepository) : ViewModel() {

    // ---------------------------------------------------------------- 状态

    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)

    /**
     * 最近一次请求的错误文案（成功时为 null）。
     * 写接口的错误请渲染在弹窗内部，**不要用 Snackbar**：
     * Snackbar 挂在 Scaffold 上会被 AlertDialog 完全遮住。
     */
    var error by mutableStateOf<String?>(null)

    /** 专供弹窗内展示的表单错误，与 [error] 同步更新。 */
    var formError by mutableStateOf<String?>(null)

    /**
     * 最近一次**成功**响应里 res 为字符串时的原文。
     * 用途：识别 code=0 的假成功，例如 UserLimit 系列返回的 `没有此条规则`。
     */
    var lastMessage by mutableStateOf<String?>(null)

    /**
     * 后端用字符串「获取数据为空」代替缺失对象的地方（get_http_method_type_rules、
     * get_http_header_len_rules）。非 null 时表示该配置项尚未初始化，界面按空态处理。
     */
    var emptyNotice by mutableStateOf<String?>(null)

    /** wafrules/get_global_rules：全局规则 config.json 全量内容。 */
    var globalRules by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** wafrules/get_rules：单站规则 site.json 里该 site_id 的子树。 */
    var siteRules by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** wafrules/get_http_method_type_rules：HTTP 方法开关，键为大写方法名。 */
    var methodTypeRules by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** wafrules/get_http_header_len_rules：请求头长度上限，键为小写头部名。 */
    var headerLenRules by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** wafrules/get_spider_rules：蜘蛛池，内容是纯字符串数组（不是对象数组）。 */
    var spiderRule by mutableStateOf<List<String>>(emptyList())

    /** wafrules/simulated_attack：为每个站点拼出的 SQL 注入模拟攻击 URL。 */
    var simulatedUrls by mutableStateOf<List<String>>(emptyList())

    /**
     * wafrules/get_regional_restrictions：国家/地区字典（config/citys.json）的**原样**内容。
     * 后端未对它做任何结构变换，形状随版本变化，所以这里保留原始 Any?，
     * 界面按 Map 或 List 两种可能分别渲染，不要假定它是数组。
     */
    var regionCatalogRaw by mutableStateOf<Any?>(null)

    /** wafrules/get_regional_restrictions_rules：地区限制规则分页列表。 */
    var regionRules by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var regionRuleTotal by mutableStateOf(0)

    /** wafrules/get_user_limit：流量限制（限流）规则列表。 */
    var userLimits by mutableStateOf<List<Map<String, Any?>>>(emptyList())

    /**
     * wafrules/apply_global_rules_to_site 的 res 原文。
     * 该接口 code=0 但 res 里同时拼了「应用到以下网站失败」的站点名，
     * 必须展示 [applyResultMessage] 才能让用户知道部分站点没生效。
     */
    var applyResultMessage by mutableStateOf<String?>(null)

    // ------------------------------------------------------------ 内部工具

    /**
     * 只读查询：把 res 原始值交给 onData，错误文案交给 onError。
     * 回调形式是为了让错误渲染在弹窗内部而不是走 Snackbar。
     */
    private fun rvQuery(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        onError: (String?) -> Unit,
        onData: (Any?) -> Unit
    ) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall(action, params)
                // 刻意不写 lastMessage：那个槽位只给写操作，用来识别
                // code=0 的假成功（如「没有此条规则」）。读接口如果也往里写，
                // 后续写操作就会读到上一次查询的残留文案而误判未生效。
                onData(r.res)
                onError(null)
            } catch (e: Exception) {
                val msg = e.message ?: "请求失败"
                error = msg
                formError = msg
                onError(msg)
            } finally {
                loading = false
            }
        }
    }

    /**
     * 写操作：成功回 null，失败回错误文案。
     *
     * 用 [WafRepository.call] 而不是 safeCall，是为了同时拿到两样东西：
     *  1. `isSuccess`（aawaf 会在 HTTP 200 + code 不为 0 时表达业务错误）
     *  2. res 原文（识别 code=0 的假成功，例如 `没有此条规则`）
     */
    private fun rvAct(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        onResult: (String?) -> Unit,
        after: () -> Unit = {}
    ) {
        viewModelScope.launch {
            saving = true
            error = null
            formError = null
            try {
                val r = repo.call(action, params)
                if (r.res is String) lastMessage = r.res as String
                if (r.isSuccess) {
                    onResult(null)
                    after()
                } else {
                    val msg = r.errorMessage().ifBlank { "操作失败" }
                    error = msg
                    formError = msg
                    onResult(msg)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "操作失败"
                error = msg
                formError = msg
                onResult(msg)
            } finally {
                saving = false
            }
        }
    }

    /** site_id 非空校验：漏传会被后端当成「键存在但值为空」而跳过存在性查询。 */
    private fun rvNeedSiteId(siteId: String, onError: (String?) -> Unit): Boolean {
        if (siteId.isBlank()) {
            onError("site_id 不能为空")
            return false
        }
        return true
    }

    /**
     * type 枚举校验。这些枚举值后端都是直接拿去索引 map 或当文件路径用的，
     * 传错不会得到「参数错误」，而是 panic(500) 或写到错误的目标文件。
     */
    private fun rvNeedType(type: String, allowed: List<String>, label: String, onError: (String?) -> Unit): Boolean {
        if (type !in allowed) {
            onError("$label 仅支持 ${allowed.joinToString(" / ")}")
            return false
        }
        return true
    }

    /**
     * 组装 UserLimit 系列的 `region` 对象。
     * 后端用「键是否存在」来区分限制方式：传 `req` 走每秒请求数限制，
     * 传 `count` 走时间窗口次数限制。两者都不传会返回「请选择一个限制方式」。
     * 注意 `count` 内部的 `time` 与 `count` 两个键都必须存在，否则后端返回
     * 「时间访问限制-时间不能小于30秒」/「时间访问限制次数不能小于20次」。
     */
    private fun rvLimitRegion(req: Int?, countTime: Int?, countCount: Int?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (req != null) m["req"] = req
        if (countTime != null && countCount != null) {
            m["count"] = mapOf("time" to countTime, "count" to countCount)
        }
        return m
    }

    /**
     * 组装 SetCrawlerInfos 的 `encryption` / `watermark` 子对象。
     * 三个键 open / type / text 都被后端无检查断言，**一个都不能少**。
     * type 只接受 `default`（默认加密/水印）或 `text`（自定义文本）。
     */
    private fun rvCrawlerPart(open: Boolean, type: String, text: String): Map<String, Any?> =
        mapOf("open" to open, "type" to type, "text" to text)

    /**
     * 组装 SetCcRulesWait 的 `wait` 子对象。
     * 六个键全部被无检查断言，且 time / qps / user 是 `.(float64)`，
     * **必须传 JSON 数字，传字符串会 panic**。
     * type 只接受 `default` 或 `text`。
     */
    private fun rvWaitPart(
        open: Boolean,
        time: Int,
        qps: Int,
        user: Int,
        type: String,
        text: String
    ): Map<String, Any?> = mapOf(
        "open" to open,
        "time" to time,
        "qps" to qps,
        "user" to user,
        "type" to type,
        "text" to text
    )

    // ====================================================== 全局规则 / 站点规则

    /**
     * Go `GetGlobalRules` → `wafrules/get_global_rules`。
     *
     * 无参数。返回全局规则 config.json 的完整内容（顶层对象），
     * 典型字段：cc、number_attacks、sql、xss、file_upload、from_data、php_eval、
     * ssrf、download、user_agent、scan、file_import、file_scan、cookie、
     * method_type、header_len、no_browser、host、black_status、database、
     * machine_verify_type、cc_uri_frequency。
     *
     * 失败只有一种：所有候选配置文件都不存在或读不出来，返回 `未找到配置文件`。
     *
     * 单 URL 频率规则（`cc_uri_frequency`）没有独立查询接口，
     * 只能从这里的返回值里取。
     */
    fun getGlobalRules(onResult: (Map<String, Any?>?, String?) -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val map = repo.safeCall("wafrules/get_global_rules", emptyMap()).asMap()
                globalRules = map
                onResult(map, null)
            } catch (e: Exception) {
                val msg = e.message ?: "请求失败"
                error = msg
                onResult(null, msg)
            } finally {
                loading = false
            }
        }
    }

    /**
     * Go `GetRules` → `wafrules/get_rules`。
     *
     * 必传键：`site_id`（站点不存在返回 `site_id不存在`）。
     *
     * **注意：site_id 在 site.json 里找不到时后端不报错，返回空对象。**
     * 所以拿到空 map 要么是站点没有规则，要么是站点 ID 不在防护配置内，
     * 界面应按空态处理而不是报错。
     *
     * 返回结构里界面常用的字段：mode、cc、smart_cc、wait、number_attacks、
     * cookie、download、scan、file_import、file_upload、from_data、sql、xss、
     * ssrf、user_agent、php_eval、readonly、idc、rce、cdn、cdn_baidu、
     * cdn_header、rewrite_url、crawler。
     *
     * `rewrite_url` 是 map，形态为「新 URL → 原 URL」（**与入参方向相反**，见 setRewriteUrl）。
     */
    fun getRules(siteId: String, onError: (String?) -> Unit = {}) {
        if (!rvNeedSiteId(siteId, onError)) return
        rvQuery("wafrules/get_rules", mapOf("site_id" to siteId), onError) {
            siteRules = it.asMap()
        }
    }

    /**
     * Go `SetSiteRunMode` → `wafrules/set_site_run_mode`。
     *
     * 必传键：`site_id`, `mode`（整数）。mode 枚举来自 `public.SiteMode`：
     * 0 关闭防护模式 / 1 只记录模式 / 2 拦截模式 / 3 显示维护页模式。
     *
     * 陷阱：mode 不在枚举里不会报错，操作日志里的模式名会变成空字符串，
     * 但配置里的 mode 值仍会被写成那个非法数字。**本方法在客户端就拦掉非法值。**
     *
     * 另：site_id 不在 site.json 里时后端跳过写入，却仍返回「操作成功」（假成功）。
     *
     * 副作用：写 site.json + UpdateWafConfig(config) + UpdateWafConfig(rule) + 备份配置。
     * mode=3 维护页会直接对外返回维护页面，属于影响线上的操作。
     */
    fun setSiteRunMode(
        siteId: String,
        mode: Int,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (mode !in 0..3) {
            onResult("mode 仅支持 0 关闭 / 1 只记录 / 2 拦截 / 3 维护页")
            return
        }
        rvAct(
            "wafrules/set_site_run_mode",
            mapOf("site_id" to siteId, "mode" to mode),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `SetSmartCcRules` → `wafrules/set_smart_cc_rules`。
     *
     * 必传键：`site_id`, `open`（JSON 布尔）。
     *
     * 陷阱一：`open = true` 时后端先查授权，`GetAuthInfo() < 1` 直接返回
     * code=403 `授权网站数量不足`。
     * 陷阱二：site_id 不在 site.json 里返回 `siteId不存在`。
     * 陷阱三：**该站还没有 smart_cc 节点时，开启会用后端默认值创建**
     * （status 444、max_avg_proxy_time 2000、max_err_count 5、expire 120、
     * max_qps 10、ip_drop_time 360），用户先前配置过但被删掉的参数不会保留。
     * 智能 CC 的阈值要在别处调整，本接口只切开关。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份配置。
     */
    fun setSmartCcRules(
        siteId: String,
        open: Boolean,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        rvAct(
            "wafrules/set_smart_cc_rules",
            mapOf("site_id" to siteId, "open" to open),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `SetCcRules` → `wafrules/set_cc_rules`（基础 CC 防护）。
     *
     * 必传键（8 个，缺一即 `参数错误`）：
     * `site_id`, `status`（整数，拦截响应码，如 444）, `cycle`（统计周期，秒）,
     * `open`（布尔）, `limit`（周期内请求上限）, `endtime`（拦截时长，秒）,
     * `cc_type_status`（整数，CC 类型：0 关闭 / 1 观察 / 2 标准 / 3 严格 语义）,
     * `type`（目标：`global` 全局 / `not_global` 单站完整 / `site` 单站仅开关）。
     *
     * 可选键：`is_cc_url`（布尔）。后端判空后读取，**不传就等价于 false**，
     * 且传 false 会把已有配置覆盖成 false，不是「保持不变」。
     *
     * **关键差异（UI 必须区分）**：
     *  - `type = "global"` 写 config.json 的 cc 节点。
     *  - `type = "not_global"` 写 site.json 的 cc 节点，7 个字段全写。
     *  - `type = "site"` **只写 open 一个字段**，其余 6 个参数被丢弃。
     *    所以「只想起始/关闭某站 CC」用 site，「完整配置」用 not_global。
     *
     * 陷阱：`type` 为其他值时 filePathS 查不到路径，
     * `ReadInterfaceFileBytes("")` 报错；本方法已在客户端拦掉。
     * site.json 里没有该 site_id 时，not_global / site 分支静默跳过却仍返回成功。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。`type = "global"` 影响所有站点。
     */
    fun setCcRules(
        siteId: String,
        status: Int,
        cycle: Int,
        open: Boolean,
        limit: Int,
        endTime: Int,
        ccTypeStatus: Int,
        type: String,
        isCcUrl: Boolean = false,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "not_global", "site"), "type", onResult)) return
        rvAct(
            "wafrules/set_cc_rules",
            mapOf(
                "site_id" to siteId,
                "status" to status,
                "cycle" to cycle,
                "open" to open,
                "limit" to limit,
                "endtime" to endTime,
                "cc_type_status" to ccTypeStatus,
                "type" to type,
                "is_cc_url" to isCcUrl
            ),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `SetRetryRules` → `wafrules/set_retry_rules`（攻击次数拦截）。
     *
     * 必传键：`site_id`, `retry_cycle`, `retry`, `retry_time`（三个都是整数）。
     *
     * **陷阱：`type` 不在 ParamsCheck 列表里，但函数体无条件读取**
     * （直接对 params 里取到的 type 值做接口断言），漏传就是 nil 断言 panic（500）。
     * 取值只有 `global`（写 config.json 的 number_attacks）或
     * `not_global`（写 site.json 的 number_attacks）。
     *
     * site.json 里没有该 site_id 时 not_global 分支静默跳过，仍返回「操作成功」。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。
     */
    fun setRetryRules(
        siteId: String,
        retryCycle: Int,
        retry: Int,
        retryTime: Int,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "not_global"), "type", onResult)) return
        rvAct(
            "wafrules/set_retry_rules",
            mapOf(
                "site_id" to siteId,
                "retry_cycle" to retryCycle,
                "retry" to retry,
                "retry_time" to retryTime,
                "type" to type
            ),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `SetCdnHeaders` → `wafrules/set_cdn_headers`（CDN 回源 IP 请求头白名单）。
     *
     * 必传键：`site_id`, `cdn_header`（单个头名，如 `x-forwarded-for`）, `type`。
     * `type` 只有 `add` 与 `del`。
     *
     * 校验与陷阱：
     *  - 站点配置里没有 `cdn_header` 节点时返回 `cdn_header不存在`。
     *  - `add` 重复添加同名的头返回 `该cdn_header已存在`。
     *  - `del` 删一个不存在的头**不会报错**，静默返回「操作成功」（假成功）。
     *  - 后端用无检查断言取 站点 json 里对应的节点，若该 site_id 不在 site.json 里
     *    会 panic 500。传 allsite 之类不存在的 ID 前请先确认。
     *
     * 现有 cdn_header 列表从 [siteRules] 的 `cdn_header` 字段取（字符串数组）。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。
     * 删错请求头会导致真实客户端 IP 取不到，进而所有基于 IP 的防护（CC、地区限制、
     * 恶意 IP 库、IDC 限制）失效，属于高危操作。
     */
    fun setCdnHeaders(
        siteId: String,
        cdnHeader: String,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("add", "del"), "type", onResult)) return
        if (cdnHeader.isBlank()) {
            onResult("cdn_header 不能为空")
            return
        }
        rvAct(
            "wafrules/set_cdn_headers",
            mapOf("site_id" to siteId, "cdn_header" to cdnHeader, "type" to type),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `SetCdnRules` → `wafrules/set_cdn_rules`。
     *
     * 必传键：`site_id`, `cdn`（布尔）, `cdn_baidu`（布尔）, `type`。
     * `type` 取 `not_global` 或 `site`。
     *
     * **关键差异**：`type = "site"` 时后端**只写 `cdn`，`cdn_baidu` 被丢弃**；
     * `not_global` 才两个都写。要改百度 CDN 兼容必须用 `not_global`。
     *
     * 错误文案：
     *  - 站点 json 读不出来：`站点配置获取败：` + 原始错误
     *  - 回写站点 json 失败：`站点配置生成失败：` + 原始错误
     *
     * 副作用：本模块里少数会**重新生成整个站点配置文件**（`ParseSiteJson`）的接口，
     * 随后两次 UpdateWafConfig + 备份。开启 CDN 后 WAF 取真实 IP 的方式会改变，
     * 需二次确认。
     */
    fun setCdnRules(
        siteId: String,
        cdn: Boolean,
        cdnBaidu: Boolean,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("not_global", "site"), "type", onResult)) return
        rvAct(
            "wafrules/set_cdn_rules",
            mapOf(
                "site_id" to siteId,
                "cdn" to cdn,
                "cdn_baidu" to cdnBaidu,
                "type" to type
            ),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `SetModeRules` → `wafrules/set_mode_rules`（单类防护项的防护级别）。
     *
     * 必传键：`site_id`, `mode`（整数）, `modename`（配置里的节点名）, `type`。
     * `type` 取 `global` 或 `site`。
     *
     * `modename` 就是全局配置里的键名，源码默认值里存在的有：
     * sql、xss、ssrf、download、scan、file_import、file_upload、from_data、
     * user_agent、php_eval、cookie。**传一个不存在的节点名会 panic 500**，
     * 因为后端直接对全局配置里 modename 指向的节点做 map 断言。
     *
     * `mode` 语义来自 `public.SiteWafMode`：
     * 0 关闭防护模式 / 1 观察模式 / 2 标准模式 / 3 严格模式。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。`type = "global"` 影响所有站点。
     */
    fun setModeRules(
        siteId: String,
        mode: Int,
        modeName: String,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "site"), "type", onResult)) return
        if (modeName !in RULE_MODE_KEYS) {
            onResult("modename 仅支持 ${RULE_MODE_KEYS.joinToString(" / ")}")
            return
        }
        rvAct(
            "wafrules/set_mode_rules",
            mapOf(
                "site_id" to siteId,
                "mode" to mode,
                "modename" to modeName,
                "type" to type
            ),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `SetFileUploadRules` → `wafrules/set_file_upload_rules`（拦截恶意文件上传）。
     *
     * 必传键：`site_id`, `status`（整数，拦截响应码）, `mode`（整数，同 SiteWafMode）,
     * `type`（`global` 或 `site`）。两个 type 都会同时写 mode 与 status。
     *
     * file_upload 节点必须已存在于对应配置文件里，否则 panic 500。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。`type = "global"` 影响所有站点。
     */
    fun setFileUploadRules(
        siteId: String,
        status: Int,
        mode: Int,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "site"), "type", onResult)) return
        rvAct(
            "wafrules/set_file_upload_rules",
            mapOf(
                "site_id" to siteId,
                "status" to status,
                "mode" to mode,
                "type" to type
            ),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `SetFileScanRules` → `wafrules/set_file_scan_rules`（目录扫描防御）。
     *
     * 必传键：`mode`（整数）, `limit`（周期内目录数上限）, `cycle`（统计周期，秒）。
     * **没有 site_id，也没有 type**——这个接口只改全局 config.json 的 file_scan。
     *
     * 副作用：全局生效 + 两次 UpdateWafConfig + 备份。属于影响所有站点的改动。
     */
    fun setFileScanRules(
        mode: Int,
        limit: Int,
        cycle: Int,
        onResult: (String?) -> Unit = {}
    ) {
        rvAct(
            "wafrules/set_file_scan_rules",
            mapOf("mode" to mode, "limit" to limit, "cycle" to cycle),
            onResult
        ) { getGlobalRules { _, _ -> } }
    }

    /**
     * Go `ApplyAll` → `wafrules/apply_all`（把全局规则下发到所有站点）。
     *
     * 必传键：`type`，只有 `cc`（基础 CC 防护）与 `number_attacks`（攻击次数拦截）。
     *
     * 前置校验：
     *  - siteid.json 读不到或为空 → `没有站点`
     *  - 全局配置里缺对应节点 → `没有全局规则`
     *
     * **高危**：会用全局值**覆盖每一个站点**的 cc / number_attacks 节点，
     * 各站点原有的差异化配置全部丢失，且没有撤销入口。UI 必须二次确认。
     *
     * 陷阱：type 传其他值时 switch 不命中，**不做任何覆盖但仍写一遍 site.json
     * 并返回「操作成功」**（假成功）。本方法已在客户端拦掉。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。
     */
    fun applyAll(
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedType(type, listOf("cc", "number_attacks"), "type", onResult)) return
        rvAct("wafrules/apply_all", mapOf("type" to type), onResult)
    }

    // ==================================================== 请求方法 / 请求头长度

    /**
     * Go `GetHttpMethodTypeRules` → `wafrules/get_http_method_type_rules`。
     *
     * 无参数。返回 config.json 的 `method_type` 节点，形如
     * `{ "POST": true, "GET": true, "PUT": true, ... }`，键是大写方法名。
     *
     * **假成功陷阱**：配置里没有 method_type 时，后端返回的是字符串
     * `获取数据为空`（code 仍为 0），不是空对象。本方法会把该文案记入
     * [emptyNotice] 并把 [methodTypeRules] 置空，界面按空态渲染。
     */
    fun getHttpMethodTypeRules(onError: (String?) -> Unit = {}) {
        rvQuery("wafrules/get_http_method_type_rules", onError = onError) { raw ->
            emptyNotice = (raw as? String)?.takeIf { it.isNotBlank() }
            methodTypeRules = raw.asMap()
        }
    }

    /**
     * Go `SetHttpMethodTypeRules` → `wafrules/set_http_method_type_rules`。
     *
     * 必传键：`type`（单个 HTTP 方法名，**大小写按配置里的键，通常大写**）, `status`（布尔）。
     *
     * 陷阱：方法名不在配置里时后端只是新增一个键，不会报错；
     * 配置里没有 method_type 节点时整个写入被跳过，却仍返回「操作成功」（假成功）。
     * 建议先调 getHttpMethodTypeRules 拿到实际键列表再提交。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。全局生效。
     */
    fun setHttpMethodTypeRules(
        type: String,
        status: Boolean,
        onResult: (String?) -> Unit = {}
    ) {
        if (type.isBlank()) {
            onResult("type 不能为空")
            return
        }
        rvAct(
            "wafrules/set_http_method_type_rules",
            mapOf("type" to type, "status" to status),
            onResult
        ) { getHttpMethodTypeRules() }
    }

    /**
     * Go `GetHttpHeaderLenRules` → `wafrules/get_http_header_len_rules`。
     *
     * 无参数。返回 config.json 的 `header_len` 节点，形如
     * `{ "host": 500, "referer": 10000, "content-type": 300 }`，键为小写头部名。
     *
     * 与 get_http_method_type_rules 同样的假成功：缺节点时返回字符串
     * `获取数据为空`（code=0），本方法记入 [emptyNotice]。
     */
    fun getHttpHeaderLenRules(onError: (String?) -> Unit = {}) {
        rvQuery("wafrules/get_http_header_len_rules", onError = onError) { raw ->
            emptyNotice = (raw as? String)?.takeIf { it.isNotBlank() }
            headerLenRules = raw.asMap()
        }
    }

    /**
     * Go `AddHttpHeaderLenRules` → `wafrules/add_http_header_len_rules`。
     *
     * 必传键：`type`（请求头名，小写）, `length`（整数，该头长度上限）。
     *
     * 校验：`type` 已存在返回 `该类型已存在`。
     * 陷阱：config.json 缺 header_len 节点时无检查断言会 panic 500，
     * 所以必须先确认 [headerLenRules] 非空。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。
     */
    fun addHttpHeaderLenRules(
        type: String,
        length: Int,
        onResult: (String?) -> Unit = {}
    ) {
        if (type.isBlank()) {
            onResult("type 不能为空")
            return
        }
        if (headerLenRules.isEmpty()) {
            onResult("请求头长度配置尚未初始化，请先刷新全局配置")
            return
        }
        rvAct(
            "wafrules/add_http_header_len_rules",
            mapOf("type" to type, "length" to length),
            onResult
        ) { getHttpHeaderLenRules() }
    }

    /**
     * Go `ModifyHttpHeaderLenRules` → `wafrules/modify_http_header_len_rules`。
     *
     * 必传键：`type`, `length`（整数）。
     *
     * 陷阱一：与 Add 不同，**改一个不存在的 type 不会报错，会直接新增该键**。
     * 陷阱二：header_len 节点不存在时写入被跳过，仍返回「操作成功」（假成功）。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。
     */
    fun modifyHttpHeaderLenRules(
        type: String,
        length: Int,
        onResult: (String?) -> Unit = {}
    ) {
        if (type.isBlank()) {
            onResult("type 不能为空")
            return
        }
        rvAct(
            "wafrules/modify_http_header_len_rules",
            mapOf("type" to type, "length" to length),
            onResult
        ) { getHttpHeaderLenRules() }
    }

    /**
     * Go `DelHttpHeaderLenRules` → `wafrules/del_http_header_len_rules`。
     *
     * 必传键：`type`（请求头名）。
     *
     * 陷阱：删一个不存在的 type **不报错**，静默返回「操作成功」（假成功），
     * 删除后请重新 [getHttpHeaderLenRules] 确认。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。
     * 删掉某个头的长度上限等于取消该头的长度检查，可能放宽防护。
     */
    fun delHttpHeaderLenRules(
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (type.isBlank()) {
            onResult("type 不能为空")
            return
        }
        rvAct(
            "wafrules/del_http_header_len_rules",
            mapOf("type" to type),
            onResult
        ) { getHttpHeaderLenRules() }
    }

    // ================================================================== 蜘蛛池

    /**
     * Go `GetSpiderRules` → `wafrules/get_spider_rules`。
     *
     * 必传键：`spider_type`（蜘蛛池编号）。
     *
     * **两个硬约束（任一不满足都是 500，不是「参数错误」以外的友好提示）**：
     *  - 后端先 `InterfaceToInt` 校验，必须是 1 到 100 的整数，超范围直接返回 `参数错误`；
     *  - 之后用 spider_type 的字符串值 拼文件路径
     *    （waf/inc/编号.json），**所以必须传 JSON 字符串，传数字会 panic**。
     *    本方法已强制转成字符串提交。
     *
     * 返回的是**纯字符串数组**（蜘蛛 UA 片段），不是对象数组，
     * 所以不能走 asList()，本方法按 List of String 解析。
     */
    fun getSpiderRules(
        spiderType: Int,
        onError: (String?) -> Unit = {}
    ) {
        if (spiderType !in 1..100) {
            onError("spider_type 必须在 1 到 100 之间")
            return
        }
        rvQuery(
            "wafrules/get_spider_rules",
            mapOf("spider_type" to spiderType.toString()),
            onError
        ) { raw ->
            spiderRule = (raw as? List<*>)?.map { it.asString() } ?: emptyList()
        }
    }

    /**
     * Go `UpdateSpiderRules` → `wafrules/update_spider_rules`。
     *
     * **无参数**。后端对 1 到 8 号蜘蛛池逐个访问
     * `https://www.bt.cn/api/panel/get_spider_segment` 拉取远程 UA 片段，
     * 与本地文件取并集后覆写本地文件。
     *
     * **全流程假成功**：循环里所有错误（网络失败、读取失败、解析失败、写文件失败）
     * 都是 `continue` 跳过，函数结尾无条件 `core.Success("操作成功")`。
     * 也就是说**即使完全没更新成功，界面也会看到成功**。
     * 唯一可靠的验证方式是重新 [getSpiderRules] 对比条目数量。
     *
     * 另外本接口不调用 BackupWebWafConfig，也没有备份。
     * 依赖外网可达，内网环境必然拿不到新数据。
     */
    fun updateSpiderRules(onResult: (String?) -> Unit = {}) =
        rvAct("wafrules/update_spider_rules", emptyMap(), onResult)

    /**
     * Go `SimulatedAttack` → `wafrules/simulated_attack`。
     *
     * 无参数。读取 domains.json，为每个域名拼出一条 SQL 注入模拟攻击 URL：
     * `http://域名/?id=1'union select user(),1,3--`（通配域名 `*.` 会被去掉）。
     *
     * domains.json 为空返回 `没有站点`。
     *
     * 返回**纯字符串数组**，同样不能走 asList()。
     * 这是模拟攻击 URL，界面应用按钮形式展示「复制」而不是直接可点击跳转。
     */
    fun simulatedAttack(onError: (String?) -> Unit = {}) {
        rvQuery("wafrules/simulated_attack", onError = onError) { raw ->
            simulatedUrls = (raw as? List<*>)?.map { it.asString() } ?: emptyList()
        }
    }

    // ================================================================ 地区限制

    /**
     * Go `GetRegionalRestrictions` → `wafrules/get_regional_restrictions`。
     *
     * 无参数。返回 config/citys.json 的**原样内容**（国家/地区字典）。
     * 后端不做任何结构变换，形状随版本变化（可能是对象也可能是数组），
     * 所以本方法把原始值存进 [regionCatalogRaw]，由界面自行判断渲染方式。
     */
    fun getRegionalRestrictions(onError: (String?) -> Unit = {}) {
        rvQuery("wafrules/get_regional_restrictions", onError = onError) {
            regionCatalogRaw = it
        }
    }

    /**
     * Go `GetRegionalRestrictionsRules` → `wafrules/get_regional_restrictions_rules`。
     *
     * 必传键：`searchStr`（搜索串）, `p`（页码）, `p_size`（每页条数）。
     *
     * 搜索语义（不是模糊匹配站点名那么简单）：命中以下任一即算匹配——
     * region_id **完全相等**、地区名**包含** searchStr、站点名包含 searchStr。
     * 所以搜一个地区名会同时匹配「中国大陆」与「海外」这类聚合项。
     *
     * **陷阱：后端会对读到的数据做写操作。**
     * `GetAllRegionRules` 发现某条规则的站点 json / vhost conf 都不存在时，
     * 会**从规则文件里删掉这条规则**并回收站点授权（UpdateSiteAuthInfo disable）。
     * 也就是说「打开列表」可能悄悄改变服务端数据。
     *
     * 返回 list 与 total 两个字段的对象。列表项结构（后端已把 site 从 map 替换成
     * 站点名字符串）：region_id、types（refuse 拦截 / allow 只允许）、
     * site（站点名或「全部网站」）、region（地区 map，键为「中国」「海外」或省/市名）、
     * uri、status（布尔）、time（unix 秒）、count（命中次数）。
     *
     * `p` 必须大于等于 1，否则后端切片会 panic。
     */
    fun getRegionalRestrictionsRules(
        searchStr: String = "",
        page: Int = 1,
        pageSize: Int = 20,
        onError: (String?) -> Unit = {}
    ) {
        if (page < 1) {
            onError("页码必须大于 0")
            return
        }
        rvQuery(
            "wafrules/get_regional_restrictions_rules",
            mapOf("searchStr" to searchStr, "p" to page, "p_size" to pageSize),
            onError
        ) { raw ->
            val m = raw.asMap()
            regionRules = m["list"].asList()
            regionRuleTotal = (m["total"] as? Number)?.toInt() ?: regionRules.size
        }
    }

    /**
     * Go `AddRegionalRestrictionsRules` → `wafrules/add_regional_restrictions_rules`。
     *
     * 必传键：
     *  - `site_ids`：**JSON 数组**（后端做无检查的数组断言，传逗号串会 panic）。
     *    填 `allsite` 表示全部网站。
     *  - `type`：`refuse` 拦截 或 `allow` 只允许。
     *  - `region`：**JSON 数组**，地区名列表（省名或市名，或「中国」「海外」）。
     *  - `add_type`：`province` 按省 / `city` 按市 / `site_city` 海外。
     *  - `uri`：只对 province / city 有意义，写入规则的 uri 字段。
     *
     * 授权：`add_type != "site_city"` 时先查授权，不足返回 code=403
     * `授权网站数量不足`。
     *
     * `add_type = "site_city"` 的特殊行为：
     *  - 往 sqlite 的 region_free 表写一条 site_id + label=overseas 的记录；
     *    若已存在则先删后插，删除失败时返回
     *    `地区限制禁止海外访问` 加上站点 ID 再跟一个左方括号（**注意这条文案缺右括号，是后端笔误**）。
     *  - 插库失败返回 `添加规则失败：数据库插入失败`。
     *  - 之后 addType 被改写成 city，最终仍落到 city.json。
     *  - **只取 site_ids 的第一个元素**，多选站点时其余站点被忽略。
     *
     * 另一个重要限制：site_ids 传多个站点时，后端把数组用「, 」拼成一个字符串
     * 去 `site_info` 查 `site_name`，这个拼接值查不到任何行，
     * 随后 site_name 字段 会 panic 500。
     * **所以非 allsite 时只能传单个站点 ID**，本方法在多选非 allsite 时直接拦下。
     *
     * 副作用：写 province.json / city.json / sqlite region_free + 两次 UpdateWafConfig。
     * 新增地区限制会让对应地区的访客被拦截或被放行，属于影响线上可达性的操作。
     */
    fun addRegionalRestrictionsRules(
        siteIds: List<String>,
        type: String,
        region: List<String>,
        addType: String,
        uri: String = "",
        onResult: (String?) -> Unit = {}
    ) {
        if (siteIds.isEmpty()) {
            onResult("请至少选择一个网站")
            return
        }
        if (!rvNeedType(type, listOf("refuse", "allow"), "type", onResult)) return
        if (!rvNeedType(addType, listOf("province", "city", "site_city"), "add_type", onResult)) return
        if (region.isEmpty()) {
            onResult("请至少选择一个地区")
            return
        }
        if (siteIds.size > 1 && !siteIds.contains("allsite")) {
            onResult("后端按单个站点查询站点名，多选非全部网站会报错，请只选一个站点")
            return
        }
        rvAct(
            "wafrules/add_regional_restrictions_rules",
            mapOf(
                "site_ids" to siteIds,
                "type" to type,
                "region" to region,
                "add_type" to addType,
                "uri" to uri
            ),
            onResult
        ) { getRegionalRestrictionsRules() }
    }

    /**
     * Go `SetRegionalRestrictionsRules` → `wafrules/set_regional_restrictions_rules`。
     *
     * 必传键：`region_id`（**单个字符串**，不是数组）, `status`（布尔）。
     *
     * 前置校验：region_id 必须在 province.json / city.json 里存在，
     * 否则返回 `未找到对应的网站`。
     *
     * 授权：`status = true` 时查授权，不足返回 code=403 `授权网站数量不足`。
     *
     * 副作用：改两个规则文件 + 两次 UpdateWafConfig + 备份。
     * 启用一条「只允许」规则会让未列出的地区全部被拒绝访问。
     */
    fun setRegionalRestrictionsRules(
        regionId: String,
        status: Boolean,
        onResult: (String?) -> Unit = {}
    ) {
        if (regionId.isBlank()) {
            onResult("region_id 不能为空")
            return
        }
        rvAct(
            "wafrules/set_regional_restrictions_rules",
            mapOf("region_id" to regionId, "status" to status),
            onResult
        ) { getRegionalRestrictionsRules() }
    }

    /**
     * Go `DelRegionalRestrictionsRules` → `wafrules/del_regional_restrictions_rules`。
     *
     * 必传键：`region_id`，且是 **JSON 数组**（后端做无检查的数组断言，传字符串会 panic）。
     *
     * 校验：数组里每个 id 都会先查一次，查不到任一即返回 `未找到对应的网站`，
     * **整个删除直接失败，一个都不会被删**。
     *
     * 副作用（连带数据库操作）：若被删规则是「海外」且作用于具体站点，
     * 会顺手删掉 sqlite region_free 里对应的记录。
     *
     * 删除不可撤销，UI 必须二次确认并展示将要删除的规则 ID。
     */
    fun delRegionalRestrictionsRules(
        regionIds: List<String>,
        onResult: (String?) -> Unit = {}
    ) {
        if (regionIds.isEmpty()) {
            onResult("请至少选择一条规则")
            return
        }
        rvAct(
            "wafrules/del_regional_restrictions_rules",
            mapOf("region_id" to regionIds),
            onResult
        ) { getRegionalRestrictionsRules() }
    }

    /**
     * Go `ResetRegionalCountryRules` → `wafrules/reset_regional_country_rules`。
     *
     * 必传键：`region_id`，**单个字符串**（不是数组）。
     * region_id 不存在返回 `未找到对应的网站`。
     *
     * 作用：把该规则的命中次数 count 清零（统计数据丢失，规则本身保留）。
     *
     * 副作用：改两个规则文件 + 两次 UpdateWafConfig + 备份。
     */
    fun resetRegionalCountryRules(
        regionId: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (regionId.isBlank()) {
            onResult("region_id 不能为空")
            return
        }
        rvAct(
            "wafrules/reset_regional_country_rules",
            mapOf("region_id" to regionId),
            onResult
        ) { getRegionalRestrictionsRules() }
    }

    // ============================================================ 单 URL 频率规则

    /**
     * Go `SetSingleUri` → `wafrules/set_single_uri`（单 URL 频率限制）。
     *
     * 必传键：`uri`（路径）, `cycle`（统计周期，秒）, `frequency`（周期内次数上限）,
     * `type`（`add` 新增 / `modify` 编辑 / `del` 删除）。
     *
     * **返回 res 是布尔值 true**（后端 `return core.Success(result)`，result 是 bool），
     * 不是文案。界面不要把 res 当提示语展示。
     *
     * 后端 bug：config.json 里没有 cc_uri_frequency 节点时，
     * **无论 type 是什么（包括 del）都会走到 else 分支新建该节点**，
     * 于是「删除」一个不存在的规则反而会把它创建出来。
     *
     * 现有规则没有独立查询接口，从 [globalRules] 的 `cc_uri_frequency` 字段取，
     * 形态是 `{ "/path": { "frequency": N, "cycle": N } }`。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。
     */
    fun setSingleUri(
        uri: String,
        cycle: Int,
        frequency: Int,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (uri.isBlank()) {
            onResult("uri 不能为空")
            return
        }
        if (!rvNeedType(type, listOf("add", "modify", "del"), "type", onResult)) return
        rvAct(
            "wafrules/set_single_uri",
            mapOf("uri" to uri, "cycle" to cycle, "frequency" to frequency, "type" to type),
            onResult
        ) { getGlobalRules { _, _ -> } }
    }

    /**
     * Go `ApplyGlobalRulesToSite` → `wafrules/apply_global_rules_to_site`。
     *
     * 必传键：`type`（全局规则名）, `site_id_list`（**JSON 数组**）。
     *
     * `type` 只能取 GLOBAL_APPLY_RULE_TYPES 里的 16 个键，传其他值返回 `参数错误`：
     * cc 基础CC防护、cookie cookie头攻击防御、download 恶意下载防御、
     * scan 扫描器防御、file_import 文件包含防御、file_upload 拦截恶意文件上传、
     * from_data 畸形文件上传协议检测、number_attacks 攻击次数拦截、
     * sql SQL注入拦截、xss XSS注入拦截、ssrf ssrf代码执行检测、
     * user_agent 恶意爬虫防御、php_eval PHP代码执行检测、idc 禁用IDC、
     * rce 命令执行拦截。
     *
     * `site_id_list` 为空返回 `请至少选择一个网站`。
     *
     * **部分成功语义（最需要 UI 注意的一点）**：
     * 站点配置读不到、站点不在 site.json、或者写盘失败的站点会被跳过，
     * 但函数结尾**无条件返回 code=0**，并把成功与失败的站点名一起拼进 res：
     * 成功段用 `全局规则【X】应用到以下网站成功：` 加站点名，失败段用
     * `，应用到以下网站失败：` 加站点名，段内用 br 标签分隔。
     * 所以本方法在成功时通过 [onMessage] 把 res 原文交出去，
     * 同时写入 [applyResultMessage]，界面必须展示失败站点而不是只弹「操作成功」。
     *
     * 附带行为：除 rce 外，规则节点里原有的 `count` 命中次数会被保留。
     *
     * 副作用：**逐站写 site.json**（每个成功站点写一次盘）+ 两次 UpdateWafConfig。
     * 会覆盖选中站点对应类别的全部配置，不可撤销，必须二次确认。
     */
    fun applyGlobalRulesToSite(
        type: String,
        siteIdList: List<String>,
        onResult: (String?) -> Unit = {},
        onMessage: (String) -> Unit = {}
    ) {
        if (siteIdList.isEmpty()) {
            onResult("请至少选择一个网站")
            return
        }
        if (type !in GLOBAL_APPLY_RULE_TYPES) {
            onResult("type 不受支持，可选：${GLOBAL_APPLY_RULE_TYPES.keys.joinToString(" / ")}")
            return
        }
        viewModelScope.launch {
            saving = true
            error = null
            formError = null
            try {
                val r = repo.call(
                    "wafrules/apply_global_rules_to_site",
                    mapOf("type" to type, "site_id_list" to siteIdList)
                )
                val text = r.res.asString()
                applyResultMessage = text.ifBlank { null }
                if (r.isSuccess) {
                    if (text.isNotBlank()) {
                        lastMessage = text
                        onMessage(text)
                    }
                    onResult(null)
                } else {
                    val msg = r.errorMessage().ifBlank { "操作失败" }
                    error = msg
                    formError = msg
                    onResult(msg)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "操作失败"
                error = msg
                formError = msg
                onResult(msg)
            } finally {
                saving = false
            }
        }
    }

    // ================================================= IDC / 恶意 IP / 护网 / 恢复

    /**
     * Go `SetIdcDeny` → `wafrules/set_idc_deny`（禁用 IDC 机房访问）。
     *
     * 必传键：`site_id`, `open`（**整数** 0 或 1，后端走 InterfaceToInt，传布尔也能解析但语义按 0/1）,
     * `type`（`global` / `not_global` / `site`）。
     *
     * **陷阱：`type` 不在 ParamsCheck 列表里但函数体无条件读取**，漏传会 panic 500。
     *
     * 授权：`open = 1` 时查授权，不足返回 code=403 `授权网站数量不足`。
     *
     * 写入结构是 `idc: { mode: <open>, ps: "IDC限制" }`。
     * 注意后端在 not_global / site 分支里用的是
     * 站点 json 里对应的节点 的无检查断言，site_id 不在 site.json 里会 panic。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。
     * 开启后 IDC 机房 IP 段的访客会被拒绝访问，属于影响线上可达性的操作。
     */
    fun setIdcDeny(
        siteId: String,
        open: Int,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "not_global", "site"), "type", onResult)) return
        if (open != 0 && open != 1) {
            onResult("open 只能是 0 或 1")
            return
        }
        rvAct(
            "wafrules/set_idc_deny",
            mapOf("site_id" to siteId, "open" to open, "type" to type),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `SetMaliciousIp` → `wafrules/set_malicious_ip`（云端恶意 IP 库）。
     *
     * 必传键：`site_id`, `open`（整数 0/1）, `type`。
     * **`type` 同样不在 ParamsCheck 里但无条件读取，漏传 panic 500。**
     *
     * **最严重的后端缺陷：switch 只有 `case "global"` 一个分支。**
     * 传 `not_global` 或 `site` 时不会写任何东西，函数却继续走到
     * WriteSiteConfig + 备份 + 返回「操作成功」——**纯假成功**。
     * 所以本接口实际上只能改全局配置，UI 不应提供站点级开关入口。
     *
     * 写入结构是 `malicious_ip: { mode: <open>, ps: "云端恶意IP库" }`。
     *
     * 副作用：写 config.json + 两次 UpdateWafConfig + 备份。全局生效。
     */
    fun setMaliciousIp(
        siteId: String,
        open: Int,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "not_global", "site"), "type", onResult)) return
        if (open != 0 && open != 1) {
            onResult("open 只能是 0 或 1")
            return
        }
        rvAct(
            "wafrules/set_malicious_ip",
            mapOf("site_id" to siteId, "open" to open, "type" to type),
            onResult
        ) { getGlobalRules { _, _ -> } }
    }

    /**
     * Go `SetHvv` → `wafrules/set_hvv`（护网 / 只读模式）。
     *
     * 必传键：`site_id`, `open`（**布尔**，注意与 setIdcDeny 的整数不同）,
     * `type`（`global` / `not_global` / `site`）。
     * **`type` 不在 ParamsCheck 里但无条件读取，漏传 panic 500。**
     *
     * 授权：`open = true` 时查授权，不足返回 code=403 `授权网站数量不足`。
     *
     * 写入结构是 `readonly: { open, ps }`，后端给 ps 写死的说明是
     * 「只读模式,请勿在非攻防演练时开启,开启后将会影响用户登录、支付、搜索、
     * 注册、评论等功能」。
     *
     * **高危**：这是全模块唯一会直接破坏站点业务功能的开关，
     * 开启后登录、支付、搜索、注册、评论都会受影响。
     * UI 必须用高危确认弹窗，并在确认文案里明确写出受影响功能。
     *
     * 副作用：写配置 + 两次 UpdateWafConfig + 备份。
     */
    fun setHvv(
        siteId: String,
        open: Boolean,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "not_global", "site"), "type", onResult)) return
        rvAct(
            "wafrules/set_hvv",
            mapOf("site_id" to siteId, "open" to open, "type" to type),
            onResult
        ) { if (type == "global") getGlobalRules { _, _ -> } else getRules(siteId) }
    }

    /**
     * Go `RecoverDefaultConfig` → `wafrules/recover_default_config`（一键恢复默认配置）。
     *
     * 必传键：`site_id`, `type`（只有 `global` 与 `site`）。
     *
     * **两个 type 的行为天差地别，UI 必须区分清楚**：
     *
     *  - `type = "site"`：**把该站整份规则重置为后端硬编码的出厂默认值**
     *    （mode=2、CC 开启且 limit 120 / cycle 60 / endtime 300、文件上传 mode=3、
     *    SQL/XSS 拦截 mode=3、目录扫描外的各类防护重置、
     *    disable_upload_ext 重置为 php 与 jsp、cdn_header 重置为默认 14 个头等）。
     *    **仅保留 idc、smart_cc、cdn、cdn_baidu、readonly 五项已有配置。**
     *    其余用户定制的防护参数全部丢失，不可撤销，必须二次确认。
     *
     *  - `type = "global"`：**什么都不修改**，只写一条操作日志就返回「操作成功」。
     *    这是纯假成功，界面上不应把它呈现为「恢复全局默认配置」的成功提示。
     *
     * 注意 site 分支里 把整份默认配置写回该 site_id 节点 之前，
     * 对 idc 的 mode 做了 `.(float64)` 强转：若该站 idc.mode 是字符串会 panic 500。
     *
     * 副作用：site 分支只写 site.json，**不调用两次 UpdateWafConfig，也不备份配置**，
     * 规则要等到下一次配置更新才生效。
     */
    fun recoverDefaultConfig(
        siteId: String,
        type: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (!rvNeedType(type, listOf("global", "site"), "type", onResult)) return
        rvAct(
            "wafrules/recover_default_config",
            mapOf("site_id" to siteId, "type" to type),
            onResult
        ) { if (type == "site") getRules(siteId) }
    }

    // ============================================================== URL 重写

    /**
     * Go `SetRewriteUrl` → `wafrules/set_rewrite_url`。
     *
     * 必传键：`site_id`, `old_url`（原 URL）, `rewrite_url`（目标 URL）。
     *
     * **方向陷阱**：后端写入的是 `re_url[rewrite_url] = old_url`，
     * 即 map 的**键是目标 URL、值是原 URL**，与入参名字的直觉相反。
     * 读 [siteRules] 的 `rewrite_url` 字段展示时要按「键 → 值」读成
     * 「重写为 X，原地址是 Y」。
     *
     * 限制：每站**最多 5 条**（已有 5 条时再提交返回 `只允许五个url重写`）。
     *
     * 假成功：site_id 不在 site.json 里时静默跳过，仍返回「操作成功」。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。
     * URL 重写直接改变对外访问路径，配错会导致站点 404，必须二次确认。
     */
    fun setRewriteUrl(
        siteId: String,
        oldUrl: String,
        rewriteUrl: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (oldUrl.isBlank() || rewriteUrl.isBlank()) {
            onResult("原 URL 与重写 URL 都不能为空")
            return
        }
        rvAct(
            "wafrules/set_rewrite_url",
            mapOf("site_id" to siteId, "old_url" to oldUrl, "rewrite_url" to rewriteUrl),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `DelRewriteUrl` → `wafrules/del_rewrite_url`。
     *
     * 必传键：`site_id`, `old_url`, `rewrite_url`（三个都必传，缺一即 `参数错误`）。
     *
     * **匹配语义极反直觉**：后端遍历整张 rewrite_url 表，
     * **只按 value（即 old_url）比对**，命中的全部删除，`rewrite_url` 参数
     * 实际从未被使用（只为通过 ParamsCheck）。
     * 后果是：如果多条规则的 old_url 相同，会被**一次性全部删除**。
     * 所以调用方应保证 old_url 在该站唯一（正常由 setRewriteUrl 写入的数据满足）。
     *
     * 删完若表被清空，后端写入的是空对象而不是删除该键，界面按空 map 渲染即可。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。删除不可撤销。
     */
    fun delRewriteUrl(
        siteId: String,
        oldUrl: String,
        rewriteUrl: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (oldUrl.isBlank()) {
            onResult("原 URL 不能为空")
            return
        }
        rvAct(
            "wafrules/del_rewrite_url",
            mapOf("site_id" to siteId, "old_url" to oldUrl, "rewrite_url" to rewriteUrl),
            onResult
        ) { getRules(siteId) }
    }

    // ========================================================== 爬虫加密 / 等待室

    /**
     * Go `SetCrawlerInfos` → `wafrules/set_crawler_infos`（爬虫加密与水印）。
     *
     * 必传键：`site_id`, `encryption`（**JSON 对象**）, `watermark`（**JSON 对象**）。
     *
     * 两个子对象的结构完全相同，各含三个键：
     * `open`（布尔）, `type`（字符串）, `text`（字符串）。
     * 三个键都被无检查断言，**一个都不能少，传字符串给 open 会 panic 500**。
     * 可用 [rvCrawlerPart] 之外手写，但务必保证类型。
     *
     * 校验文案：
     *  - encryption.type 不是 `default` 或 `text` → `加密类型错误`
     *  - watermark.type 不是 `default` 或 `text` → `水印类型错误`
     *  - site_id 不在 site.json 里 → `站点ID不存在`
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。
     * 这是**整块覆盖**（后端直接 `jsonData[site_id]["crawler"] = crawler`），
     * 不传全三个键会把已有配置清掉。
     */
    fun setCrawlerInfos(
        siteId: String,
        encryptionOpen: Boolean,
        encryptionType: String,
        encryptionText: String,
        watermarkOpen: Boolean,
        watermarkType: String,
        watermarkText: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (encryptionType !in CRAWLER_TYPES) {
            onResult("加密类型仅支持 default 或 text")
            return
        }
        if (watermarkType !in CRAWLER_TYPES) {
            onResult("水印类型仅支持 default 或 text")
            return
        }
        rvAct(
            "wafrules/set_crawler_infos",
            mapOf(
                "site_id" to siteId,
                "encryption" to rvCrawlerPart(
                    encryptionOpen, encryptionType, encryptionText
                ),
                "watermark" to rvCrawlerPart(
                    watermarkOpen, watermarkType, watermarkText
                )
            ),
            onResult
        ) { getRules(siteId) }
    }

    /**
     * Go `SetCcRulesWait` → `wafrules/set_cc_rules_wait`（CC 等待室）。
     *
     * 必传键：`site_id`, `open`（布尔，控制该站 CC 防护总开关）,
     * `wait`（**JSON 对象**，结构见下）。
     *
     * `wait` 对象含六个键，全部被无检查断言：
     * `open`（布尔）, `time`（数字）, `qps`（数字）, `user`（数字）,
     * `type`（字符串）, `text`（字符串）。
     * **time / qps / user 是 float64 断言，必须传 JSON 数字，传字符串会 panic 500。**
     *
     * 校验文案（按后端顺序，可用于表单逐项标红）：
     *  - type 不是 `default` 或 `text` → `等待室类型错误`
     *  - time 小于 1 → `等待时间必须大于1`
     *  - user 小于 1 → `用户数必须大于1`
     *  - qps 小于 0 → `QPS必须大于0`
     *
     * **连带副作用**：本接口除了写 wait 节点，还会顺手把该站 cc.open 一起改掉。
     * 所以「只想调整等待室参数」也会顺带切换 CC 防护开关，界面文案要提示清楚。
     *
     * 另：site_id 不在 site.json 里时静默跳过，却仍返回「操作成功」（假成功）。
     *
     * 副作用：写 site.json + 两次 UpdateWafConfig + 备份。
     */
    fun setCcRulesWait(
        siteId: String,
        open: Boolean,
        waitOpen: Boolean,
        waitTime: Int,
        waitQps: Int,
        waitUser: Int,
        waitType: String,
        waitText: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (!rvNeedSiteId(siteId, onResult)) return
        if (waitType !in CRAWLER_TYPES) {
            onResult("等待室类型仅支持 default 或 text")
            return
        }
        rvAct(
            "wafrules/set_cc_rules_wait",
            mapOf(
                "site_id" to siteId,
                "open" to open,
                "wait" to rvWaitPart(waitOpen, waitTime, waitQps, waitUser, waitType, waitText)
            ),
            onResult
        ) { getRules(siteId) }
    }

    // ============================================================== 流量限制

    /**
     * Go `GetUserLimit` → `wafrules/get_user_limit`。
     *
     * 无参数。返回限流规则数组，文件不存在或读取失败时返回**空数组**（不是错误）。
     *
     * 列表项结构对应 Go 的 UserLimit：
     * `id`（16 位随机串，新增时自动生成）, `name`（规则名，全局唯一）,
     * `site`（**站点 ID 到 1 的 map**，展示时要自己映射成站点名）,
     * `types`（`all` 全站 / `url` 指定 URL）, `url`, `condition`（1/2/3）,
     * `region`（含 `req` 或 `count.time` / `count.count`）, `action`
     * （status / content / drop / status_404 / status_403 / status_502 / status_503）,
     * `return`（html / json / 444）, `status`（固定 403）, `open`（布尔开关）。
     */
    fun getUserLimit(onError: (String?) -> Unit = {}) {
        rvQuery("wafrules/get_user_limit", onError = onError) {
            userLimits = it.asList()
        }
    }

    /**
     * Go `AddUserLimit` → `wafrules/add_user_limit`（新增限流规则）。
     *
     * 必传键（9 个，缺一即 `参数错误`）：
     * `name`, `site`, `types`, `url`, `condition`, `region`, `action`, `return`。
     *
     * **参数类型（都跟直觉不一样，务必按下表传）**：
     *  - `site`：**逗号分隔的字符串**（后端 `strings.Split`），
     *    例如 `"1,2"`，表示同时作用于多个站点；**不是 JSON 数组**。
     *    可以用 `allsite` 表示全部网站。
     *  - `region`：**JSON 对象**（不是数字）。键二选一：
     *    `req` 表示每秒请求数限制；
     *    `count` 表示时间窗口限制，其下必须同时有 `time` 与 `count` 两个数字键。
     *    可用 [rvLimitRegion] 的思路构造。两个都不传会返回「请选择一个限制方式」。
     *  - `condition`：整数，只能是 1、2、3，否则「访问限制条件错误」。
     *  - `action`：只能取 status / content / drop / status_404 / status_403 /
     *    status_502 / status_503，否则「操作选项设置错误」。
     *  - `types`：只能取 `all` 或 `url`，否则「请选择一个路径类型」。
     *    选 `all` 时后端会把 url 强制改成 `/`。
     *  - `return`：action 为 content 时才是自定义内容；
     *    action 为 drop 与四种 status_xxx 时后端强制改成 `html`。
     *    最终值必须是 html / json / 444，否则「返回内容错误类型错误」。
     *
     * 校验文案全集：site不能为空 / 请选择一个限制方式 / 返回内容不能为空 /
     * 请选择一个路径类型 / 名称不能包含特殊字符（name 里不能有尖括号或双引号）/
     * 参数错误,url不能为空 / 指定URL不能为 /  不然会全站都会限流（注意中间两个空格）/
     * 参数错误,url必须以/开头 / 每秒访问限制-请求数不能小于1 /
     * 时间访问限制-时间不能小于30秒 / 时间访问限制次数不能小于20次 /
     * 访问限制条件错误 / 返回内容错误类型错误 / 名称已存在 / 此条规则已经存在。
     *
     * **高危数据风险**：当 limit.json 读取失败或反序列化失败时，
     * 后端会 `WriteFile` 写入一个**只包含本次新规则的数组**，
     * 等于把已有的全部限流规则清空。提交前建议先 [getUserLimit] 确认文件可读。
     *
     * 成功返回的是字符串 `添加成功`。
     *
     * 副作用：写 limit.json + 两次 UpdateWafConfig + 备份。
     */
    fun addUserLimit(
        name: String,
        siteIds: List<String>,
        types: String,
        url: String,
        condition: Int,
        region: Map<String, Any?>,
        action: String,
        returnType: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (name.isBlank()) {
            onResult("名称不能为空")
            return
        }
        if (siteIds.isEmpty()) {
            onResult("site不能为空")
            return
        }
        if (types !in USER_LIMIT_TYPES) {
            onResult("types 仅支持 all 或 url")
            return
        }
        if (action !in USER_LIMIT_ACTIONS) {
            onResult("action 不受支持，可选：${USER_LIMIT_ACTIONS.joinToString(" / ")}")
            return
        }
        if (region.isEmpty()) {
            onResult("请选择一个限制方式")
            return
        }
        rvAct(
            "wafrules/add_user_limit",
            mapOf(
                "name" to name,
                "site" to siteIds.joinToString(","),
                "types" to types,
                "url" to url,
                "condition" to condition,
                "region" to region,
                "action" to action,
                "return" to returnType
            ),
            onResult
        ) { getUserLimit() }
    }

    /**
     * Go `EditUserLimit` → `wafrules/edit_user_limit`（编辑限流规则）。
     *
     * 必传键：在 add 的 9 个基础上多一个 `id`，共 10 个。参数类型与校验规则
     * 与 [addUserLimit] 完全一致（`site` 逗号串、`region` 对象、condition 1 到 3 等）。
     *
     * **假成功（必须让用户看见）**：以下三种情况后端都返回 code=0 与字符串
     * `没有此条规则`——limit.json 读不出来、解析失败、传入的 id 在列表里找不到。
     * 也就是说**改一个不存在的规则会提示成功**。
     * 本方法在成功时把 res 原文写进 [lastMessage]，界面需要判断这个文案并提示未生效。
     *
     * 名称冲突文案：与 add 不同，改名撞上其他规则时返回 `名称已经被其他规则使用`。
     *
     * 成功返回字符串 `修改成功`。
     *
     * 副作用：整体覆写 limit.json + 两次 UpdateWafConfig + 备份。
     */
    fun editUserLimit(
        id: String,
        name: String,
        siteIds: List<String>,
        types: String,
        url: String,
        condition: Int,
        region: Map<String, Any?>,
        action: String,
        returnType: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (id.isBlank()) {
            onResult("id 不能为空")
            return
        }
        if (name.isBlank()) {
            onResult("名称不能为空")
            return
        }
        if (siteIds.isEmpty()) {
            onResult("site不能为空")
            return
        }
        if (types !in USER_LIMIT_TYPES) {
            onResult("types 仅支持 all 或 url")
            return
        }
        if (action !in USER_LIMIT_ACTIONS) {
            onResult("action 不受支持，可选：${USER_LIMIT_ACTIONS.joinToString(" / ")}")
            return
        }
        if (region.isEmpty()) {
            onResult("请选择一个限制方式")
            return
        }
        rvAct(
            "wafrules/edit_user_limit",
            mapOf(
                "id" to id,
                "name" to name,
                "site" to siteIds.joinToString(","),
                "types" to types,
                "url" to url,
                "condition" to condition,
                "region" to region,
                "action" to action,
                "return" to returnType
            ),
            onResult
        ) { getUserLimit() }
    }

    /**
     * Go `DelUserLimit` → `wafrules/del_user_limit`（删除限流规则）。
     *
     * 必传键：`id`。
     *
     * **假成功**：文件读不出来、解析失败、或 id 不存在时，
     * 后端返回 code=0 与字符串 `没有此条规则`，界面必须识别（见 [lastMessage]）。
     * 成功返回字符串 `删除成功`。
     *
     * 副作用：整体覆写 limit.json + 两次 UpdateWafConfig + 备份。
     * 误删会让该限流规则立即失效，可能导致站点被刷或正常用户被拦，需二次确认。
     */
    fun delUserLimit(
        id: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (id.isBlank()) {
            onResult("id 不能为空")
            return
        }
        rvAct("wafrules/del_user_limit", mapOf("id" to id), onResult) { getUserLimit() }
    }

    /**
     * Go `SetUserLimit` → `wafrules/set_user_limit`（切换限流规则开关）。
     *
     * 必传键：`id`。
     *
     * 语义是**取反**（`Open = !Open`），不是设置成指定值。
     * 所以界面上不要传「目标开关状态」，只需确认用户想切换即可，
     * 且连续点两次等于没改。
     *
     * **假成功**：文件读不出来、解析失败、或 id 不存在时返回 code=0 与
     * 字符串 `没有此条规则`（见 [lastMessage]）。成功返回 `设置成功`。
     *
     * 副作用：整体覆写 limit.json + 两次 UpdateWafConfig + 备份。
     */
    fun setUserLimit(
        id: String,
        onResult: (String?) -> Unit = {}
    ) {
        if (id.isBlank()) {
            onResult("id 不能为空")
            return
        }
        rvAct("wafrules/set_user_limit", mapOf("id" to id), onResult) { getUserLimit() }
    }

    // ---------------------------------------------------------------- 通用入口

    /**
     * 通用写入口，供「按路径 + 参数Map」驱动的动态表单使用。
     *
     * 只适用于参数已在调用方拼装成 Map 的场景；参数里有对象或数组时，
     * 务必用本文件里对应的专用方法，避免类型踩坑。
     */
    fun run(
        path: String,
        params: Map<String, Any?>,
        onResult: (String?) -> Unit
    ) = rvAct(path, params, onResult)

    // ---------------------------------------------------------------- 常量表

    companion object {
        /**
         * setModeRules / setFileUploadRules 的 modename 合法集合。
         * 来自后端默认配置里存在的防护节点，传其他值会 panic 500。
         */
        val RULE_MODE_KEYS = listOf(
            "sql", "xss", "ssrf", "download", "scan", "file_import",
            "file_upload", "from_data", "user_agent", "php_eval", "cookie"
        )

        /** applyGlobalRulesToSite 的 type 到中文名的映射，后端用同一份文案。 */
        val GLOBAL_APPLY_RULE_TYPES: Map<String, String> = linkedMapOf(
            "cc" to "基础CC防护",
            "cookie" to "cookie头攻击防御",
            "download" to "恶意下载防御",
            "scan" to "扫描器防御",
            "file_import" to "文件包含防御",
            "file_upload" to "拦截恶意文件上传",
            "from_data" to "畸形文件上传协议检测",
            "number_attacks" to "攻击次数拦截",
            "sql" to "SQL注入拦截",
            "xss" to "XSS注入拦截",
            "ssrf" to "ssrf代码执行检测",
            "user_agent" to "恶意爬虫防御",
            "php_eval" to "PHP代码执行检测",
            "idc" to "禁用IDC",
            "rce" to "命令执行拦截"
        )

        /** SetCrawlerInfos 与 SetCcRulesWait 允许的 type 值。 */
        val CRAWLER_TYPES = listOf("default", "text")

        /** UserLimit 的 types 取值。 */
        val USER_LIMIT_TYPES = listOf("all", "url")

        /** UserLimit 的 action 取值。 */
        val USER_LIMIT_ACTIONS = listOf(
            "status", "content", "drop",
            "status_404", "status_403", "status_502", "status_503"
        )

        /** setSiteRunMode 的 mode 枚举（public.SiteMode）。 */
        val SITE_RUN_MODES = listOf(
            0 to "关闭防护模式",
            1 to "只记录模式",
            2 to "拦截模式",
            3 to "显示维护页模式"
        )

        /** setModeRules / setFileUploadRules 的 mode 枚举（public.SiteWafMode）。 */
        val WAF_MODES = listOf(
            0 to "关闭防护模式",
            1 to "观察模式",
            2 to "标准模式",
            3 to "严格模式"
        )

        /** 地区限制规则的 type 取值。 */
        val REGION_RULE_TYPES = listOf("refuse" to "拦截", "allow" to "只允许")
    }
}
